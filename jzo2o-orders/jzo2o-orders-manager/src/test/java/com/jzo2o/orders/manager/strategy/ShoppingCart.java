package com.jzo2o.orders.manager.strategy;

import java.math.BigDecimal;

/**
 * @author Mr.M
 * @version 1.0
 * @description TODO
 * @date 2024/11/6 17:25
 */
class ShoppingCart {
 private PaymentStrategy paymentStrategy;

 public void setPaymentStrategy(PaymentStrategy paymentStrategy) {
  this.paymentStrategy = paymentStrategy;
 }

 public void checkout(BigDecimal amount) {
  paymentStrategy.pay(amount);
 }
}
