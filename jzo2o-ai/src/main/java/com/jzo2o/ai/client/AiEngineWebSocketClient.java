package com.jzo2o.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzo2o.ai.properties.AiEngineProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AI引擎 WebSocket 客户端 — 管理 per-session WebSocket 连接,
 * 将 Python 引擎的结构化 JSON 帧转换为 SseEmitter 事件推向前端
 */
@Slf4j
@Component
public class AiEngineWebSocketClient {

    @Resource
    private ReactorNettyWebSocketClient wsClient;

    @Resource
    private AiEngineProperties aiEngineProperties;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ToolExecutor toolExecutor;

    /** sessionId → WebSocket 订阅, 用于取消旧连接 */
    private final ConcurrentHashMap<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();

    /** sessionId → WebSocketSession 引用, 用于发送 cancel 帧 */
    private final ConcurrentHashMap<String, WebSocketSession> activeWsSessions = new ConcurrentHashMap<>();

    /**
     * 为指定 session 建立 WebSocket 连接, 流式消费 AI 回复,
     * 通过 SseEmitter 推送给调用方 (最终推向前端)
     *
     * @param sessionId          会话ID
     * @param messages           消息列表 (OpenAI 格式)
     * @param emitter            SSE 发射器
     * @param tokenAccumulator   token 累积回调 (用于持久化), 每个 token 调用一次
     * @param onCompleteCallback 流正常完成时的回调 (持久化完整回复)
     */
    public void connectAndStream(String sessionId,
                                 List<Map<String, String>> messages,
                                 SseEmitter emitter,
                                 Consumer<String> tokenAccumulator,
                                 Runnable onCompleteCallback) {
        // 1. 如果该 session 已有连接, 先取消旧连接
        cancelExistingSession(sessionId);

        // 2. 构建 WebSocket URI
        String wsUri = aiEngineProperties.getWsUrl() + "/ws/chat/" + sessionId;
        log.info("建立 WebSocket 连接, sessionId={}, uri={}", sessionId, wsUri);

        // 3. 构建 user_message JSON 帧
        String userMessageJson;
        try {
            userMessageJson = objectMapper.writeValueAsString(
                    Map.of("type", "user_message", "messages", messages));
        } catch (Exception e) {
            log.error("序列化 user_message 失败, sessionId={}", sessionId, e);
            emitter.completeWithError(e);
            return;
        }

        // 4. 建立 WebSocket 连接并订阅
        Disposable disposable = wsClient.execute(URI.create(wsUri), session -> {
            // 保存 session 引用, 以便发 cancel 帧
            activeWsSessions.put(sessionId, session);

            // 发送 user_message
            Mono<Void> send = session.send(
                    Mono.just(session.textMessage(userMessageJson)));

            // 接收回复帧, 逐帧路由到 SseEmitter
            Mono<Void> receive = session.receive()
                    .doOnNext(wsMessage -> {
                        String payload = wsMessage.getPayloadAsText();
                        handleWsMessage(payload, emitter, sessionId,
                                tokenAccumulator, onCompleteCallback);
                    })
                    .then();

            return send.then(receive);
        }).subscribe(
                null, // onNext 在 handler 内部处理
                error -> {
                    log.error("WebSocket 连接异常, sessionId={}: {}", sessionId, error.getMessage());
                    try {
                        emitter.completeWithError(error);
                    } catch (Exception ignored) {
                        // emitter 可能已经完成
                    }
                    cleanup(sessionId);
                },
                () -> {
                    log.info("WebSocket 连接关闭, sessionId={}", sessionId);
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                        // emitter 可能已经完成
                    }
                    cleanup(sessionId);
                }
        );

        activeSubscriptions.put(sessionId, disposable);
    }

    /**
     * 取消指定 session 的 WebSocket 连接
     */
    public void cancelSession(String sessionId) {
        WebSocketSession wsSession = activeWsSessions.get(sessionId);
        if (wsSession != null && wsSession.isOpen()) {
            try {
                String cancelJson = objectMapper.writeValueAsString(Map.of("type", "cancel"));
                wsSession.send(Mono.just(wsSession.textMessage(cancelJson)))
                        .subscribe(null, null, () -> wsSession.close().subscribe());
            } catch (Exception e) {
                log.warn("发送 cancel 帧失败, sessionId={}: {}", sessionId, e.getMessage());
                wsSession.close().subscribe();
            }
        }
        cancelExistingSession(sessionId);
    }

    /**
     * 解析 Python 引擎发来的 JSON 帧, 按类型路由到 SseEmitter
     */
    private void handleWsMessage(String payload, SseEmitter emitter, String sessionId,
                                  Consumer<String> tokenAccumulator,
                                  Runnable onCompleteCallback) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = objectMapper.readValue(payload, Map.class);
            String type = (String) frame.get("type");

            switch (type) {
                case "token":
                    // LLM token: 透传给前端, 同时累积到 responseBuilder
                    String content = (String) frame.get("content");
                    if (content != null) {
                        emitter.send(SseEmitter.event().data(content));
                        if (tokenAccumulator != null) {
                            tokenAccumulator.accept(content);
                        }
                    }
                    break;
                case "agent_finish":
                    // Agent 正常完成: 先持久化, 再关闭 SSE
                    if (onCompleteCallback != null) {
                        onCompleteCallback.run();
                    }
                    emitter.complete();
                    cleanup(sessionId);
                    break;
                case "tool_call":
                    // Python Agent 请求执行远程工具 → 调用业务服务 → WS 回传 tool_result
                    handleToolCall(frame, sessionId);
                    break;
                case "tool_start":
                    // 透传工具开始事件给前端 (展示进度)
                    emitter.send(SseEmitter.event().name("tool_start").data(payload));
                    break;
                case "tool_end":
                    // 透传工具完成事件给前端
                    emitter.send(SseEmitter.event().name("tool_end").data(payload));
                    break;
                case "error":
                    // Python 引擎报错
                    String message = (String) frame.get("message");
                    log.error("Python 引擎返回错误, sessionId={}: {}", sessionId, message);
                    emitter.completeWithError(new RuntimeException(message));
                    cleanup(sessionId);
                    break;
                default:
                    log.warn("未知 WebSocket 消息类型, sessionId={}, type={}", sessionId, type);
            }
        } catch (IOException e) {
            log.error("SseEmitter.send 失败, sessionId={}: {}", sessionId, e.getMessage());
            emitter.completeWithError(e);
            cleanup(sessionId);
        } catch (Exception e) {
            log.error("解析 WebSocket 消息失败, sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 处理 tool_call 帧 — 异步执行远程工具, 结果通过 WebSocket 回传给 Python
     */
    private void handleToolCall(Map<String, Object> frame, String sessionId) {
        String toolName = (String) frame.get("tool_name");
        String toolCallId = (String) frame.get("tool_call_id");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) frame.getOrDefault("args", Map.of());

        CompletableFuture.supplyAsync(() -> toolExecutor.execute(toolName, toolCallId, args))
                .thenAccept(result -> sendToolResult(sessionId, toolCallId, result))
                .exceptionally(e -> {
                    log.error("远程工具执行失败, sessionId={}, toolName={}: {}",
                            sessionId, toolName, e.getMessage());
                    // 失败时也回传 tool_result, 避免 Python 侧干等超时
                    sendToolResult(sessionId, toolCallId,
                            "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
                    return null;
                });
    }

    /** 通过 WebSocket session 向 Python 发送 tool_result 帧 */
    private void sendToolResult(String sessionId, String toolCallId, String content) {
        WebSocketSession wsSession = activeWsSessions.get(sessionId);
        if (wsSession != null && wsSession.isOpen()) {
            try {
                String resultJson = objectMapper.writeValueAsString(
                        Map.of("type", "tool_result",
                                "tool_call_id", toolCallId,
                                "content", content));
                wsSession.send(Mono.just(wsSession.textMessage(resultJson)))
                        .subscribe(
                                null,
                                e -> log.error("发送 tool_result 失败, sessionId={}: {}",
                                        sessionId, e.getMessage()));
            } catch (Exception e) {
                log.error("序列化 tool_result 失败, sessionId={}: {}",
                        sessionId, e.getMessage());
            }
        } else {
            log.warn("WebSocket 会话已关闭, 无法发送 tool_result, sessionId={}", sessionId);
        }
    }

    /**
     * 取消并清理指定 session 的旧连接
     */
    private void cancelExistingSession(String sessionId) {
        Disposable existing = activeSubscriptions.remove(sessionId);
        if (existing != null && !existing.isDisposed()) {
            existing.dispose();
        }
        WebSocketSession wsSession = activeWsSessions.remove(sessionId);
        if (wsSession != null && wsSession.isOpen()) {
            wsSession.close().subscribe(
                    null,
                    e -> log.warn("关闭旧 WebSocket 连接失败, sessionId={}: {}", sessionId, e.getMessage())
            );
        }
    }

    /**
     * 清理指定 session 的注册信息
     */
    private void cleanup(String sessionId) {
        activeSubscriptions.remove(sessionId);
        activeWsSessions.remove(sessionId);
    }
}
