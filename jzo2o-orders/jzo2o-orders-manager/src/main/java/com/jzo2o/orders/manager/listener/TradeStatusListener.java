package com.jzo2o.orders.manager.listener;

import com.alibaba.fastjson.JSON;
import com.jzo2o.api.trade.enums.TradingStateEnum;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.common.utils.CollUtils;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Mr.M
 * @version 1.0
 * @description TODO
 * @date 2024/11/6 15:33
 */
@Slf4j
@Component
public class TradeStatusListener {
    @Resource
    private IOrdersCreateService ordersCreateService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "jzo2o.queue.orders.trade.update.Status", durable = "true"),
            exchange = @Exchange(value = "jzo2o.exchange.topic.trade", type = "topic"),
            key = "UPDATE_STATUS"
    ))
    public void listenTradeUpdatePayStatusMsg(String msg) {

        //解析消息
        log.info("监听到订单支付结果消息：{}", msg);
        List<TradeStatusMsg> tradeStatusMsgs = JSON.parseArray(msg, TradeStatusMsg.class);
        //过滤支付成功的且是家政项目的支付通知
        List<TradeStatusMsg> collect = tradeStatusMsgs.stream().filter(tradeStatusMsg -> tradeStatusMsg.getStatusCode() == TradingStateEnum.YJS.getCode() && tradeStatusMsg.getProductAppId().equals("jzo2o.orders")).collect(Collectors.toList());
        //如果为空则直接返回
        if (CollUtils.isEmpty(collect)) {
            return;
        }
        for (TradeStatusMsg tradeStatusMsg : collect) {
            //更新订单支付状态
            ordersCreateService.paySuccess(tradeStatusMsg);
        }

    }

}
