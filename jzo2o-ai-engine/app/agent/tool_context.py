"""ToolExecutionContext — 远程工具执行的异步桥接
Agent 图的 tool_executor 节点通过此类将 tool_call 发送给 Java, 等待 Java 回传 tool_result
"""
import asyncio
import logging
from typing import Any, Callable, Awaitable

logger = logging.getLogger(__name__)

# 远程工具默认超时(秒)
_TOOL_TIMEOUT = 120


class ToolExecutionContext:
    """管理 Agent ↔ Java 之间的远程工具调用生命周期

    一个 ToolExecutionContext 实例对应一个 WebSocket session。
    Agent 图通过 execute() 发送 tool_call 到 Java 并异步等待结果;
    WebSocket reader 通过 on_tool_result() 注入 Java 返回的结果。
    """

    def __init__(self, cancel_event: asyncio.Event):
        self._pending: dict[str, asyncio.Future[str]] = {}
        self._cancel_event = cancel_event
        self._send_callback: Callable[[dict], Awaitable[None]] | None = None

    def set_send_callback(self, callback: Callable[[dict], Awaitable[None]]) -> None:
        """设置异步发送回调, 用于将 JSON 帧发送到 WebSocket"""
        self._send_callback = callback

    async def execute(self, tool_call_id: str, tool_name: str, args: dict[str, Any]) -> str:
        """发送 tool_call 到 Java 并等待 tool_result 返回

        Raises:
            RuntimeError: send_callback 未设置
            TimeoutError: Java 执行超时
            asyncio.CancelledError: 会话被取消
        """
        if self._send_callback is None:
            raise RuntimeError("send_callback 未设置, 请先调用 set_send_callback()")

        loop = asyncio.get_running_loop()
        future: asyncio.Future[str] = loop.create_future()
        self._pending[tool_call_id] = future

        # 发送 tool_call 帧到 Java
        await self._send_callback({
            "type": "tool_call",
            "tool_call_id": tool_call_id,
            "tool_name": tool_name,
            "args": args,
        })
        # 发送 tool_start 帧, 供前端展示进度
        await self._send_callback({
            "type": "tool_start",
            "tool_call_id": tool_call_id,
            "tool_name": tool_name,
        })

        try:
            return await asyncio.wait_for(future, timeout=_TOOL_TIMEOUT)
        except asyncio.TimeoutError:
            self._pending.pop(tool_call_id, None)
            logger.warning("远程工具 %s (id=%s) 超时 (%ds)", tool_name, tool_call_id, _TOOL_TIMEOUT)
            return f"错误: 工具 {tool_name} 执行超时"
        except asyncio.CancelledError:
            self._pending.pop(tool_call_id, None)
            logger.info("远程工具 %s (id=%s) 被取消", tool_name, tool_call_id)
            raise

    async def on_tool_result(self, msg: dict) -> None:
        """WebSocket reader 调用, 注入 Java 返回的工具执行结果并发送 tool_end"""
        tool_call_id = msg.get("tool_call_id", "")
        content = msg.get("content", "")
        future = self._pending.pop(tool_call_id, None)
        if future is not None and not future.done():
            future.set_result(content)
            logger.debug("收到 tool_result: %s", tool_call_id)
            # 通知前端工具执行完成
            if self._send_callback:
                await self._send_callback({
                    "type": "tool_end",
                    "tool_call_id": tool_call_id,
                })

    def cancel_all(self) -> None:
        """取消所有等待中的远程工具调用 (会话断开/用户取消时调用)"""
        for tool_call_id, future in list(self._pending.items()):
            if not future.done():
                future.cancel()
        self._pending.clear()
