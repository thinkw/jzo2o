package com.jzo2o.ai.model.dto.request;

import lombok.Data;

import java.util.List;

/**
 * 聊天请求DTO — 兼容OpenAI格式，前端传入
 */
@Data
public class ChatRequestDTO {

    /** 对话消息列表 */
    private List<ChatMessage> messages;

    /** 会话ID, 为空时后端自动生成 */
    private String sessionId;

    /** 是否流式返回, 默认true */
    private Boolean stream = true;

    @Data
    public static class ChatMessage {
        /** 角色: user / assistant / system */
        private String role;
        /** 消息文本 */
        private String content;
    }
}
