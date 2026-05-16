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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * AI引擎 WebSocket 客户端 — 管理 per-session WebSocket 连接,
 * 支持流模式 (SseEmitter → 前端) 和收集模式 (CompletableFuture → 代码调用方)。
 *
 * <p>连接管理特性:
 * <ul>
 *   <li>按需建连: 仅在用户发言时建立 WebSocket</li>
 *   <li>空闲超时: 超过 idleTimeoutSeconds 无活动的连接自动关闭释放资源</li>
 *   <li>收集模式自动重连: 连接异常断开时指数退避重试 (最多 maxReconnectAttempts 次)</li>
 *   <li>优雅关闭: {@code @PreDestroy} 清理所有活跃连接</li>
 * </ul>
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

    /** sessionId → 会话上下文 (唯一会话状态存储, 替代原来的多个 Map) */
    private final ConcurrentHashMap<String, SessionContext> sessions = new ConcurrentHashMap<>();

    /** 单线程调度器: 空闲检测 + 重连延迟执行 */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-session-mgr");
                t.setDaemon(true);
                return t;
            });

    // ==================== 连接状态 ====================

    private enum ConnectionState {
        /** 正在建立 WebSocket 连接 */
        CONNECTING,
        /** 连接正常, 通信中 */
        ACTIVE,
        /** 连接正常但暂无活动 (与 ACTIVE 区分, 用于空闲检测) */
        IDLE,
        /** 已断开 */
        DISCONNECTED
    }

    // ==================== per-session 聚合上下文 ====================

    /**
     * 每个 session 的所有状态聚合在一个对象中, 替代原来分散的 3 个 ConcurrentHashMap。
     */
    private static class SessionContext {
        final String sessionId;
        /** 原始消息列表, 重连时用于重发 */
        final List<Map<String, String>> messages;

        // 连接
        volatile WebSocketSession wsSession;
        volatile Disposable disposable;
        volatile ConnectionState state = ConnectionState.DISCONNECTED;
        volatile long lastActivityTime = System.currentTimeMillis();

        // 重连
        volatile int reconnectAttempts = 0;
        volatile ScheduledFuture<?> reconnectFuture;

        // 流模式 (SseEmitter → 前端)
        volatile SseEmitter emitter;
        volatile Consumer<String> tokenAccumulator;
        volatile Runnable onCompleteCallback;

        // 收集模式 (CompletableFuture → 代码调用方)
        volatile CompletableFuture<String> resultFuture;
        final StringBuffer collectBuffer = new StringBuffer();

        SessionContext(String sessionId, List<Map<String, String>> messages) {
            this.sessionId = sessionId;
            this.messages = messages;
        }
    }

    // ==================== 初始化 & 清理 ====================

    @PostConstruct
    public void init() {
        // 每 60 秒扫描一次空闲会话
        scheduler.scheduleWithFixedDelay(this::checkIdleSessions, 60, 60, TimeUnit.SECONDS);
        log.info("WebSocket 会话管理器启动, 空闲超时={}s", aiEngineProperties.getIdleTimeoutSeconds());
    }

    @PreDestroy
    public void shutdown() {
        log.info("WebSocket 客户端关闭, 清理 {} 个会话", sessions.size());
        for (String sessionId : sessions.keySet()) {
            cancelExistingSession(sessionId);
        }
        scheduler.shutdownNow();
    }

    // ==================== 公开 API ====================

    /**
     * 流模式 — 建立 WebSocket, 通过 SseEmitter 将 token 推向前端。
     * 连接断开时通过 SSE error 事件通知前端 (由前端决定是否重试)。
     *
     * @param sessionId         会话ID
     * @param messages          消息列表 (OpenAI 格式)
     * @param emitter           SSE 发射器
     * @param tokenAccumulator  每个 token 的回调 (用于拼装完整回复)
     * @param onCompleteCallback Agent 正常完成时的回调 (用于持久化)
     */
    public void connectAndStream(String sessionId,
                                 List<Map<String, String>> messages,
                                 SseEmitter emitter,
                                 Consumer<String> tokenAccumulator,
                                 Runnable onCompleteCallback) {
        cancelExistingSession(sessionId);

        SessionContext ctx = new SessionContext(sessionId, messages);
        ctx.emitter = emitter;
        ctx.tokenAccumulator = tokenAccumulator;
        ctx.onCompleteCallback = onCompleteCallback;
        sessions.put(sessionId, ctx);

        // 注册回调: 前端断开 SSE 时自动取消 WebSocket
        // (通过 cancelSession → 先发 cancel 帧给 Python, 再关闭连接)
        emitter.onCompletion(() -> {
            log.info("SSE 完成, 取消 WebSocket, sessionId={}", sessionId);
            cancelSession(sessionId);
        });
        emitter.onError(e -> {
            log.info("SSE 错误, 取消 WebSocket, sessionId={}: {}", sessionId, e.getMessage());
            cancelSession(sessionId);
        });
        emitter.onTimeout(() -> {
            log.info("SSE 超時, 取消 WebSocket, sessionId={}", sessionId);
            cancelSession(sessionId);
        });

        doConnect(ctx, false);
    }

    /**
     * 收集模式 — 建立 WebSocket, 收集完整回复后通过 CompletableFuture 返回。
     * 支持自动重连 (指数退避), 适用于定时任务等无 HTTP 请求上下文的场景。
     *
     * @param sessionId 会话ID
     * @param messages  消息列表 (OpenAI 格式)
     * @return 包含完整 AI 回复文本的 CompletableFuture
     */
    public CompletableFuture<String> connectAndCollect(String sessionId,
                                                       List<Map<String, String>> messages) {
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        cancelExistingSession(sessionId);

        SessionContext ctx = new SessionContext(sessionId, messages);
        ctx.resultFuture = resultFuture;
        sessions.put(sessionId, ctx);

        doConnect(ctx, true);
        return resultFuture;
    }

    /**
     * 主动取消指定 session: 发送 cancel 帧 → 等待发送完成 → 清理资源。
     */
    public void cancelSession(String sessionId) {
        SessionContext ctx = sessions.get(sessionId);
        if (ctx == null) {
            // 可能已被 emitter 回调清理, 正常情况不告警
            log.debug("取消会话: 上下文已清理, sessionId={}", sessionId);
            return;
        }
        log.info("取消会话: 找到上下文, sessionId={}, state={}, wsOpen={}",
                sessionId, ctx.state,
                ctx.wsSession != null && ctx.wsSession.isOpen());
        cancelReconnect(ctx);

        WebSocketSession wsSession = ctx.wsSession;
        if (wsSession != null && wsSession.isOpen()) {
            try {
                String cancelJson = objectMapper.writeValueAsString(
                        Map.of("type", "cancel"));
                // 先发 cancel 帧, 发送完成或失败后再清理 — 避免 disposable.dispose() 中断发送
                wsSession.send(Mono.just(wsSession.textMessage(cancelJson)))
                        .doOnSuccess(v -> log.info("cancel 帧已发送, sessionId={}", sessionId))
                        .doOnError(e -> log.warn("发送 cancel 帧失败, sessionId={}: {}",
                                sessionId, e.getMessage()))
                        .subscribe(
                                v -> {},
                                e -> {
                                    log.warn("cancel 帧管道异常, sessionId={}: {}", sessionId, e.getMessage());
                                    cancelExistingSession(sessionId);
                                },
                                () -> {
                                    log.info("cancel 帧发送完成, 清理会话, sessionId={}", sessionId);
                                    cancelExistingSession(sessionId);
                                }
                        );
                return; // 清理由以上回调完成, 不在此处同步调用
            } catch (Exception e) {
                log.warn("序列化 cancel 帧失败, sessionId={}: {}", sessionId, e.getMessage());
                wsSession.close().subscribe();
            }
        } else {
            log.info("WebSocket 已关闭, 直接清理, sessionId={}", sessionId);
        }
        cancelExistingSession(sessionId);
    }

    // ==================== 连接建立 (流/收集共用) ====================

    /**
     * 执行一次 WebSocket 连接建立。
     *
     * @param ctx           会话上下文
     * @param reconnectable 是否启用自动重连 (收集模式=true, 流模式=false)
     */
    private void doConnect(SessionContext ctx, boolean reconnectable) {
        ctx.state = ConnectionState.CONNECTING;
        String wsUri = aiEngineProperties.getWsUrl() + "/ws/chat/" + ctx.sessionId;
        log.info("建立 WebSocket, sessionId={}, reconnectable={}, attempt={}",
                ctx.sessionId, reconnectable, ctx.reconnectAttempts);

        String userMessageJson;
        try {
            userMessageJson = objectMapper.writeValueAsString(
                    Map.of("type", "user_message", "messages", ctx.messages));
        } catch (Exception e) {
            log.error("序列化 user_message 失败, sessionId={}", ctx.sessionId, e);
            failContext(ctx, e);
            return;
        }

        Disposable disposable = wsClient.execute(URI.create(wsUri), session -> {
            ctx.wsSession = session;
            ctx.state = ConnectionState.ACTIVE;
            updateActivity(ctx);

            Mono<Void> send = session.send(
                    Mono.just(session.textMessage(userMessageJson)));

            Mono<Void> receive = session.receive()
                    .doOnNext(wsMessage -> {
                        updateActivity(ctx);
                        String payload = wsMessage.getPayloadAsText();
                        if (ctx.emitter != null) {
                            handleStreamFrame(payload, ctx);
                        } else {
                            handleCollectFrame(payload, ctx);
                        }
                    })
                    .then();

            return send.then(receive);
        }).subscribe(
                null,
                error -> {
                    log.error("WebSocket 连接异常, sessionId={}, attempt={}: {}",
                            ctx.sessionId, ctx.reconnectAttempts + 1, error.getMessage());
                    ctx.state = ConnectionState.DISCONNECTED;
                    onConnectionError(ctx, error, reconnectable);
                },
                () -> {
                    log.info("WebSocket 连接关闭, sessionId={}", ctx.sessionId);
                    ctx.state = ConnectionState.DISCONNECTED;
                    onConnectionComplete(ctx);
                }
        );

        ctx.disposable = disposable;
    }

    // ==================== 消息路由 (流模式) ====================

    private void handleStreamFrame(String payload, SessionContext ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = objectMapper.readValue(payload, Map.class);
            String type = (String) frame.get("type");

            switch (type) {
                case "token":
                    String content = (String) frame.get("content");
                    if (content != null) {
                        ctx.emitter.send(SseEmitter.event().data(content));
                        if (ctx.tokenAccumulator != null) {
                            ctx.tokenAccumulator.accept(content);
                        }
                    }
                    break;
                case "agent_finish":
                    if (ctx.onCompleteCallback != null) {
                        ctx.onCompleteCallback.run();
                    }
                    // emitter.complete() → onCompletion → cancelSession 统一清理
                    ctx.emitter.complete();
                    break;
                case "tool_call":
                    handleToolCall(frame, ctx.sessionId);
                    break;
                case "tool_start":
                    ctx.emitter.send(SseEmitter.event().name("tool_start").data(payload));
                    break;
                case "tool_end":
                    ctx.emitter.send(SseEmitter.event().name("tool_end").data(payload));
                    break;
                case "heartbeat":
                    // Agent 长时间无输出时 Python 发送 heartbeat 保活
                    // 流模式: 透传为 SSE 注释维持 SSE 长连接
                    ctx.emitter.send(SseEmitter.event().comment("hb"));
                    break;
                case "error":
                    String msg = (String) frame.get("message");
                    log.error("Python 引擎错误, sessionId={}: {}", ctx.sessionId, msg);
                    // emitter.completeWithError() → onError → cancelSession 统一清理
                    ctx.emitter.completeWithError(new RuntimeException(msg));
                    break;
                default:
                    log.warn("未知消息类型, sessionId={}, type={}", ctx.sessionId, type);
            }
        } catch (IOException e) {
            log.error("SseEmitter.send 失败, sessionId={}: {}", ctx.sessionId, e.getMessage());
            // emitter.completeWithError() → onError → cancelSession 统一清理
            ctx.emitter.completeWithError(e);
        } catch (Exception e) {
            log.error("解析 WebSocket 消息失败, sessionId={}: {}", ctx.sessionId, e.getMessage());
        }
    }

    // ==================== 消息路由 (收集模式) ====================

    private void handleCollectFrame(String payload, SessionContext ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = objectMapper.readValue(payload, Map.class);
            String type = (String) frame.get("type");

            switch (type) {
                case "token":
                    String content = (String) frame.get("content");
                    if (content != null) {
                        ctx.collectBuffer.append(content);
                    }
                    break;
                case "tool_call":
                    handleToolCall(frame, ctx.sessionId);
                    break;
                case "agent_finish":
                    if (!ctx.resultFuture.isDone()) {
                        ctx.resultFuture.complete(ctx.collectBuffer.toString());
                    }
                    cleanup(ctx.sessionId);
                    break;
                case "error":
                    String msg = (String) frame.get("message");
                    log.error("Python 引擎错误, sessionId={}: {}", ctx.sessionId, msg);
                    if (!ctx.resultFuture.isDone()) {
                        ctx.resultFuture.completeExceptionally(new RuntimeException(msg));
                    }
                    cleanup(ctx.sessionId);
                    break;
                case "heartbeat":
                    // 保活心跳, 收集模式下仅更新活动时间
                    break;
                default:
                    // tool_start / tool_end 在收集模式下忽略
                    break;
            }
        } catch (Exception e) {
            log.error("解析 WebSocket 消息失败, sessionId={}: {}", ctx.sessionId, e.getMessage());
        }
    }

    // ==================== 连接异常/完成处理 ====================

    /**
     * WebSocket 连接异常回调。
     * 流模式: 通过 SSE 通知前端后清理。
     * 收集模式: 如有重连额度则指数退避重试。
     */
    private void onConnectionError(SessionContext ctx, Throwable error, boolean reconnectable) {
        if (ctx.emitter != null) {
            // 流模式: 通知前端连接中断, 不自动重连
            try {
                ctx.emitter.send(SseEmitter.event().name("error")
                        .data("连接中断: " + error.getMessage()));
                ctx.emitter.completeWithError(error);
            } catch (Exception ignored) {
                // emitter 可能已关闭
            }
            cleanup(ctx.sessionId);
        } else if (ctx.resultFuture != null && !ctx.resultFuture.isDone() && reconnectable) {
            // 收集模式: 自动重连
            attemptReconnect(ctx);
        } else if (ctx.resultFuture != null && !ctx.resultFuture.isDone()) {
            ctx.resultFuture.completeExceptionally(error);
            cleanup(ctx.sessionId);
        }
    }

    /**
     * WebSocket 连接正常关闭回调。
     */
    private void onConnectionComplete(SessionContext ctx) {
        if (ctx.emitter != null) {
            try {
                ctx.emitter.complete();
            } catch (Exception ignored) {
            }
            cleanup(ctx.sessionId);
        } else if (ctx.resultFuture != null && !ctx.resultFuture.isDone()) {
            // 收集模式下连接关闭但 future 未完成: 可能是 Python 端异常退出
            if (ctx.reconnectAttempts < aiEngineProperties.getMaxReconnectAttempts()
                    && ctx.collectBuffer.length() == 0) {
                // 尚未收到任何 token, 尝试重连
                attemptReconnect(ctx);
            } else {
                // 已收到部分内容, 直接完成 (不再重连)
                ctx.resultFuture.complete(ctx.collectBuffer.toString());
                cleanup(ctx.sessionId);
            }
        } else {
            cleanup(ctx.sessionId);
        }
    }

    // ==================== 重连逻辑 (收集模式) ====================

    /**
     * 指数退避延迟: baseDelay × 2^attempt, 上限 maxDelay。
     */
    private long calculateBackoff(int attempt) {
        long delay = (long) aiEngineProperties.getReconnectBaseDelayMs() * (1L << attempt);
        return Math.min(delay, aiEngineProperties.getReconnectMaxDelayMs());
    }

    /**
     * 调度一次重连尝试 (收集模式专用)。
     */
    private void attemptReconnect(SessionContext ctx) {
        if (ctx.reconnectAttempts >= aiEngineProperties.getMaxReconnectAttempts()) {
            log.error("重连次数耗尽, sessionId={}, attempts={}",
                    ctx.sessionId, ctx.reconnectAttempts);
            if (ctx.resultFuture != null && !ctx.resultFuture.isDone()) {
                ctx.resultFuture.completeExceptionally(
                        new RuntimeException("WebSocket 重连失败, 已尝试 "
                                + ctx.reconnectAttempts + " 次"));
            }
            cleanup(ctx.sessionId);
            return;
        }

        long delay = calculateBackoff(ctx.reconnectAttempts);
        ctx.reconnectAttempts++;
        log.info("计划重连, sessionId={}, attempt={}/{}, delay={}ms",
                ctx.sessionId, ctx.reconnectAttempts,
                aiEngineProperties.getMaxReconnectAttempts(), delay);

        ctx.reconnectFuture = scheduler.schedule(
                () -> doConnect(ctx, true), delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 取消待执行的重连任务。
     */
    private void cancelReconnect(SessionContext ctx) {
        ScheduledFuture<?> future = ctx.reconnectFuture;
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    // ==================== 空闲检测 ====================

    /**
     * 定时扫描所有会话, 超过空闲阈值的连接自动关闭释放资源。
     */
    private void checkIdleSessions() {
        long now = System.currentTimeMillis();
        long idleThreshold = aiEngineProperties.getIdleTimeoutSeconds() * 1000L;

        for (Map.Entry<String, SessionContext> entry : sessions.entrySet()) {
            SessionContext ctx = entry.getValue();
            if (ctx.state == ConnectionState.ACTIVE || ctx.state == ConnectionState.IDLE) {
                long idleTime = now - ctx.lastActivityTime;
                if (idleTime > idleThreshold) {
                    log.info("关闭空闲连接, sessionId={}, 空闲时间={}s",
                            ctx.sessionId, idleTime / 1000);
                    ctx.state = ConnectionState.IDLE;
                    cancelSession(ctx.sessionId);
                }
            }
        }
    }

    /** 更新会话最后活动时间戳 */
    private void updateActivity(SessionContext ctx) {
        ctx.lastActivityTime = System.currentTimeMillis();
        if (ctx.state == ConnectionState.IDLE) {
            ctx.state = ConnectionState.ACTIVE;
        }
    }

    // ==================== 失败 & 清理 ====================

    /** 连接建立前即失败 (如序列化异常) */
    private void failContext(SessionContext ctx, Throwable error) {
        if (ctx.emitter != null) {
            ctx.emitter.completeWithError(error);
        }
        if (ctx.resultFuture != null && !ctx.resultFuture.isDone()) {
            ctx.resultFuture.completeExceptionally(error);
        }
        cleanup(ctx.sessionId);
    }

    /** 取消旧连接并移除会话 (新连接建立前调用) */
    private void cancelExistingSession(String sessionId) {
        SessionContext ctx = sessions.remove(sessionId);
        if (ctx == null) {
            return;
        }
        cancelReconnect(ctx);
        if (ctx.disposable != null && !ctx.disposable.isDisposed()) {
            ctx.disposable.dispose();
        }
        if (ctx.wsSession != null && ctx.wsSession.isOpen()) {
            ctx.wsSession.close().subscribe(
                    null,
                    e -> log.warn("关闭旧 WebSocket 失败, sessionId={}: {}", sessionId, e.getMessage())
            );
        }
    }

    /** 正常清理会话 (连接结束时调用) */
    private void cleanup(String sessionId) {
        SessionContext ctx = sessions.remove(sessionId);
        if (ctx == null) {
            return;
        }
        cancelReconnect(ctx);
        if (ctx.disposable != null && !ctx.disposable.isDisposed()) {
            ctx.disposable.dispose();
        }
    }

    // ==================== 远程工具执行 ====================

    /**
     * 处理 tool_call 帧 — 异步执行远程工具, 结果通过 WebSocket 回传给 Python。
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
                    sendToolResult(sessionId, toolCallId,
                            "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
                    return null;
                });
    }

    /** 通过 WebSocket 向 Python 发送 tool_result 帧 */
    private void sendToolResult(String sessionId, String toolCallId, String content) {
        // 防御: content 为 null 时转为错误 JSON, 避免 Map.of() 抛 NPE
        final String safeContent = content != null ? content : "{\"error\": \"工具无返回数据\"}";

        SessionContext ctx = sessions.get(sessionId);
        if (ctx == null) {
            log.warn("会话已不存在, 无法发送 tool_result, sessionId={}", sessionId);
            return;
        }
        WebSocketSession wsSession = ctx.wsSession;
        if (wsSession != null && wsSession.isOpen()) {
            try {
                String resultJson = objectMapper.writeValueAsString(
                        Map.of("type", "tool_result",
                                "tool_call_id", toolCallId,
                                "content", safeContent));
                wsSession.send(Mono.just(wsSession.textMessage(resultJson)))
                        .subscribe(
                                null,
                                e -> log.error("发送 tool_result 失败, sessionId={}: {}",
                                        sessionId, e.getMessage()));
            } catch (Exception e) {
                log.error("序列化 tool_result 失败, sessionId={}: {}", sessionId, e.getMessage());
            }
        } else {
            log.warn("WebSocket 已关闭, 无法发送 tool_result, sessionId={}", sessionId);
        }
    }
}
