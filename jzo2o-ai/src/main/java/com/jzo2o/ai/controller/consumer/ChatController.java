package com.jzo2o.ai.controller.consumer;

import com.jzo2o.ai.model.dto.request.ChatRequestDTO;
import com.jzo2o.ai.service.ChatService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;

/**
 * 用户端聊天控制器 — SSE 流式响应
 */
@Slf4j
@RestController
@RequestMapping("/consumer/chat")
@Api(tags = "AI助手-用户端聊天")
public class ChatController {

    @Resource
    private ChatService chatService;

    @PostMapping("/completions")
    @ApiOperation("流式聊天 (SSE)")
    public SseEmitter chatCompletions(@RequestBody ChatRequestDTO request) {
        log.info("收到聊天请求, sessionId: {}", request.getSessionId());
        return chatService.chat(request);
    }
}
