"""WebSocket 聊天端点 — LangGraph Agent 集成
架构: 三任务并发模型
  reader_task  — 循环 receive_text(), 按 type 路由
  sender_task  — 循环从 send_queue 取 JSON 帧, send_text()
  agent_task   — 每次 user_message 新建, 运行 agent.astream() 流式输出
"""
import asyncio
import json
from typing import Optional
from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from app.agent import build_graph, ToolExecutionContext
from app.utils.logger import get_logger

logger = get_logger(__name__)
router = APIRouter()


def _dicts_to_messages(messages_list: list[dict]) -> list:
    """将原始 dict 消息列表转换为 LangChain BaseMessage 对象"""
    from langchain_core.messages import SystemMessage, HumanMessage, AIMessage

    result = []
    for m in messages_list:
        role = m.get("role", "user")
        content = m.get("content", "")
        if role == "system":
            result.append(SystemMessage(content=content))
        elif role == "assistant":
            result.append(AIMessage(content=content))
        else:
            result.append(HumanMessage(content=content))
    return result


@router.websocket("/ws/chat/{session_id}")
async def ws_chat(websocket: WebSocket, session_id: str):
    """WebSocket 聊天端点, 一个连接对应一个 session"""
    await websocket.accept()
    logger.info("WebSocket 已连接, session_id=%s", session_id)

    cancel_event = asyncio.Event()
    incoming_queue: asyncio.Queue = asyncio.Queue()
    send_queue: asyncio.Queue = asyncio.Queue()
    agent_task: Optional[asyncio.Task] = None

    # 创建工具执行上下文 (仅远程工具走此通道)
    tool_ctx = ToolExecutionContext(cancel_event)
    tool_ctx.set_send_callback(lambda msg: send_queue.put(msg))

    # =========================================================================
    # reader_task — 接收 WebSocket 消息, 按 type 路由
    # =========================================================================
    async def reader():
        try:
            while True:
                raw = await websocket.receive_text()
                msg = json.loads(raw)
                msg_type = msg.get("type")

                if msg_type == "tool_result":
                    # Java 返回的工具执行结果 → 注入 tool_ctx
                    await tool_ctx.on_tool_result(msg)
                elif msg_type == "cancel":
                    # 用户取消 → 中断 Agent
                    cancel_event.set()
                    tool_ctx.cancel_all()
                    logger.info("收到取消请求, session_id=%s", session_id)
                elif msg_type == "user_message":
                    # 用户新消息 → 入队, 触发 Agent 运行
                    await incoming_queue.put(msg)
                else:
                    logger.warning("未知 WS 消息类型: %s", msg_type)
        except WebSocketDisconnect:
            await incoming_queue.put({"type": "__disconnect__"})

    # =========================================================================
    # sender_task — 从 send_queue 取帧发送到 WebSocket
    # =========================================================================
    async def sender():
        try:
            while True:
                payload = await send_queue.get()
                if payload is None:  # 退出哨兵
                    break
                await websocket.send_text(
                    json.dumps(payload, ensure_ascii=False)
                )
        except WebSocketDisconnect:
            pass

    # =========================================================================
    # run_agent — 创建 Agent 任务, 流式输出 token
    # =========================================================================
    async def run_agent(messages_list: list[dict]):
        """运行 LangGraph Agent, 将 token 和 tool 事件推入 send_queue"""
        try:
            agent = build_graph(tool_ctx)
            langchain_messages = _dicts_to_messages(messages_list)
            config = {"configurable": {"thread_id": session_id}}

            buffer = ""
            async for chunk, _metadata in agent.astream(
                {"messages": langchain_messages},
                config=config,
                stream_mode="messages",
            ):
                if cancel_event.is_set():
                    logger.info("Agent 被取消, session_id=%s", session_id)
                    break

                # 仅处理 LLM 产出的 token 块
                from langchain_core.messages import AIMessageChunk
                if isinstance(chunk, AIMessageChunk) and chunk.content:
                    token = chunk.content
                    # 按换行缓冲, 与旧版 HTTP 行解码器行为一致
                    if "\n" in token:
                        parts = token.split("\n")
                        for i, part in enumerate(parts):
                            if i > 0:
                                # 发送已完成的行
                                await send_queue.put({
                                    "type": "token",
                                    "content": buffer,
                                })
                                buffer = part
                            else:
                                buffer += part
                    else:
                        buffer += token
                # tool_call / tool_result 由 graph 节点内部的 tool_ctx 处理,
                # 这里不需要额外干预

            # 发送最后一行
            if buffer and not cancel_event.is_set():
                await send_queue.put({
                    "type": "token",
                    "content": buffer,
                })

            if not cancel_event.is_set():
                await send_queue.put({"type": "agent_finish"})

        except asyncio.CancelledError:
            logger.info("Agent 任务被取消, session_id=%s", session_id)
        except Exception as e:
            logger.error("Agent 运行异常, session_id=%s: %s", session_id, str(e))
            try:
                await send_queue.put({"type": "error", "message": str(e)})
            except Exception:
                pass

    # =========================================================================
    # 主循环 — 接收消息 → 启动/取消 Agent
    # =========================================================================
    reader_task = asyncio.create_task(reader())
    sender_task = asyncio.create_task(sender())

    try:
        while True:
            msg = await incoming_queue.get()

            if msg.get("type") == "__disconnect__":
                break

            if msg.get("type") == "user_message":
                # 取消旧 Agent
                if agent_task and not agent_task.done():
                    cancel_event.set()
                    tool_ctx.cancel_all()
                    agent_task.cancel()
                    try:
                        await agent_task
                    except (asyncio.CancelledError, Exception):
                        pass

                cancel_event.clear()
                messages = msg.get("messages", [])
                agent_task = asyncio.create_task(run_agent(messages))

    except WebSocketDisconnect:
        logger.info("WebSocket 断开, session_id=%s", session_id)
    except Exception as e:
        logger.error("WebSocket 异常, session_id=%s: %s", session_id, str(e))
    finally:
        # 清理: 取消 Agent → 取消 reader → 关闭 sender
        cancel_event.set()
        tool_ctx.cancel_all()
        if agent_task and not agent_task.done():
            agent_task.cancel()
        reader_task.cancel()
        await send_queue.put(None)  # 通知 sender 退出
        sender_task.cancel()
