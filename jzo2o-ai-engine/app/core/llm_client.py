"""OpenAI 兼容 LLM 客户端 — 封装流式调用"""
from typing import AsyncIterator, Dict, List
from openai import AsyncOpenAI
from .config import settings

_client = AsyncOpenAI(
    base_url=settings.llm_api_base,
    api_key=settings.llm_api_key,
)


async def stream_chat(messages: List[Dict[str, str]]) -> AsyncIterator[str]:
    """
    向 LLM 发送消息并逐 token 异步迭代返回

    Args:
        messages: OpenAI 格式的消息列表 [{"role": "user", "content": "..."}]

    Yields:
        纯文本 token 字符串 (不含 SSE 格式)
    """
    response = await _client.chat.completions.create(
        model=settings.llm_model,
        messages=messages,
        stream=True,
    )
    async for chunk in response:
        delta = chunk.choices[0].delta
        if delta.content:
            yield delta.content
