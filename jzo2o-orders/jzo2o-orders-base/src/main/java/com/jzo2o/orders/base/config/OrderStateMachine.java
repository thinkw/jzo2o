package com.jzo2o.orders.base.config;

import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.service.IHistoryOrdersSyncCommonService;
import com.jzo2o.statemachine.AbstractStateMachine;
import com.jzo2o.statemachine.core.StatusDefine;
import com.jzo2o.statemachine.persist.StateMachinePersister;
import com.jzo2o.statemachine.snapshot.BizSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @author Mr.M
 * @version 1.0
 * @description 订单状态机
 * @date 2024/11/13 9:07
 */
@Component
@Slf4j
public class OrderStateMachine extends AbstractStateMachine<OrderSnapshotDTO> {

    @Resource
    private IHistoryOrdersSyncCommonService historyOrdersSyncCommonService;

    protected OrderStateMachine(StateMachinePersister stateMachinePersister, BizSnapshotService bizSnapshotService, RedisTemplate redisTemplate) {
        super(stateMachinePersister, bizSnapshotService, redisTemplate);
    }

    /**
     * 返回订单状态机的名称
     * @return
     */
    @Override
    protected String getName() {
        return "order";
    }

    @Override
    protected void postProcessor(OrderSnapshotDTO bizSnapshot) {
        /***************************完成、关闭、取消订单写历史订单同步表*******************************/
        //取出订单的新状态
        Integer ordersStatus = bizSnapshot.getOrdersStatus();
        if(OrderStatusEnum.FINISHED.getStatus().equals(ordersStatus) ||
                OrderStatusEnum.CLOSED.getStatus().equals(ordersStatus) ||
                OrderStatusEnum.CANCELED.getStatus().equals(ordersStatus) ){
            historyOrdersSyncCommonService.writeHistorySync(bizSnapshot.getId());
        }

    }

    /**
     * 订单初始化状态
     * @return
     */
    @Override
    protected StatusDefine getInitState() {
        return OrderStatusEnum.NO_PAY;
    }
}
