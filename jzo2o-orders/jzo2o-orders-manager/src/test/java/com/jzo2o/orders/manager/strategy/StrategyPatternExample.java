package com.jzo2o.orders.manager.strategy;

import java.math.BigDecimal;

/**
 * @author Mr.M
 * @version 1.0
 * @description TODO
 * @date 2024/11/6 17:26
 */
public class StrategyPatternExample {
 public static void main(String[] args) {
  // 创建环境类
  ShoppingCart shoppingCart = new ShoppingCart();

  // 选择支付策略--信用卡支付
  PaymentStrategy creditCardPayment = new CreditCardPayment("1234-5678-9876-5432");
  shoppingCart.setPaymentStrategy(creditCardPayment);

  // 进行支付
  shoppingCart.checkout(new BigDecimal(100));

  // 切换支付策略，使用微信支付
  PaymentStrategy weixinPayment = new WeixinPayment("example@example.com");
  shoppingCart.setPaymentStrategy(weixinPayment);

  // 进行支付
  shoppingCart.checkout(new BigDecimal(50));
 }
}
