package com.jzo2o.orders.manager.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.db.DbRuntimeException;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.market.CouponApi;
import com.jzo2o.api.market.dto.request.CouponUseBackReqDTO;
import com.jzo2o.api.orders.dto.response.OrderResDTO;
import com.jzo2o.api.orders.dto.response.OrderSimpleResDTO;
import com.jzo2o.api.trade.enums.RefundStatusEnum;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.enums.EnableStatusEnum;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.utils.CollUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
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
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.jzo2o.orders.manager.strategy.OrderCancelStrategyManager;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.jzo2o.orders.base.constants.FieldConstants.SORT_BY;

/**
 * <p>
 * 订单表 服务实现类
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
@Slf4j
@Service
public class OrdersManagerServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersManagerService {

    //注入订单状态机
    @Resource
    private OrderStateMachine orderStateMachine;

    @Override
    public List<Orders> batchQuery(List<Long> ids) {
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery().in(Orders::getId, ids);
        //添加排序字段
        queryWrapper.orderByDesc(Orders::getSortBy);
        return baseMapper.selectList(queryWrapper);
    }
    @Override
    public List<Orders> batchQuery(List<Long> ids,Long userId) {
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery().in(Orders::getId, ids).eq(Orders::getUserId, userId);
        return baseMapper.selectList(queryWrapper);
    }

    @Override
    public Orders queryById(Long id) {
        return baseMapper.selectById(id);
    }

    /**
     * 滚动分页查询
     *
     * @param currentUserId 当前用户id
     * @param ordersStatus  订单状态，0：待支付，100：派单中，200：待服务，300：服务中，400：待评价，500：订单完成，600：已取消，700：已关闭
     * @param sortBy        排序字段
     * @return 订单列表
     */
    @Override
    public List<OrderSimpleResDTO> consumerQueryList(Long currentUserId, Integer ordersStatus, Long sortBy) {
        //1.构件查询条件
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery()
                .eq(ObjectUtils.isNotNull(ordersStatus), Orders::getOrdersStatus, ordersStatus)
                .lt(ObjectUtils.isNotNull(sortBy), Orders::getSortBy, sortBy)
                .eq(Orders::getUserId, currentUserId)
                .eq(Orders::getDisplay, EnableStatusEnum.ENABLE.getStatus())
                .select(Orders::getId);
        Page<Orders> queryPage = new Page<>();
        queryPage.addOrder(OrderItem.desc(SORT_BY));
        queryPage.setSearchCount(false);

        //2.查询订单列表
        Page<Orders> ordersPage = baseMapper.selectPage(queryPage, queryWrapper);
        if (CollUtils.isEmpty(ordersPage.getRecords())) {
            log.info("订单列表为空");
            return new ArrayList<>();
        }
        List<Orders> records = ordersPage.getRecords();
        //提取出订单id
        List<Long> ids = records.stream().map(Orders::getId).collect(Collectors.toList());
        List<Orders> orders = batchQuery(ids);
        List<OrderSimpleResDTO> orderSimpleResDTOS = BeanUtil.copyToList(orders, OrderSimpleResDTO.class);
        return orderSimpleResDTOS;

    }
    /**
     * 根据订单id查询
     *
     * @param id 订单id
     * @return 订单详情
     */
    @Override
    public OrderResDTO getDetail(Long id) {
//        Orders orders = queryById(id);
        //订单快照中查询订单
        String currentSnapshot = orderStateMachine.getCurrentSnapshotCache(String.valueOf(id));
        OrderSnapshotDTO orderSnapshotDTO = JSONUtil.toBean(currentSnapshot, OrderSnapshotDTO.class);

        //懒加载方式取消订单
        orderSnapshotDTO = canalIfPayOvertime(orderSnapshotDTO);
        OrderResDTO orderResDTO = BeanUtil.toBean(orderSnapshotDTO, OrderResDTO.class);
        return orderResDTO;
    }
    /**
     * 如果支付过期则取消订单
     * @param orderSnapshotDTO
     */
    private OrderSnapshotDTO canalIfPayOvertime(OrderSnapshotDTO orderSnapshotDTO){
        //订单到达超时时间则自动取消
        if(orderSnapshotDTO.getOrdersStatus()==OrderStatusEnum.NO_PAY.getStatus() && orderSnapshotDTO.getOverTime().isBefore(LocalDateTime.now())){
            //todo 查询最新支付状态，如果仍是未支付进行取消订单
            //取消订单
            OrderCancelDTO orderCancelDTO = new OrderCancelDTO();
            orderCancelDTO.setId(orderSnapshotDTO.getId());
            orderCancelDTO.setCurrentUserId(orderSnapshotDTO.getUserId());
            orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
            orderCancelDTO.setCancelReason("订单超时支付，自动取消");
            cancel(orderCancelDTO);
//            orders = getById(orders.getId());
            //查询订单快照
            String currentSnapshot = orderStateMachine.getCurrentSnapshotCache(String.valueOf(orderSnapshotDTO.getId()));
            orderSnapshotDTO = JSONUtil.toBean(currentSnapshot, OrderSnapshotDTO.class);
            return orderSnapshotDTO;
        }
        return orderSnapshotDTO;
    }
//    private Orders canalIfPayOvertime(Orders orders){
//        //订单到达超时时间则自动取消
//        if(orders.getOrdersStatus()==OrderStatusEnum.NO_PAY.getStatus() && orders.getOverTime().isBefore(LocalDateTime.now())){
//            //todo 查询最新支付状态，如果仍是未支付进行取消订单
//            //取消订单
//            OrderCancelDTO orderCancelDTO = new OrderCancelDTO();
//            orderCancelDTO.setId(orders.getId());
//            orderCancelDTO.setCurrentUserId(orders.getUserId());
//            orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
//            orderCancelDTO.setCancelReason("订单超时支付，自动取消");
//            cancel(orderCancelDTO);
//            orders = getById(orders.getId());
//        }
//        return orders;
//    }
    /**
     * 订单评价
     *
     * @param ordersId 订单id
     */
    @Override
    @Transactional
    public void evaluationOrder(Long ordersId) {
//        //查询订单详情
//        Orders orders = queryById(ordersId);
//
//        //构建订单快照
//        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
//                .evaluationTime(LocalDateTime.now())
//                .build();
//
//        //订单状态变更
//        orderStateMachine.changeStatus(orders.getUserId(), orders.getId().toString(), OrderStatusChangeEventEnum.EVALUATE, orderSnapshotDTO);
    }

    @Resource
    private CouponApi couponApi;

    @Resource
    private IOrdersCanceledService ordersCanceledService;

    @Resource
    private IOrdersCommonService ordersCommonService;

    @Resource
    private OrdersManagerServiceImpl owner;

//    @Override
//    public void cancel(OrderCancelDTO orderCancelDTO) {
//        //订单id
//        Long id = orderCancelDTO.getId();
//        //查询订单
//        Orders orders = queryById(id);
//        //如果订单为空则抛出异常
//        if (ObjectUtils.isNull(orders)) {
//            throw new CommonException("订单不存在");
//        }
//        //订单状态
//        Integer ordersStatus = orders.getOrdersStatus();
//        //填充数据
//        orderCancelDTO.setTradingOrderNo(orders.getTradingOrderNo());
//        orderCancelDTO.setRealPayAmount(orders.getRealPayAmount());
//        //订单状态为待支付时
//        if (OrderStatusEnum.NO_PAY.getStatus().equals(ordersStatus)) {
//           //取消待支付订单
//           //如果订单存在优惠金额并且优惠金额大于0
//            if (ObjectUtils.isNotNull(orders.getDiscountAmount()) && orders.getDiscountAmount().compareTo(new BigDecimal(0)) > 0) {
//                owner.cancelWithCoupon(orderCancelDTO);
//            }else{
//                //取消待支付订单
//                owner.cancelNoPayOrder(orderCancelDTO);
//            }
//        }else if(OrderStatusEnum.DISPATCHING.getStatus().equals(ordersStatus)){//派单中
//            if (ObjectUtils.isNotNull(orders.getDiscountAmount()) && orders.getDiscountAmount().compareTo(new BigDecimal(0)) > 0) {
//                owner.cancelWithCoupon(orderCancelDTO);
//            }else{
//                //取消派单中的订单
//                owner.cancelByDispatching(orderCancelDTO);
//            }
//        }else{
//            throw new CommonException("订单状态异常");
//        }
//    }
    @Override
    public void cancel(OrderCancelDTO orderCancelDTO) {
        //订单id
        Long id = orderCancelDTO.getId();
        //查询订单
        Orders orders = queryById(id);
        //如果订单为空则抛出异常
        if (ObjectUtils.isNull(orders)) {
            throw new CommonException("订单不存在");
        }
        //填充数据
        orderCancelDTO.setTradingOrderNo(orders.getTradingOrderNo());
        orderCancelDTO.setRealPayAmount(orders.getRealPayAmount());
        if (ObjectUtils.isNotNull(orders.getDiscountAmount()) && orders.getDiscountAmount().compareTo(new BigDecimal(0)) > 0) {
            owner.cancelWithCoupon(orderCancelDTO);
        }else{
            //取消待支付订单
            owner.cancelWithoutCoupon(orderCancelDTO);
        }
    }
    @Resource
    private OrderCancelStrategyManager orderCancelStrategyManager;
    //存在优惠券的情况取消订单，本方法是分布式事务
//    @GlobalTransactional
//    public void cancelWithCoupon(OrderCancelDTO orderCancelDTO) {
////        //查询订单信息
////        Orders orders = getById(orderCancelDTO.getId());
//        //退回优惠券
//        CouponUseBackReqDTO couponUseBackReqDTO = new CouponUseBackReqDTO();
//        couponUseBackReqDTO.setUserId(orderCancelDTO.getCurrentUserId());
//        couponUseBackReqDTO.setOrdersId(orderCancelDTO.getId());
//        couponApi.useBack(couponUseBackReqDTO);
//        //如果订单状态为待支付
//        if (OrderStatusEnum.NO_PAY.getStatus()==orders.getOrdersStatus()) {
//            //取消待支付订单
//            owner.cancelNoPayOrder(orderCancelDTO);
//        }else if(OrderStatusEnum.DISPATCHING.getStatus()==orders.getOrdersStatus()){
//            owner.cancelByDispatching(orderCancelDTO);
//        }else{
//            throw new CommonException("订单状态异常");
//        }
//    }
    @GlobalTransactional
    public void cancelWithCoupon(OrderCancelDTO orderCancelDTO) {
        //退回优惠券
        CouponUseBackReqDTO couponUseBackReqDTO = new CouponUseBackReqDTO();
        couponUseBackReqDTO.setUserId(orderCancelDTO.getCurrentUserId());
        couponUseBackReqDTO.setOrdersId(orderCancelDTO.getId());
        couponApi.useBack(couponUseBackReqDTO);
        //使用策略模式取消订单
        orderCancelStrategyManager.cancel(orderCancelDTO);
    }
    @Transactional
    public void cancelWithoutCoupon(OrderCancelDTO orderCancelDTO) {
        //使用策略模式取消订单
        orderCancelStrategyManager.cancel(orderCancelDTO);
    }
    /**
     * 取消待支付订单
     */
    @Transactional
    public void cancelNoPayOrder(OrderCancelDTO orderCancelDTO) {
       //保证取消订单记录
        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderCancelDTO, OrdersCanceled.class);
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);
        //更新订单状态
        OrderUpdateStatusDTO orderUpdateStatusDTO = new OrderUpdateStatusDTO();
        //订单id
        orderUpdateStatusDTO.setId(orderCancelDTO.getId());
        //原始订单状态
        orderUpdateStatusDTO.setOriginStatus(OrderStatusEnum.NO_PAY.getStatus());
        //目标状态
        orderUpdateStatusDTO.setTargetStatus(OrderStatusEnum.CANCELED.getStatus());
        ordersCommonService.updateStatus(orderUpdateStatusDTO);
    }

    @Resource
    private IOrdersRefundService ordersRefundService;
    /**
     * 取消派单中的订单
     */
    @Transactional
    public void cancelByDispatching(OrderCancelDTO orderCancelDTO) {
        //保存取消订单记录
        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderCancelDTO, OrdersCanceled.class);
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);
        //更新订单为关闭状态
        //更新订单状态
        OrderUpdateStatusDTO orderUpdateStatusDTO = new OrderUpdateStatusDTO();
        //订单id
        orderUpdateStatusDTO.setId(orderCancelDTO.getId());
        //原始订单状态
        orderUpdateStatusDTO.setOriginStatus(OrderStatusEnum.DISPATCHING.getStatus());
        //目标状态
        orderUpdateStatusDTO.setTargetStatus(OrderStatusEnum.CLOSED.getStatus());
        //退款状态更新为退款中
        orderUpdateStatusDTO.setRefundStatus(OrderRefundStatusEnum.REFUNDING.getStatus());
        Integer integer = ordersCommonService.updateStatus(orderUpdateStatusDTO);
        //更新失败
        if (integer <= 0) {
            throw new CommonException("更新订单状态异常");
        }

        //向退款记录表插入数据
        OrdersRefund ordersRefund = new OrdersRefund();
        ordersRefund.setId(orderCancelDTO.getId());
        ordersRefund.setTradingOrderNo(orderCancelDTO.getTradingOrderNo());
        ordersRefund.setRealPayAmount(orderCancelDTO.getRealPayAmount());
        ordersRefund.setCreateTime(LocalDateTime.now());
        ordersRefundService.save(ordersRefund);
    }

}
