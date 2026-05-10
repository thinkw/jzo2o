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
    响应: text/plain, 每行一个 token, [DONE] 结束
    """
    logger.info("收到聊天请求, 消息数: %d", len(request.messages))

    messages_dict = [m.model_dump() for m in request.messages]

    async def generate():
        """逐 token 输出，换行分隔"""
        try:
            async for content_chunk in stream_chat(messages_dict):
                # 纯文本 token + 换行，Java端负责SSE包装
                yield content_chunk + "\n"
            yield "[DONE]\n"
        except Exception as e:
            logger.error("LLM 流式调用异常: %s", str(e))
            yield f"[ERROR] {str(e)}\n"

    return StreamingResponse(
        generate(),
        media_type="text/plain",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )
