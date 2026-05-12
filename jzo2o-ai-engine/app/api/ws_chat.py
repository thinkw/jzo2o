"""WebSocket 聊天端点 — 双向通信
职责: 接收 user_message → 调用 stream_chat → 逐 token 回传,
      支持中途取消(cancel帧)和断连自动清理
"""
import asyncio
import json
from typing import Optional
from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from app.core.llm_client import stream_chat
from app.utils.logger import get_logger

logger = get_logger(__name__)
router = APIRouter()


@router.websocket("/ws/chat/{session_id}")
async def ws_chat(websocket: WebSocket, session_id: str):
    """WebSocket 聊天端点, 一个连接对应一个 session"""
    await websocket.accept()
    logger.info("WebSocket 已连接, session_id=%s", session_id)

    cancel_event = asyncio.Event()
    streaming_task: Optional[asyncio.Task] = None

    try:
        while True:
            raw = await websocket.receive_text()
            msg = json.loads(raw)
            msg_type = msg.get("type")

            if msg_type == "user_message":
                # 收到新消息, 先取消当前正在跑的流
                if streaming_task and not streaming_task.done():
                    cancel_event.set()
                    streaming_task.cancel()
                    try:
                        await streaming_task
                    except asyncio.CancelledError:
                        pass

                cancel_event.clear()
                messages = msg.get("messages", [])

                async def stream_and_send():
                    """异步流式发送 token — 按换行缓冲, 与 HTTP 行解码器行为一致"""
                    try:
                        buffer = ""
                        async for token in stream_chat(messages):
                            if cancel_event.is_set():
                                logger.info("流已被取消, session_id=%s", session_id)
                                break
                            if "\n" in token:
                                # token 中包含换行: 按 \n 切分, 逐行发送
                                parts = token.split("\n")
                                for i, part in enumerate(parts):
                                    if i > 0:
                                        # 发送已完成的行 (可为空串, 表示段落分隔)
                                        payload = json.dumps(
                                            {"type": "token", "content": buffer},
                                            ensure_ascii=False,
                                        )
                                        await websocket.send_text(payload)
                                        buffer = part
                                    else:
                                        buffer += part
                            else:
                                buffer += token
                        # 发送最后一行
                        if buffer:
                            payload = json.dumps(
                                {"type": "token", "content": buffer}, ensure_ascii=False
                            )
                            await websocket.send_text(payload)

                        if not cancel_event.is_set():
                            await websocket.send_text(
                                json.dumps({"type": "agent_finish"}, ensure_ascii=False)
                            )
                    except Exception as e:
                        logger.error("LLM 调用异常, session_id=%s: %s", session_id, str(e))
                        await websocket.send_text(
                            json.dumps({"type": "error", "message": str(e)}, ensure_ascii=False)
                        )

                streaming_task = asyncio.create_task(stream_and_send())

            elif msg_type == "cancel":
                cancel_event.set()
                if streaming_task and not streaming_task.done():
                    streaming_task.cancel()
                    try:
                        await streaming_task
                    except asyncio.CancelledError:
                        pass
                logger.info("收到取消请求, session_id=%s", session_id)

    except WebSocketDisconnect:
        logger.info("WebSocket 断开, session_id=%s", session_id)
        cancel_event.set()
        if streaming_task and not streaming_task.done():
            streaming_task.cancel()
    except Exception as e:
        logger.error("WebSocket 异常, session_id=%s: %s", session_id, str(e))
    finally:
        # 确保资源清理
        if streaming_task and not streaming_task.done():
            streaming_task.cancel()
