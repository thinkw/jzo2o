"""Agent 状态定义 — 基于 LangGraph MessagesState"""
from typing import Annotated, Sequence, TypedDict
from langchain_core.messages import AnyMessage
from langgraph.graph.message import add_messages


class AgentState(TypedDict):
    """Agent 状态, 使用 add_messages reducer 管理对话历史"""
    messages: Annotated[Sequence[AnyMessage], add_messages]
