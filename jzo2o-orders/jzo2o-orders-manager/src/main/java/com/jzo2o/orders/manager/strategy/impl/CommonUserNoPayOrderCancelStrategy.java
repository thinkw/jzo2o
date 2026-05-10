package com.jzo2o.orders.manager.strategy.impl;

import cn.hutool.core.bean.BeanUtil;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.enums.OrderStatusChangeEventEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.model.domain.OrdersCanceled;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.model.dto.OrderUpdateStatusDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.service.IOrdersCanceledService;
import com.jzo2o.orders.manager.strategy.OrderCancelStrategy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * @author Mr.M
 * @version 1.0
 * @description c端用户取消待支付订单策略类
 * @date 2024/11/6 17:38
 */
@Component("1:NO_PAY")
public class CommonUserNoPayOrderCancelStrategy implements OrderCancelStrategy {
    @Resource
    private IOrdersCommonService ordersCommonService;
    @Resource
    private IOrdersCanceledService ordersCanceledService;

    @Resource
    private OrderStateMachine orderStateMachine;
    @Override
    @Transactional
    public void cancel(OrderCancelDTO orderCancelDTO) {
//        //保证取消订单记录
//        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderCancelDTO, OrdersCanceled.class);
//        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
//        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
//        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
//        ordersCanceled.setCancelTime(LocalDateTime.now());
//        ordersCanceledService.save(ordersCanceled);
//        //更新订单状态
//        OrderUpdateStatusDTO orderUpdateStatusDTO = new OrderUpdateStatusDTO();
//        //订单id
//        orderUpdateStatusDTO.setId(orderCancelDTO.getId());
//        //原始订单状态
//        orderUpdateStatusDTO.setOriginStatus(OrderStatusEnum.NO_PAY.getStatus());
//        //目标状态
//        orderUpdateStatusDTO.setTargetStatus(OrderStatusEnum.CANCELED.getStatus());
//        Integer integer = ordersCommonService.updateStatus(orderUpdateStatusDTO);
//        if (integer == 0) {
//            throw new RuntimeException("订单取消执行失败");
//        }
        //2.构建订单快照更新模型
        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
                .cancellerId(orderCancelDTO.getCurrentUserId())
                .cancelerName(orderCancelDTO.getCurrentUserName())
                .cancellerType(orderCancelDTO.getCurrentUserType())
                .cancelReason(orderCancelDTO.getCancelReason())
                .cancelTime(LocalDateTime.now())
                .build();

        //3.保存订单取消记录
        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderSnapshotDTO, OrdersCanceled.class);
        ordersCanceled.setId(orderCancelDTO.getId());
        ordersCanceledService.save(ordersCanceled);

        //4.订单状态变更
        orderStateMachine.changeStatus(orderCancelDTO.getUserId(), orderCancelDTO.getId().toString(), OrderStatusChangeEventEnum.CANCEL, orderSnapshotDTO);

    }
}
