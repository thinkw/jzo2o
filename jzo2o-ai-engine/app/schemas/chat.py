"""请求/响应模型 — 兼容 OpenAI Chat Completions 格式"""
from typing import List, Optional
from pydantic import BaseModel, Field


class ChatMessage(BaseModel):
    """单条聊天消息"""
    role: str = Field(..., description="角色: user / assistant / system")
    content: str = Field(..., description="消息内容")


class ChatCompletionRequest(BaseModel):
    """
    聊天完成请求，兼容 OpenAI 格式
    未来扩展: langgraph_config, agent_tools 等字段
    """
    messages: List[ChatMessage] = Field(..., description="对话消息列表")
    stream: bool = Field(default=True, description="是否流式返回")
    temperature: Optional[float] = Field(default=None, description="采样温度")
    max_tokens: Optional[int] = Field(default=None, description="最大生成 token 数")


class DeltaContent(BaseModel):
    """流式增量内容"""
    content: Optional[str] = None
    role: Optional[str] = None


class ChoiceDelta(BaseModel):
    """流式 choice 中的 delta"""
    delta: DeltaContent
    index: int = 0


class ChatCompletionChunk(BaseModel):
    """流式响应的单个 chunk (兼容 OpenAI 格式)"""
    id: str = ""
    object: str = "chat.completion.chunk"
    created: int = 0
    model: str = ""
    choices: List[ChoiceDelta] = []
