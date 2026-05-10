package com.jzo2o.orders.manager.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.db.DbRuntimeException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.customer.AddressBookApi;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import com.jzo2o.api.foundations.ServeApi;
import com.jzo2o.api.foundations.dto.response.ServeAggregationResDTO;
import com.jzo2o.api.market.CouponApi;
import com.jzo2o.api.market.dto.request.CouponUseReqDTO;
import com.jzo2o.api.market.dto.response.AvailableCouponsResDTO;
import com.jzo2o.api.market.dto.response.CouponUseResDTO;
import com.jzo2o.api.trade.NativePayApi;
import com.jzo2o.api.trade.TradingApi;
import com.jzo2o.api.trade.dto.request.NativePayReqDTO;
import com.jzo2o.api.trade.dto.response.NativePayResDTO;
import com.jzo2o.api.trade.dto.response.TradingResDTO;
import com.jzo2o.api.trade.enums.PayChannelEnum;
import com.jzo2o.api.trade.enums.TradingStateEnum;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.common.utils.DateUtils;
import com.jzo2o.common.utils.IdUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.common.utils.UserContext;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusChangeEventEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.service.IOrdersDiversionCommonService;
import com.jzo2o.orders.manager.model.dto.request.OrdersPayReqDTO;
import com.jzo2o.orders.manager.model.dto.request.PlaceOrderReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.model.dto.response.PlaceOrderResDTO;
import com.jzo2o.orders.manager.porperties.TradeProperties;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.redis.annotations.Lock;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.jzo2o.orders.base.constants.RedisConstants.Lock.ORDERS_SHARD_KEY_ID_GENERATOR;
import static com.jzo2o.orders.base.constants.RedisConstants.Lock.ORDERS_SHARD_KEY_ID_LOCK;

/**
 * <p>
 * 下单服务类
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
@Slf4j
@Service
public class OrdersCreateServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersCreateService {

    @Resource
    private RedisTemplate<String, Long> redisTemplate;

    //注入地址簿api
    @Resource
    private AddressBookApi addressBookApi;

    //注入serveApi
    @Resource
    private ServeApi serveApi;

    @Resource
    private CouponApi couponApi;

    @Resource
    private OrdersCreateServiceImpl owner;

    @Resource
    private IOrdersDiversionCommonService ordersDiversionCommonService;
    @Value("${jzo2o.openPay}")
    private Boolean openPay;
    @Override
    public PlaceOrderResDTO placeOrder(PlaceOrderReqDTO placeOrderReqDTO) {
        Long userId = UserContext.currentUserId();
        return owner.placeOrder(userId, placeOrderReqDTO);
    }

    @Override
    public List<AvailableCouponsResDTO> getAvailableCoupons(Long serveId, Integer purNum) {
        //当前用户
        Long userId = UserContext.currentUserId();
        //获取serve信息
        ServeAggregationResDTO serveAggregationResDTO = serveApi.findById(serveId);
        //获取价格
        BigDecimal price = serveAggregationResDTO.getPrice();
        //计算总价格
        BigDecimal totalPrice = price.multiply(new BigDecimal(purNum));
        //获取可用优惠券
        List<AvailableCouponsResDTO> available = couponApi.getAvailable(userId, totalPrice);
        return available;
    }

    @Override
    public List<Orders> queryOverTimePayOrdersListByCount(Integer count) {
        //查询订单状态为待支付，超时时间在当前时间之后的订单，查询count条，且只查询订单id和用户id字段
        List<Orders> list = lambdaQuery()
                .eq(Orders::getOrdersStatus, OrderStatusEnum.NO_PAY.getStatus())
                .lt(Orders::getOverTime, LocalDateTime.now())
                .last("limit " + count)
                .select(Orders::getId, Orders::getUserId)
                .list();
        return list;
    }

    @Resource
    private TradeProperties tradeProperties;

    @Resource
    private NativePayApi nativePayApi;

    @Override
    public OrdersPayResDTO pay(Long id, OrdersPayReqDTO ordersPayReqDTO) {
        //查询订单
        Orders orders = getById(id);
        //如果订单不存在抛出异常
        if (ObjectUtils.isNull(orders)) {
            throw new CommonException("订单不存在");
        }
        //如果订单的支付状态为成功则直接返回
        if (OrderPayStatusEnum.PAY_SUCCESS.getStatus() == orders.getPayStatus()) {
            OrdersPayResDTO ordersPayResDTO = new OrdersPayResDTO();
            ordersPayResDTO.setPayStatus(orders.getPayStatus());
            ordersPayResDTO.setProductOrderNo(orders.getId());
            ordersPayResDTO.setTradingOrderNo(orders.getTradingOrderNo());
            ordersPayResDTO.setTradingChannel(orders.getTradingChannel());
            return ordersPayResDTO;
        } else {
            NativePayResDTO nativePayResDTO = generateQrCode(orders, ordersPayReqDTO.getTradingChannel());
            OrdersPayResDTO ordersPayResDTO = BeanUtil.toBean(nativePayResDTO, OrdersPayResDTO.class);
            return ordersPayResDTO;
        }

    }

    @Resource
    private TradingApi tradingApi;

    @Override
    public OrdersPayResDTO getPayResultFromTradServer(Long id) {
        //查询订单
        Orders orders = getById(id);
        //订单不存在
        if (ObjectUtils.isNull(orders)) {
            throw new CommonException("订单不存在");
        }
        //支付结果
        Integer payStatus = orders.getPayStatus();
        //如果订单未支付则远程调用支付服务查询支付结果
        if (OrderPayStatusEnum.NO_PAY.getStatus() == payStatus && ObjectUtils.isNotEmpty(orders.getTradingOrderNo())) {
            //远程调用支付服务查询支付结果
            TradingResDTO tradingResDTO = tradingApi.findTradResultByTradingOrderNo(orders.getTradingOrderNo());
            //如果支付成功则更新订单表的支付状态为已支付
            if (TradingStateEnum.YJS.getCode() == tradingResDTO.getTradingState().getCode()) {
                TradeStatusMsg tradeStatusMsg = new TradeStatusMsg();
                //交易单号
                tradeStatusMsg.setTradingOrderNo(tradingResDTO.getTradingOrderNo());
                //订单号
                tradeStatusMsg.setProductOrderNo(String.valueOf(tradingResDTO.getProductOrderNo()));
                //第三方支付交易号
                tradeStatusMsg.setTransactionId(tradingResDTO.getTransactionId());
                //支付渠道
                tradeStatusMsg.setTradingChannel(tradingResDTO.getTradingChannel());
                //支付状态
                tradeStatusMsg.setStatusCode(TradingStateEnum.YJS.getCode());
                owner.paySuccess(tradeStatusMsg);
                //返回支付结果
                OrdersPayResDTO ordersPayResDTO = BeanUtil.toBean(tradeStatusMsg, OrdersPayResDTO.class);
                ordersPayResDTO.setPayStatus(OrderPayStatusEnum.PAY_SUCCESS.getStatus());
                return ordersPayResDTO;
            }

        }
        OrdersPayResDTO ordersPayResDTO = new OrdersPayResDTO();
        ordersPayResDTO.setPayStatus(orders.getPayStatus());
        ordersPayResDTO.setProductOrderNo(orders.getId());
        ordersPayResDTO.setTradingOrderNo(orders.getTradingOrderNo());
        ordersPayResDTO.setTradingChannel(orders.getTradingChannel());
        return ordersPayResDTO;
    }

    //支付成功方法
    public void paySuccess(TradeStatusMsg tradeStatusMsg) {
        //订单号
        Long productOrderNo = Long.valueOf(tradeStatusMsg.getProductOrderNo());
        //查询订单
        Orders orders = getById(productOrderNo);
        //订单不存在
        if (ObjectUtils.isNull(orders)) {
            throw new CommonException("订单不存在");
        }
        //订单的支付状态如果不是待支付则不处理
        if (OrderPayStatusEnum.NO_PAY.getStatus() != orders.getPayStatus()) {
            return;
        }
        //订单状态如果不是待支付不处理
        if (OrderStatusEnum.NO_PAY.getStatus() != orders.getOrdersStatus()) {
            return;
        }
        //如果缺少第三方支付单号不处理
        if (ObjectUtils.isNull(tradeStatusMsg.getTransactionId())) {
            log.error("支付成功事件处理失败，缺少第三方支付单号，订单号：{}", orders.getId());
            return;
        }
        //更新订单的支付状态及第三方交易单号等信息
//        boolean update = lambdaUpdate()
//                .eq(Orders::getId, orders.getId())//订单号
//                .eq(Orders::getPayStatus, OrderPayStatusEnum.NO_PAY.getStatus())//原支付状态
//                .eq(Orders::getOrdersStatus, OrderStatusEnum.NO_PAY.getStatus())//原订单状态
//                .set(Orders::getPayTime, LocalDateTime.now())//支付时间
//                .set(Orders::getTradingOrderNo, tradeStatusMsg.getTradingOrderNo())//交易单号
//                .set(Orders::getTradingChannel, tradeStatusMsg.getTradingChannel())//支付渠道
//                .set(Orders::getTransactionId, tradeStatusMsg.getTransactionId())//第三方支付交易号
//                .set(Orders::getPayStatus, OrderPayStatusEnum.PAY_SUCCESS.getStatus())//支付状态
//                .set(Orders::getOrdersStatus, OrderStatusEnum.DISPATCHING.getStatus())//订单状态更新为派单中
//                .update();
//        if(!update){
//            log.info("更新订单:{}支付成功失败", orders.getId());
//            throw new CommonException("更新订单"+orders.getId()+"支付成功失败");
//        }
        // 修改订单状态和支付状态
        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
                .payTime(LocalDateTime.now())
                .tradingOrderNo(tradeStatusMsg.getTradingOrderNo())
                .tradingChannel(tradeStatusMsg.getTradingChannel())
                .thirdOrderId(tradeStatusMsg.getTransactionId())
                .build();
        orderStateMachine.changeStatus(orders.getUserId(), String.valueOf(orders.getId()), OrderStatusChangeEventEnum.PAYED, orderSnapshotDTO);
        // 订单分流
        ordersDiversionCommonService.diversion(orders);
    }

    //生成二维码
    public NativePayResDTO generateQrCode(Orders orders, PayChannelEnum tradingChannel) {
        NativePayReqDTO nativePayReqDTO = new NativePayReqDTO();

        //根据支付渠道从配置文件获取商户号
        if (PayChannelEnum.ALI_PAY.getValue().equals(tradingChannel.getValue())) {
            nativePayReqDTO.setEnterpriseId(tradeProperties.getAliEnterpriseId());
        } else {
            nativePayReqDTO.setEnterpriseId(tradeProperties.getWechatEnterpriseId());
        }
        //业务系统标识
        nativePayReqDTO.setProductAppId("jzo2o.orders");
        //家政订单号
        nativePayReqDTO.setProductOrderNo(orders.getId());
        //支付渠道
        nativePayReqDTO.setTradingChannel(tradingChannel);
        // 支付金额 是订单的实付金额
        nativePayReqDTO.setTradingAmount(orders.getRealPayAmount());
        //备注信息
        nativePayReqDTO.setMemo(orders.getServeItemName());
        //判断是否切换支付渠道
        if (ObjectUtils.isNotEmpty(orders.getTradingChannel())) {
            if (!tradingChannel.getValue().equals(orders.getTradingChannel())) {
                nativePayReqDTO.setChangeChannel(true);
            }
        }
        //请求支付服务生成二维码
        NativePayResDTO downLineTrading = nativePayApi.createDownLineTrading(nativePayReqDTO);
        //如果生成二维码成功则将返回的交易号码保存到订单表中
        if (ObjectUtils.isNotEmpty(downLineTrading)) {
            boolean update = lambdaUpdate()
                    .set(Orders::getTradingOrderNo, downLineTrading.getTradingOrderNo())
                    .set(Orders::getTradingChannel, downLineTrading.getTradingChannel())
                    .eq(Orders::getId, downLineTrading.getProductOrderNo())
                    .update();
            if (!update) {
                throw new CommonException("订单支付信息保存失败");
            }
        }
        return downLineTrading;

    }

    @Lock(formatter = "ORDERS:CREATE:LOCK#{userId}:#{placeOrderReqDTO.serveId}", time = 30, waitTime = 1, unlock = false)
    public PlaceOrderResDTO placeOrder(Long userId, PlaceOrderReqDTO placeOrderReqDTO) {
        Long addressBookId = placeOrderReqDTO.getAddressBookId();
        //下单人信息，获取地址簿，调用jzo2o-customer服务获取
        AddressBookResDTO addressBookResDTO = addressBookApi.detail(addressBookId);

        //服务相关信息,调用jzo2o-foundations获取
        ServeAggregationResDTO serveAggregationResDTO = serveApi.findById(placeOrderReqDTO.getServeId());
        //当前用户id
//        Long userId = UserContext.currentUserId();
        Orders orders = new Orders();
        //生成订单号
        Long orderId = generateOrderId();
        orders.setId(orderId);
        //用户id
        orders.setUserId(userId);
        //服务类型id
        orders.setServeTypeId(serveAggregationResDTO.getServeTypeId());
        //服务类型名称
        orders.setServeTypeName(serveAggregationResDTO.getServeTypeName());
        //服务项名称
        orders.setServeItemName(serveAggregationResDTO.getServeItemName());
        //服务项id
        orders.setServeItemId(serveAggregationResDTO.getServeItemId());
        //服务项图片
        orders.setServeItemImg(serveAggregationResDTO.getServeItemImg());
        //单位
        orders.setUnit(serveAggregationResDTO.getUnit());
        //serve_id
        orders.setServeId(placeOrderReqDTO.getServeId());
        //订单状态默认为待支付
        orders.setOrdersStatus(OrderStatusEnum.NO_PAY.getStatus());
        //支付状态
        orders.setPayStatus(OrderPayStatusEnum.NO_PAY.getStatus());
        //单价
        orders.setPrice(serveAggregationResDTO.getPrice());
        //购买数量
        orders.setPurNum(placeOrderReqDTO.getPurNum());
        //订单总金额
        orders.setTotalAmount(serveAggregationResDTO.getPrice().multiply(new BigDecimal(placeOrderReqDTO.getPurNum())));
        //todo 优惠金额为暂时为0
        orders.setDiscountAmount(new BigDecimal(0));
        //实际支付金额
        orders.setRealPayAmount(orders.getTotalAmount().subtract(orders.getDiscountAmount()));
        //city_code
        orders.setCityCode(serveAggregationResDTO.getCityCode());
        //服务地址
        String address = addressBookResDTO.getProvince() + addressBookResDTO.getCity() + addressBookResDTO.getAddress();
        orders.setServeAddress(address);
        //联系电话
        orders.setContactsPhone(addressBookResDTO.getPhone());
        //联系人
        orders.setContactsName(addressBookResDTO.getName());
        //服务开始时间
        orders.setServeStartTime(placeOrderReqDTO.getServeStartTime());
        //经纬度
        orders.setLon(addressBookResDTO.getLon());
        orders.setLat(addressBookResDTO.getLat());

        //排序字段 服务开始时间转发毫秒加订单号后5位
        long sortBy = DateUtils.toEpochMilli(orders.getServeStartTime()) + orderId % 100000;
        orders.setSortBy(sortBy);
        //支付超时时间 定为30分钟后，极端情况下可能在到达超时用户进行支付，这里超时时间多出5分钟，定时任务根据此时间查询超时订单并进行取消
        orders.setOverTime(LocalDateTime.now().plusMinutes(35));
        //保存订单
        //如果有优惠券则执行优惠券下单
        if (placeOrderReqDTO.getCouponId() != null) {
            owner.addWithCoupon(orders, placeOrderReqDTO.getCouponId());
        } else {
            owner.add(orders);
        }
        if (Boolean.FALSE.equals(openPay)) {
            TradeStatusMsg msg = TradeStatusMsg.builder()
                    .productOrderNo(String.valueOf(orders.getId()))
                    .tradingChannel("WECHAT_PAY")
                    .statusCode(TradingStateEnum.YJS.getCode())
                    .tradingOrderNo(IdUtil.getSnowflakeNextId())
                    .transactionId(IdUtils.getSnowflakeNextIdStr())
                    .build();
            owner.paySuccess(msg);
        }
        return new PlaceOrderResDTO(orderId);

    }

    //使用优惠券下单，这里有分布式事务
    @GlobalTransactional
    public void addWithCoupon(Orders orders, Long couponId) {
        CouponUseReqDTO couponUseReqDTO = new CouponUseReqDTO();
        couponUseReqDTO.setId(couponId);
        couponUseReqDTO.setOrdersId(orders.getId());
        couponUseReqDTO.setTotalAmount(orders.getTotalAmount());
        CouponUseResDTO use = couponApi.use(couponUseReqDTO);
        //设置优惠券金额
        orders.setDiscountAmount(use.getDiscountAmount());
        //计算实付金额
        orders.setRealPayAmount(orders.getTotalAmount().subtract(orders.getDiscountAmount()));
        owner.add(orders);
        //模拟异常
//        throw new RuntimeException("模拟异常");
    }

    @Resource
    private OrderStateMachine orderStateMachine;

    @Transactional(rollbackFor = Exception.class)
    public void add(Orders orders) {
        boolean save = this.save(orders);
        if (!save) {
            throw new DbRuntimeException("下单失败");
        }
        //构建快照对象
        OrderSnapshotDTO orderSnapshotDTO = BeanUtil.toBean(baseMapper.selectById(orders.getId()), OrderSnapshotDTO.class);
        //状态启动
        orderStateMachine.start(orders.getUserId(), orders.getId().toString(), orderSnapshotDTO);
    }

    /**
     * 订单号生成方法
     */
    private Long generateOrderId() {
        //通过redis自增方法生成一个序号
        Long id = redisTemplate.opsForValue().increment(ORDERS_SHARD_KEY_ID_GENERATOR, 1);

        Long date = DateUtils.getFormatDate(LocalDateTime.now(), "yyMMdd");

        //拼接订单号
        Long orderId = date * 10000000000000L + id;

        return orderId;
    }
}
