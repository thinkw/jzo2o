"""WebSocket 消息模型 — Java ↔ Python 通信协议"""
from typing import Any, List, Literal, Optional
from pydantic import BaseModel, Field


# ──── Java → Python ────

class WsClientMessage(BaseModel):
    """Java → Python: 用户消息或取消请求"""
    type: Literal["user_message", "cancel"]
    messages: Optional[List[dict]] = Field(default=None, description="用户消息列表, type=user_message 时必填")


class WsToolResultPayload(BaseModel):
    """Java → Python: 远程工具执行结果"""
    type: Literal["tool_result"] = "tool_result"
    tool_call_id: str
    content: str = ""


# ──── Python → Java ────

class WsTokenPayload(BaseModel):
    """Python → Java: LLM token"""
    type: Literal["token"] = "token"
    content: str


class WsToolCallPayload(BaseModel):
    """Python → Java: 请求执行远程工具"""
    type: Literal["tool_call"] = "tool_call"
    tool_call_id: str
    tool_name: str
    args: dict[str, Any] = {}


class WsToolStartPayload(BaseModel):
    """Python → Java: 工具开始执行 (供前端 UI 展示进度)"""
    type: Literal["tool_start"] = "tool_start"
    tool_call_id: str
    tool_name: str


class WsToolEndPayload(BaseModel):
    """Python → Java: 工具执行完成"""
    type: Literal["tool_end"] = "tool_end"
    tool_call_id: str


class WsErrorPayload(BaseModel):
    """Python → Java: 错误信息"""
    type: Literal["error"] = "error"
    message: str


class WsFinishPayload(BaseModel):
    """Python → Java: Agent 完成"""
    type: Literal["agent_finish"] = "agent_finish"
