"""Agent 图构建 — StateGraph 编排 LLM 调用和工具执行"""
import logging
import os
from pathlib import Path
from typing import Literal, Optional

from langchain_core.messages import AIMessage, ToolMessage
from langgraph.graph import StateGraph, END
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from .state import AgentState
from .tools import ALL_TOOLS, REMOTE_TOOL_NAMES, local_tools_by_name
from .tool_context import ToolExecutionContext

logger = logging.getLogger(__name__)

# 模块级共享 checkpointer: 所有会话共用同一个 SQLite 数据库,
# 通过 config["configurable"]["thread_id"] 区分不同会话的检查点
_checkpointer: Optional[AsyncSqliteSaver | InMemorySaver] = None


async def get_checkpointer() -> AsyncSqliteSaver | InMemorySaver:
    """获取共享的持久化 checkpointer, 首次调用时初始化"""
    global _checkpointer
    if _checkpointer is not None:
        return _checkpointer

    from app.core.config import settings

    db_path = settings.checkpoint_db_path
    if db_path:
        import aiosqlite
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        # 手动创建连接和 saver, 避免 context manager 的生命周期问题
        conn = await aiosqlite.connect(db_path)
        _checkpointer = AsyncSqliteSaver(conn)
        await _checkpointer.setup()
        logger.info("检查点持久化已启用: %s (绝对路径: %s)", db_path,
                     os.path.abspath(db_path))
        return _checkpointer
    else:
        logger.info("未配置检查点路径, 使用内存模式 (重启后丢失)")
        return InMemorySaver()


def build_graph(tool_ctx: ToolExecutionContext,
                checkpointer=None):
    """构建并返回已编译的 Agent StateGraph

    Args:
        tool_ctx: ToolExecutionContext 实例, 管理远程工具调用
        checkpointer: 可选, 外部传入的 checkpointer (流模式复用共享实例)

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

    # 使用传入的 checkpointer 或默认内存模式
    actual_checkpointer = checkpointer if checkpointer is not None else InMemorySaver()
    return builder.compile(checkpointer=actual_checkpointer)
