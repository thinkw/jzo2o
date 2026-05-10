package com.jzo2o.orders.manager.strategy;

import java.math.BigDecimal;

/**
 * @author Mr.M
 * @version 1.0
 * @description 支付策略接口
 * @date 2024/11/6 17:23
 */
public interface PaymentStrategy {
    void pay(BigDecimal amount);
}
