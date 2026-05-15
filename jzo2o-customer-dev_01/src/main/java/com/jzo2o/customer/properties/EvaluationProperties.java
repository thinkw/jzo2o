package com.jzo2o.customer.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 评价配置属性
 *
 * @author itcast
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "jzo2o.evaluation")
public class EvaluationProperties {

    /**
     * 服务项评价
     */
    private EvaluationTargetProperties serveItem;

    /**
     * 服务人员/机构评价
     */
    private EvaluationTargetProperties serveProvider;
}
