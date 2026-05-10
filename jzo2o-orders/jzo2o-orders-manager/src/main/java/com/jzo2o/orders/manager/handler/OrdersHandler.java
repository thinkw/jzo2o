package com.jzo2o.orders.manager.handler;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.db.sql.Order;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jzo2o.api.trade.RefundRecordApi;
import com.jzo2o.api.trade.dto.response.ExecutionResultResDTO;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.jzo2o.orders.manager.service.impl.OrdersManagerServiceImpl;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/**
 * @author Mr.M
 * @version 1.0
 * @description TODO
 * @date 2024/11/5 22:03
 */
@Component
public class OrdersHandler {

    @Resource
    private IOrdersCreateService ordersCreateService;
    //注入ordersManagerService
    @Resource
    private OrdersManagerServiceImpl ordersManagerService;
    /**
     * 支付超时取消订单
     * 每分钟执行一次
     */
    @XxlJob(value = "cancelOverTimePayOrder")
    public void cancelOverTimePayOrder() {

        //查询支付超时状态订单
        List<Orders> ordersList = ordersCreateService.queryOverTimePayOrdersListByCount(100);
        if (CollUtil.isEmpty(ordersList)) {
            XxlJobHelper.log("查询超时订单列表为空！");
            return;
        }

        for (Orders orders : ordersList) {
            //取消订单
            OrderCancelDTO orderCancelDTO = new OrderCancelDTO();
            orderCancelDTO.setId(orders.getId());
            orderCancelDTO.setCurrentUserId(orders.getUserId());
            orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
            orderCancelDTO.setCancelReason("订单超时支付，自动取消");
            ordersManagerService.cancel(orderCancelDTO);

        }
    }

    @Resource
    private IOrdersRefundService ordersRefundService;

    /**
     * 订单退款异步任务
     */
    @XxlJob(value = "handleRefundOrders")
    public void handleRefundOrders() {
        //查询退款记录
        List<OrdersRefund> ordersRefundList = ordersRefundService.queryRefundOrderListByCount(100);
        //遍历
        for (OrdersRefund ordersRefund : ordersRefundList) {
            //请求退款
            requestRefundOrder(ordersRefund);
        }

    }
    @Resource
    private RefundRecordApi refundRecordApi;
    @Resource
    private OrdersHandler ordersHandler;
    /**
     * 请求退款
     */
    public void requestRefundOrder(OrdersRefund ordersRefund) {
        ExecutionResultResDTO executionResultResDTO = null;
        try {
            executionResultResDTO = refundRecordApi.refundTrading(ordersRefund.getTradingOrderNo(), ordersRefund.getId(), ordersRefund.getRealPayAmount());
        } catch (Exception e) {
            e.printStackTrace();
        }
        //如果不为空则处理更新订单相关信息
        if (executionResultResDTO != null) {
            ordersHandler.refundOrder(ordersRefund, executionResultResDTO);
        }
    }

    @Resource
    private OrdersMapper ordersMapper;

    //更新订单退款状态
    @Transactional
    public void refundOrder(OrdersRefund ordersRefund, ExecutionResultResDTO executionResultResDTO) {
       //初始退款状态为退款中
        int refundStatus = OrderRefundStatusEnum.REFUNDING.getStatus();
        if (executionResultResDTO.getRefundStatus() == OrderRefundStatusEnum.REFUND_SUCCESS.getStatus()) {
            refundStatus = OrderRefundStatusEnum.REFUND_SUCCESS.getStatus();
        } else if (executionResultResDTO.getRefundStatus() == OrderRefundStatusEnum.REFUND_FAIL.getStatus()) {
            refundStatus = OrderRefundStatusEnum.REFUND_FAIL.getStatus();
        }
        //如果为退款中则取消操作
        if (OrderRefundStatusEnum.REFUNDING.getStatus()==executionResultResDTO.getRefundStatus()) {
            return;
        }
        //更新退款状态
        LambdaUpdateWrapper<Orders> lambdaUpdateWrapper = new LambdaUpdateWrapper<Orders>()
                .eq(Orders::getId, ordersRefund.getId())
                .eq(Orders::getRefundStatus, OrderRefundStatusEnum.REFUNDING.getStatus())
                .set(Orders::getRefundStatus, refundStatus)
                .set(ObjectUtil.isNotNull(executionResultResDTO.getRefundId()), Orders::getRefundId, executionResultResDTO.getRefundId())
                .set(ObjectUtil.isNotNull(executionResultResDTO.getRefundNo()), Orders::getRefundNo, executionResultResDTO.getRefundNo());
        int update = ordersMapper.update(null, lambdaUpdateWrapper);
        if(update>0){
            //非退款中状态，删除申请退款记录，删除后定时任务不再扫描
            ordersRefundService.removeById(ordersRefund.getId());
        }

    }
}
