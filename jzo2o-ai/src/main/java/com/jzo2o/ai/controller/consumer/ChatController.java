package com.jzo2o.ai.controller.consumer;

import com.jzo2o.ai.model.dto.request.ChatRequestDTO;
import com.jzo2o.ai.service.ChatService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiImplicitParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

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

    @DeleteMapping("/cancel")
    @ApiOperation("取消正在生成的 AI 回复")
    @ApiImplicitParam(name = "sessionId", value = "会话ID", required = true, dataTypeClass = String.class)
    public void cancel(@RequestParam String sessionId) {
        chatService.cancel(sessionId);
    }

    @GetMapping("/sessions")
    @ApiOperation("获取当前用户的会话列表")
    public List<Map<String, Object>> listSessions() {
        return chatService.listSessions();
    }

    @GetMapping("/sessions/{sessionId}")
    @ApiOperation("获取指定会话的消息历史")
    public List<Map<String, Object>> getSessionMessages(@PathVariable String sessionId) {
        return chatService.getSessionMessages(sessionId);
    }
}
