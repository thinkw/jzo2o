package com.jzo2o.ai;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * jzo2o-ai — AI助手微服务
 * 职责: 鉴权、敏感词初滤、业务数据预取、对话持久化、流量整形
 * 严禁: 编写Prompt逻辑、处理向量检索
 */
@Slf4j
@SpringBootApplication
@MapperScan("com.jzo2o.ai.mapper")
@EnableFeignClients(basePackages = "com.jzo2o.api")
public class AiApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(AiApplication.class)
                .build(args)
                .run(args);
        log.info("家政服务-AI助手服务启动");
    }
}
