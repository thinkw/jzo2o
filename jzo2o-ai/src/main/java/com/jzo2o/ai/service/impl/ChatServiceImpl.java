package com.jzo2o.ai.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.jzo2o.ai.client.AiEngineClient;
import com.jzo2o.ai.client.AiEngineWebSocketClient;
import com.jzo2o.ai.properties.AiEngineProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private AiEngineWebSocketClient aiEngineWebSocketClient;

    @Resource
    private AiEngineProperties aiEngineProperties;

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

        // 7. 根据传输模式选择 HTTP 或 WebSocket
        if ("ws".equals(aiEngineProperties.getMode())) {
            // WebSocket 传输: 建立 per-session 连接, 双向 JSON 帧通信
            StringBuffer responseBuffer = new StringBuffer();

            aiEngineWebSocketClient.connectAndStream(sessionId, messages, emitter,
                    token -> responseBuffer.append(token).append("\n"),
                    () -> {
                        String fullResponse = responseBuffer.toString();
                        if (StrUtil.isNotBlank(fullResponse)) {
                            saveRecord(userId, userType, sessionId, AiConstants.ROLE_ASSISTANT, fullResponse);
                        }
                        log.info("聊天会话完成(WS), sessionId: {}", sessionId);
                    });
        } else {
            // HTTP 传输 (原有逻辑)
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
        }

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

    @Override
    public void cancel(String sessionId) {
        log.info("前端请求取消会话, sessionId={}", sessionId);
        aiEngineWebSocketClient.cancelSession(sessionId);
    }

    /**
     * 保存聊天记录到数据库
     */
    @Override
    public List<Map<String, Object>> listSessions() {
        CurrentUserInfo user = UserContext.currentUser();
        // 子查询: 每 session 取第一条用户消息作为预览, 按最新消息倒序
        List<AiChatRecord> records = aiChatRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiChatRecord>()
                        .eq(AiChatRecord::getUserId, user.getId())
                        .eq(AiChatRecord::getRole, "user")
                        .orderByDesc(AiChatRecord::getCreateTime)
        );
        // 按 sessionId 去重, 保留每个 session 的第一条消息
        Map<String, Map<String, Object>> sessionMap = new java.util.LinkedHashMap<>();
        for (AiChatRecord r : records) {
            if (!sessionMap.containsKey(r.getSessionId())) {
                Map<String, Object> item = new java.util.HashMap<>();
                item.put("sessionId", r.getSessionId());
                item.put("preview", r.getContent().length() > 50
                        ? r.getContent().substring(0, 50) + "..." : r.getContent());
                item.put("lastTime", r.getCreateTime().toString());
                sessionMap.put(r.getSessionId(), item);
            }
        }
        return new ArrayList<>(sessionMap.values());
    }

    @Override
    public List<Map<String, Object>> getSessionMessages(String sessionId) {
        CurrentUserInfo user = UserContext.currentUser();
        List<AiChatRecord> records = aiChatRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiChatRecord>()
                        .eq(AiChatRecord::getUserId, user.getId())
                        .eq(AiChatRecord::getSessionId, sessionId)
                        .orderByAsc(AiChatRecord::getCreateTime)
        );
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiChatRecord r : records) {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("role", r.getRole());
            item.put("content", r.getContent());
            item.put("createTime", r.getCreateTime().toString());
            result.add(item);
        }
        return result;
    }

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
