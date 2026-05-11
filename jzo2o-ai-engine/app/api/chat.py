"""聊天接口 — 流式传输
职责: Prompt编排、LLM调用，不涉及业务数据
"""
from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from app.schemas.chat import ChatCompletionRequest
from app.core.llm_client import stream_chat
from app.utils.logger import get_logger

logger = get_logger(__name__)
router = APIRouter()


@router.post("/completions")
async def chat_completions(request: ChatCompletionRequest):
    """
    流式聊天接口

    请求: {"messages": [...], "stream": true}
    响应: text/plain, 逐 token 直接输出 (原样, 不加分隔符)
          Java 端用 WebClient.bodyToFlux 接收后直接包 SSE 转发前端
    """
    logger.info("收到聊天请求, 消息数: %d", len(request.messages))

    messages_dict = [m.model_dump() for m in request.messages]

    async def generate():
        """逐 token 原样输出, 由 LLM 控制内容格式"""
        try:
            async for content_chunk in stream_chat(messages_dict):
                yield content_chunk
        except Exception as e:
            logger.error("LLM 流式调用异常: %s", str(e))
            yield f"[ERROR] {str(e)}"

    return StreamingResponse(
        generate(),
        media_type="text/plain",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )
