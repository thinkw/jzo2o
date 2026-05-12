package com.jzo2o.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

/**
 * WebSocket 客户端配置 — Java 连接 Python AI 引擎
 */
@Configuration
public class WebSocketClientConfig {

    @Bean
    public ReactorNettyWebSocketClient reactorNettyWebSocketClient() {
        return new ReactorNettyWebSocketClient();
    }
}
