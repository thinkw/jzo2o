"""WebSocket 消息模型 — Java ↔ Python 通信协议"""
from typing import List, Literal, Optional
from pydantic import BaseModel, Field


class WsClientMessage(BaseModel):
    """Java → Python 的 WebSocket 消息"""
    type: Literal["user_message", "cancel"]
    messages: Optional[List[dict]] = Field(default=None, description="用户消息列表, type=user_message 时必填")


class WsTokenPayload(BaseModel):
    """Python → Java: LLM token"""
    type: Literal["token"] = "token"
    content: str


class WsErrorPayload(BaseModel):
    """Python → Java: 错误信息"""
    type: Literal["error"] = "error"
    message: str


class WsFinishPayload(BaseModel):
    """Python → Java: Agent 完成"""
    type: Literal["agent_finish"] = "agent_finish"
