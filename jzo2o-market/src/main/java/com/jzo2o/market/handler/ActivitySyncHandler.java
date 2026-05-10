//package com.jzo2o.market.handler;
//
//import com.jzo2o.canal.listeners.AbstractCanalRabbitMqMsgListener;
//import com.jzo2o.common.utils.DateUtils;
//import com.jzo2o.market.model.domain.Activity;
//import org.springframework.amqp.core.ExchangeTypes;
//import org.springframework.amqp.core.Message;
//import org.springframework.amqp.rabbit.annotation.Exchange;
//import org.springframework.amqp.rabbit.annotation.Queue;
//import org.springframework.amqp.rabbit.annotation.QueueBinding;
//import org.springframework.amqp.rabbit.annotation.RabbitListener;
//import org.springframework.data.redis.core.RedisTemplate;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.Resource;
//import java.time.LocalDateTime;
//import java.util.List;
//
//import static com.jzo2o.market.constants.RedisConstants.RedisKey.COUPON_RESOURCE_STOCK;
//
///**
// * 活动同步handler
// * 运营人员管理活动信息通过Canal同步到Redis中
// */
//@Component
//public class ActivitySyncHandler extends AbstractCanalRabbitMqMsgListener<Activity> {
//
//    @Resource
//    private RedisTemplate redisTemplate;
//
//    @RabbitListener(bindings = @QueueBinding(
//            value = @Queue(name = "canal-mq-jzo2o-market-resource"),
//            exchange = @Exchange(name = "exchange.canal-jzo2o", type = ExchangeTypes.TOPIC),
//            key = "canal-mq-jzo2o-market-resource"),
//            concurrency = "1"
//    )
//    public void onMessage(Message message) throws Exception {
//        parseMsg(message);
//    }
//
//
//    @Override
//    public void batchSave(List<Activity> data) {
//        LocalDateTime now = DateUtils.now();
//        data.stream()
//                //活动开始后库存不允许修改，这里将其过滤掉不再同步
//                .filter(activity -> activity.getDistributeStartTime().isBefore(now))
//                .forEach(activity -> {
//                    redisTemplate.opsForHash().put(String.format(COUPON_RESOURCE_STOCK, activity.getId() % 10), activity.getId(), activity.getTotalNum());
//                });
//
//    }
//
//    @Override
//    public void batchDelete(List<Long> ids) {
//
//    }
//}
