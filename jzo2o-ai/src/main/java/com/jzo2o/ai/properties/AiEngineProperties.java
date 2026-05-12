package com.jzo2o.ai.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Python AI引擎连接配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "jzo2o.ai.engine")
public class AiEngineProperties {

    /** Python引擎HTTP地址 (保留, 向后兼容) */
    private String baseUrl = "http://localhost:8000";

    /** Python引擎WebSocket地址 */
    private String wsUrl = "ws://localhost:8000";

    /** 传输模式: ws(WebSocket) / http(HTTP), 默认 ws */
    private String mode = "ws";

    /** 连接超时 (毫秒) */
    private Integer connectTimeout = 5000;

    /** 读取超时 (毫秒), SSE长连接需要较大值 */
    private Integer readTimeout = 120000;
}
