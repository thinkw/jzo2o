package com.jzo2o.ai.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天响应DTO — 非流式场景使用（保留扩展）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseDTO {

    /** 会话ID */
    private String sessionId;

    /** AI回复内容 */
    private String content;
}
