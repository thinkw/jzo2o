package com.jzo2o.ai.service;

import com.jzo2o.ai.model.dto.request.ChatRequestDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
}
