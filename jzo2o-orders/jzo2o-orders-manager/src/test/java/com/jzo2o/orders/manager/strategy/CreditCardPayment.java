package com.jzo2o.orders.manager.strategy;

import java.math.BigDecimal;

/**
 * @author Mr.M
 * @version 1.0
 * @description TODO
 * @date 2024/11/6 17:25
 */
public class CreditCardPayment implements PaymentStrategy {
    private String cardNumber;

    public CreditCardPayment(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    @Override
    public void pay(BigDecimal amount) {
        System.out.println("信用卡:" + cardNumber + "支付金额:" + amount);
    }
}
