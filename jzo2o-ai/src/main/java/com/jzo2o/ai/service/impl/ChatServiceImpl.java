package com.jzo2o.ai.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.jzo2o.ai.client.AiEngineClient;
import com.jzo2o.ai.constants.AiConstants;
import com.jzo2o.ai.mapper.AiChatRecordMapper;
import com.jzo2o.ai.model.domain.AiChatRecord;
import com.jzo2o.ai.model.dto.request.ChatRequestDTO;
import com.jzo2o.ai.service.ChatService;
import com.jzo2o.common.model.CurrentUserInfo;
import com.jzo2o.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 聊天服务实现 — 鉴权、持久化、SSE代理
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    @Resource
    private AiEngineClient aiEngineClient;

    @Resource
    private AiChatRecordMapper aiChatRecordMapper;

    /** 提取 SSE data 行内容的正则: data: <content> */
    private static final Pattern SSE_DATA_PATTERN = Pattern.compile("^data:\\s?(.*)$", Pattern.MULTILINE);

    @Override
    public SseEmitter chat(ChatRequestDTO request) {
        // 1. 从 ThreadLocal 获取当前用户
        CurrentUserInfo user = UserContext.currentUser();
        Long userId = user.getId();
        Integer userType = user.getUserType();

        // 2. 生成或复用会话ID
        String sessionId = StrUtil.isNotBlank(request.getSessionId())
                ? request.getSessionId()
                : IdUtil.simpleUUID();

        // 3. 提取用户最后一条消息
        String userContent = extractLastUserMessage(request.getMessages());

        // 4. 持久化用户消息
        saveRecord(userId, userType, sessionId, AiConstants.ROLE_USER, userContent);

        // 5. 转换为 Python 引擎的消息格式
        List<Map<String, String>> messages = convertMessages(request.getMessages());

        // 6. 创建 SseEmitter (5分钟超时)
        SseEmitter emitter = new SseEmitter(AiConstants.SSE_TIMEOUT);

        // 7. 异步流式代理
        StringBuilder responseBuilder = new StringBuilder();

        aiEngineClient.streamChat(messages)
                .subscribe(
                        sseChunk -> {
                            // 转发 SSE 数据到前端
                            try {
                                emitter.send(SseEmitter.event().data(extractContent(sseChunk)));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            // 积累响应文本用于持久化
                            responseBuilder.append(extractContent(sseChunk));
                        },
                        error -> {
                            log.error("聊天流式传输异常: {}", error.getMessage());
                            emitter.completeWithError(error);
                        },
                        () -> {
                            // 流结束: 持久化助手回复
                            String fullResponse = responseBuilder.toString();
                            if (StrUtil.isNotBlank(fullResponse)) {
                                saveRecord(userId, userType, sessionId, AiConstants.ROLE_ASSISTANT, fullResponse);
                            }
                            emitter.complete();
                            log.info("聊天会话完成, sessionId: {}", sessionId);
                        }
                );

        return emitter;
    }

    /**
     * 从 SSE 数据行中提取纯文本内容
     * 输入: "data: 你好" → 输出: "你好"
     * 输入: "data: [DONE]" → 输出: ""
     */
    private String extractContent(String sseChunk) {
        if (StrUtil.isBlank(sseChunk)) {
            return "";
        }
        // 按行分割，提取 data: 后面的内容
        String[] lines = sseChunk.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                String content = trimmed.substring(5).trim();
                // 跳过结束标记
                if (!"[DONE]".equals(content) && !content.startsWith("[ERROR]")) {
                    sb.append(content);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 提取最后一条用户消息
     */
    private String extractLastUserMessage(List<ChatRequestDTO.ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatRequestDTO.ChatMessage msg = messages.get(i);
            if (AiConstants.ROLE_USER.equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return "";
    }

    /**
     * DTO 消息列表转为 Map 列表，传给 Python 引擎
     */
    private List<Map<String, String>> convertMessages(List<ChatRequestDTO.ChatMessage> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .collect(Collectors.toList());
    }

    /**
     * 保存聊天记录到数据库
     */
    private void saveRecord(Long userId, Integer userType, String sessionId, String role, String content) {
        AiChatRecord record = new AiChatRecord();
        record.setUserId(userId);
        record.setUserType(userType);
        record.setSessionId(sessionId);
        record.setRole(role);
        record.setContent(content);
        record.setCreateTime(LocalDateTime.now());
        aiChatRecordMapper.insert(record);
    }
}
