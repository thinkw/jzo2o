"""LLM 客户端 — 原始 OpenAI SDK (HTTP 降级) + ChatDeepSeek (Agent 使用)

DeepSeek 思考模式兼容:
  - ChatDeepSeek._create_chat_result  ✅ 入站: 提取 reasoning_content → additional_kwargs
  - _convert_message_to_dict         ❌ 出站: 不序列化 additional_kwargs.reasoning_content
  这里 monkey-patch 出站方向, 确保工具调用往返不丢失 reasoning_content。
"""
from typing import AsyncIterator, Dict, List, Mapping, Any
from openai import AsyncOpenAI
from langchain_core.messages import AIMessage, BaseMessage
from langchain_deepseek import ChatDeepSeek
from langchain_openai.chat_models import base as base_module
from .config import settings

# ──── Monkey-patch: _convert_message_to_dict 写回 reasoning_content ────
# ChatDeepSeek 已在入站方向 (响应解析) 正确处理 reasoning_content,
# 但出站方向 (AIMessage → API 请求体) 仍使用 BaseChatOpenAI 的
# _convert_message_to_dict, 该函数不包含 reasoning_content。
# 参考: DeepSeek API 文档要求工具调用后的后续请求必须传回 reasoning_content

_original_message_to_dict = base_module._convert_message_to_dict


def _patched_message_to_dict(message, api="chat/completions"):
    result = _original_message_to_dict(message, api=api)
    if isinstance(message, AIMessage) and "reasoning_content" in message.additional_kwargs:
        result["reasoning_content"] = message.additional_kwargs["reasoning_content"]
    return result


base_module._convert_message_to_dict = _patched_message_to_dict

# ──── 向后兼容: 原始 OpenAI SDK 客户端 (HTTP 降级路径使用) ────

_client = AsyncOpenAI(
    base_url=settings.llm_api_base,
    api_key=settings.llm_api_key,
)


async def stream_chat(messages: List[Dict[str, str]]) -> AsyncIterator[str]:
    """向 LLM 发送消息并逐 token 异步迭代返回 (原始 OpenAI SDK, HTTP 降级路径)

    Args:
        messages: OpenAI 格式的消息列表 [{"role": "user", "content": "..."}]

    Yields:
        纯文本 token 字符串
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


# ──── ChatDeepSeek 工厂 (Agent 使用) ────

def create_llm(**kwargs) -> ChatDeepSeek:
    """创建 ChatDeepSeek 实例, 配置为 DeepSeek API 调用

    ChatDeepSeek 入站方向原生提取 reasoning_content 到 additional_kwargs;
    出站方向通过上方的 monkey-patch 补齐。

    Args:
        **kwargs: 可覆盖 model/api_key/api_base/temperature/max_tokens

    Returns:
        已配置 streaming=True 的 ChatDeepSeek 实例
    """
    return ChatDeepSeek(
        model=kwargs.get("model", settings.llm_model),
        api_key=kwargs.get("api_key", settings.llm_api_key),
        api_base=kwargs.get("api_base", settings.llm_api_base.rstrip("/") + "/v1"),
        temperature=kwargs.get("temperature", settings.llm_temperature),
        max_tokens=kwargs.get("max_tokens", settings.llm_max_tokens),
        streaming=True,
    )
