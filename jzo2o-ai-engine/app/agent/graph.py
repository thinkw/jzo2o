"""Agent 图构建 — StateGraph 编排 LLM 调用和工具执行"""
import logging
from typing import Literal

from langchain_core.messages import AIMessage, ToolMessage
from langgraph.graph import StateGraph, END
from langgraph.checkpoint.memory import InMemorySaver

from .state import AgentState
from .tools import ALL_TOOLS, REMOTE_TOOL_NAMES, local_tools_by_name
from .tool_context import ToolExecutionContext

logger = logging.getLogger(__name__)


def build_graph(tool_ctx: ToolExecutionContext):
    """构建并返回已编译的 Agent StateGraph

    Args:
        tool_ctx: ToolExecutionContext 实例, 管理远程工具调用

    Returns:
        已编译的 CompiledStateGraph, 可通过 astream() 进行流式调用
    """
    from app.core.llm_client import create_llm

    llm = create_llm()
    llm_with_tools = llm.bind_tools(ALL_TOOLS)

    # ── 节点定义为闭包, 捕获 llm_with_tools 和 tool_ctx ──

    async def call_model(state: AgentState) -> dict:
        """调用 LLM, 返回 AIMessage (可能包含 tool_calls)"""
        response = await llm_with_tools.ainvoke(state["messages"])
        return {"messages": [response]}

    async def tool_executor(state: AgentState) -> dict:
        """执行工具: 本地工具直接调用, 远程工具通过 WebSocket 发给 Java"""
        last_message = state["messages"][-1]
        if not isinstance(last_message, AIMessage) or not last_message.tool_calls:
            return {"messages": []}

        outputs: list[ToolMessage] = []
        for tc in last_message.tool_calls:
            tool_name = tc["name"]
            tool_call_id = tc["id"]
            args = tc.get("args", {})

            if tool_name in REMOTE_TOOL_NAMES:
                # 远程工具: 发给 Java 执行, 等待结果
                logger.info("执行远程工具: %s (id=%s)", tool_name, tool_call_id)
                result = await tool_ctx.execute(tool_call_id, tool_name, args)
            else:
                # 本地工具: 直接执行
                tool = local_tools_by_name.get(tool_name)
                if tool is None:
                    result = f"错误: 未知工具 {tool_name}"
                else:
                    logger.info("执行本地工具: %s (id=%s)", tool_name, tool_call_id)
                    result = await tool.ainvoke(args)

            outputs.append(ToolMessage(content=str(result), tool_call_id=tool_call_id))

        return {"messages": outputs}

    def should_continue(state: AgentState) -> Literal["tools", "__end__"]:
        """判断是否需要继续执行工具"""
        messages = state["messages"]
        last_message = messages[-1]
        if isinstance(last_message, AIMessage) and last_message.tool_calls:
            return "tools"
        return "__end__"

    # ── 构建图 ──
    builder = StateGraph(AgentState)
    builder.add_node("call_model", call_model)
    builder.add_node("tools", tool_executor)
    builder.add_edge("__start__", "call_model")
    builder.add_conditional_edges("call_model", should_continue, {
        "tools": "tools",
        "__end__": END,
    })
    builder.add_edge("tools", "call_model")

    checkpointer = InMemorySaver()
    return builder.compile(checkpointer=checkpointer)
