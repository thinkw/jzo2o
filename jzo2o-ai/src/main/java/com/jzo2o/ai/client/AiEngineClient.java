package com.jzo2o.ai.client;

import com.jzo2o.ai.properties.AiEngineProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * Python AI引擎 HTTP 客户端 — 基于 WebClient 消费 SSE 流
 */
@Slf4j
@Component
public class AiEngineClient {

    @Resource
    private WebClient webClient;

    @Resource
    private AiEngineProperties aiEngineProperties;

    /**
     * 发送聊天消息到 Python 引擎，返回 SSE 字符串流
     *
     * @param messages OpenAI格式的消息列表
     * @return SSE 格式的 Flux<String>, 每个元素为一条 SSE 数据行
     */
    public Flux<String> streamChat(List<Map<String, String>> messages) {
        String url = aiEngineProperties.getBaseUrl() + "/chat/completions";

        Map<String, Object> body = Map.of(
                "messages", messages,
                "stream", true
        );

        log.info("调用 Python AI引擎: {}", url);

        return webClient.post()
                .uri(url)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnError(e -> log.error("Python AI引擎调用异常: {}", e.getMessage()));
    }
}
