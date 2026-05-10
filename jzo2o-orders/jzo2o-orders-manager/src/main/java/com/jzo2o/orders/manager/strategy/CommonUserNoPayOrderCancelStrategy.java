package com.jzo2o.orders.manager.strategy;

import com.jzo2o.api.market.CouponApi;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.OrdersCanceled;
import com.jzo2o.orders.base.model.dto.OrderUpdateStatusDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.service.IOrdersCanceledService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.jzo2o.orders.manager.service.impl.OrdersManagerServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * @author Mr.M
 * @version 1.0
 * @description c端用户取消待支付订单策略类
 * @date 2024/11/11 10:18
 */
//每个策略类定义的bean名称为“用户类型：订单状态”。
@Component("1:NO_PAY")
@Slf4j
public class CommonUserNoPayOrderCancelStrategy implements OrderCancelStrategy {

    @Resource
    private OrdersManagerServiceImpl owner;

    @Resource
    private CouponApi couponApi;

    @Resource
    private IOrdersCommonService ordersCommonService;

    @Resource
    private IOrdersRefundService ordersRefundService;

    @Resource
    private IOrdersCanceledService ordersCanceledService;

    @Resource
    private OrdersMapper ordersMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(OrderCancelDTO orderCancelDTO) {
        //更新订单状态由待支付改为已取消
        OrderUpdateStatusDTO orderUpdateStatusReqDTO = new OrderUpdateStatusDTO();
        //订单id
        orderUpdateStatusReqDTO.setId(orderCancelDTO.getId());
        //原始状态
        orderUpdateStatusReqDTO.setOriginStatus(OrderStatusEnum.NO_PAY.getStatus());
        //目标状态
        orderUpdateStatusReqDTO.setTargetStatus(OrderStatusEnum.CANCELED.getStatus());
        ordersCommonService.updateStatus(orderUpdateStatusReqDTO);

        //向取消订单记录表插入一条记录
        OrdersCanceled ordersCanceled = new OrdersCanceled();
        //订单id
        ordersCanceled.setId(orderCancelDTO.getId());
        //取消人id
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        ordersCanceled.setCancelReason(orderCancelDTO.getCancelReason());
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        ordersCanceled.setCancelTime(LocalDateTime.now());

        ordersCanceledService.save(ordersCanceled);
    }
}
