"""聊天接口 — 流式 SSE 传输
职责: Prompt编排、LLM调用，不涉及业务数据
"""
from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from app.schemas.chat import ChatCompletionRequest
from app.core.llm_client import stream_chat
from app.utils.logger import get_logger

logger = get_logger(__name__)
router = APIRouter()


@router.post("/chat/completions")
async def chat_completions(request: ChatCompletionRequest):
    """
    流式聊天接口，兼容 OpenAI Chat Completions 格式

    请求: {"messages": [...], "stream": true}
    响应: text/event-stream (SSE), 逐 token 返回
    """
    logger.info("收到聊天请求, 消息数: %d", len(request.messages))

    messages_dict = [m.model_dump() for m in request.messages]

    async def generate():
        """SSE 事件生成器，逐 token 输出"""
        try:
            async for content_chunk in stream_chat(messages_dict):
                # 标准 SSE 格式: data: <内容>\n\n
                yield f"data: {content_chunk}\n\n"
            yield "data: [DONE]\n\n"
        except Exception as e:
            logger.error("LLM 流式调用异常: %s", str(e))
            yield f"data: [ERROR] {str(e)}\n\n"

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )
