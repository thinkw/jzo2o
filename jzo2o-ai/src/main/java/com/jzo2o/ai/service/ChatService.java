package com.jzo2o.ai.service;

import com.jzo2o.ai.model.dto.request.ChatRequestDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 聊天服务接口
 */
public interface ChatService {

    /**
     * 处理聊天消息，返回 SSE 流式响应
     *
     * @param request 聊天请求 (含消息列表和会话ID)
     * @return SseEmitter 前端流式消费
     */
    SseEmitter chat(ChatRequestDTO request);

    /**
     * 取消指定会话的 AI 生成 — 关闭 WebSocket → Python Agent 立即停止
     *
     * @param sessionId 会话ID
     */
    void cancel(String sessionId);

    /**
     * 获取当前用户的会话列表 (含预览信息)
     *
     * @return 会话列表, 按最近活动倒序
     */
    List<Map<String, Object>> listSessions();

    /**
     * 获取指定会话的消息历史
     *
     * @param sessionId 会话ID
     * @return 消息列表, 按时间正序
     */
    List<Map<String, Object>> getSessionMessages(String sessionId);
}
