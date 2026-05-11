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
import java.util.stream.Collectors;

/**
 * 聊天服务实现 — 鉴权、持久化、SSE代理
 * Python引擎返回原始 LLM token 流, 由本层直接包装为 SSE 发给前端
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    @Resource
    private AiEngineClient aiEngineClient;

    @Resource
    private AiChatRecordMapper aiChatRecordMapper;

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
        // WebClient 的 bodyToFlux 使用行解码器, 每个 Flux 元素是原始响应的
        // 一行内容 (不含 \n)。直接发送单行 SSE 给前端, 前端负责在每个 chunk
        // 后追加 \n 来还原原始文档结构。不转义, 避免与 LaTeX 命令冲突。
        StringBuilder responseBuilder = new StringBuilder();

        aiEngineClient.streamChat(messages)
                .subscribe(
                        rawChunk -> {
                            if (rawChunk.startsWith("[ERROR]")) {
                                log.error("Python引擎返回错误: {}", rawChunk);
                                return;
                            }
                            try {
                                emitter.send(SseEmitter.event().data(rawChunk));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            responseBuilder.append(rawChunk).append("\n");
                        },
                        error -> {
                            log.error("聊天流式传输异常: {}", error.getMessage());
                            emitter.completeWithError(error);
                        },
                        () -> {
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
