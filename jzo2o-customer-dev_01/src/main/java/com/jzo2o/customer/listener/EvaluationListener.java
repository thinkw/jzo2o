package com.jzo2o.customer.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 评价评分监听器
 * 评分计算已改为在EvaluationServiceImpl中同步执行，此监听器已废弃
 *
 * @author itcast
 * @deprecated 评分计算改为同步，不再依赖MQ消息
 **/
@Slf4j
@Component
@Deprecated
public class EvaluationListener {
    // MQ监听已废弃，评分同步计算逻辑见 EvaluationServiceImpl.recalculateServeProviderScore()
}
