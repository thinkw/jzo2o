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

    /** Python引擎地址 */
    private String baseUrl = "http://localhost:8000";

    /** 连接超时 (毫秒) */
    private Integer connectTimeout = 5000;

    /** 读取超时 (毫秒), SSE长连接需要较大值 */
    private Integer readTimeout = 120000;
}
