package com.jzo2o.orders.manager.strategy;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jzo2o.api.market.CouponApi;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusChangeEventEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersCanceled;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.model.dto.OrderUpdateStatusDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.service.IOrdersCanceledService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.jzo2o.orders.manager.service.impl.OrdersManagerServiceImpl;
import com.jzo2o.statemachine.core.StatusChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * @author Mr.M
 * @version 1.0
 * @description c端取消派单中订单
 * @date 2024/11/11 10:21
 */
@Component("1:DISPATCHING")
@Slf4j
public class CommonUserDispatchingOrderCancelStrategy implements OrderCancelStrategy {

    //注入订单状态机
    @Resource
    private OrderStateMachine orderStateMachine;

    @Resource
    private OrdersManagerServiceImpl owner;

    @Resource
    private CouponApi couponApi;

    @Resource
    private IOrdersCommonService ordersCommonService;

    @Resource
    private IOrdersRefundService ordersRefundService;

    @Resource
    private OrdersMapper ordersMapper;
    //注入IOrdersCanceledService
    @Resource
    private IOrdersCanceledService ordersCanceledService;
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(OrderCancelDTO orderCancelDTO) {
//        //更新订单状态为已关闭
//        OrderUpdateStatusDTO orderUpdateStatusReqDTO = new OrderUpdateStatusDTO();
//        //订单id
//        orderUpdateStatusReqDTO.setId(orderCancelDTO.getId());
//        //原始状态
//        orderUpdateStatusReqDTO.setOriginStatus(OrderStatusEnum.DISPATCHING.getStatus());
//        //目标状态
//        orderUpdateStatusReqDTO.setTargetStatus(OrderStatusEnum.CLOSED.getStatus());
//        ordersCommonService.updateStatus(orderUpdateStatusReqDTO);

        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
                .cancellerId(orderCancelDTO.getCurrentUserId())
                .cancelerName(orderCancelDTO.getCurrentUserName())
                .cancellerType(orderCancelDTO.getCurrentUserType())
                .cancelReason(orderCancelDTO.getCancelReason())
                .cancelTime(LocalDateTime.now())
                .build();
        //指定事件
        orderStateMachine.changeStatus(null, String.valueOf(orderCancelDTO.getId()), OrderStatusChangeEventEnum.CLOSE_DISPATCHING_ORDER,orderSnapshotDTO);

        //添加取消记录
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

        //添加退款记录
        OrdersRefund ordersRefund = new OrdersRefund();
        ordersRefund.setId(orderCancelDTO.getId());
        ordersRefund.setTradingOrderNo(orderCancelDTO.getTradingOrderNo());
        ordersRefund.setRealPayAmount(orderCancelDTO.getRealPayAmount());
        ordersRefund.setCreateTime(LocalDateTime.now());
        //保存退款记录
        ordersRefundService.save(ordersRefund);
        //更新订单表中的退款状态为退款中
        LambdaUpdateWrapper<Orders> lambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        lambdaUpdateWrapper.eq(Orders::getId, orderCancelDTO.getId())
                .set(Orders::getRefundStatus, OrderRefundStatusEnum.REFUNDING.getStatus());
        int update = ordersMapper.update(null, lambdaUpdateWrapper);
        if (update<=0) {
            //抛出异常
            log.error("订单退款失败");
            throw new CommonException("订单退款失败");
        }
    }
//    @Override
//    @Transactional(rollbackFor = Exception.class)
//    public void cancel(OrderCancelDTO orderCancelDTO) {
//        //更新订单状态为已关闭
//        OrderUpdateStatusDTO orderUpdateStatusReqDTO = new OrderUpdateStatusDTO();
//        //订单id
//        orderUpdateStatusReqDTO.setId(orderCancelDTO.getId());
//        //原始状态
//        orderUpdateStatusReqDTO.setOriginStatus(OrderStatusEnum.DISPATCHING.getStatus());
//        //目标状态
//        orderUpdateStatusReqDTO.setTargetStatus(OrderStatusEnum.CLOSED.getStatus());
//        ordersCommonService.updateStatus(orderUpdateStatusReqDTO);
//        //添加取消记录
//        //向取消订单记录表插入一条记录
//        OrdersCanceled ordersCanceled = new OrdersCanceled();
//        //订单id
//        ordersCanceled.setId(orderCancelDTO.getId());
//        //取消人id
//        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
//        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
//        ordersCanceled.setCancelReason(orderCancelDTO.getCancelReason());
//        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
//        ordersCanceled.setCancelTime(LocalDateTime.now());
//
//        ordersCanceledService.save(ordersCanceled);
//
//        //添加退款记录
//        OrdersRefund ordersRefund = new OrdersRefund();
//        ordersRefund.setId(orderCancelDTO.getId());
//        ordersRefund.setTradingOrderNo(orderCancelDTO.getTradingOrderNo());
//        ordersRefund.setRealPayAmount(orderCancelDTO.getRealPayAmount());
//        ordersRefund.setCreateTime(LocalDateTime.now());
//        //保存退款记录
//        ordersRefundService.save(ordersRefund);
//        //更新订单表中的退款状态为退款中
//        LambdaUpdateWrapper<Orders> lambdaUpdateWrapper = new LambdaUpdateWrapper<>();
//        lambdaUpdateWrapper.eq(Orders::getId, orderCancelDTO.getId())
//                .set(Orders::getRefundStatus, OrderRefundStatusEnum.REFUNDING.getStatus());
//        int update = ordersMapper.update(null, lambdaUpdateWrapper);
//        if (update<=0) {
//            //抛出异常
//            log.error("订单退款失败");
//            throw new CommonException("订单退款失败");
//        }
//    }
}
