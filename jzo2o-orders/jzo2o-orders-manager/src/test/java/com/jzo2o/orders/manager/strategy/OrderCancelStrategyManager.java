package com.jzo2o.orders.manager.strategy;

import cn.hutool.extra.spring.SpringUtil;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Mr.M
 * @version 1.0
 * @description TODO
 * @date 2024/11/11 7:42
 */
@Component
@Slf4j
public class OrderCancelStrategyManager implements OrderCancelStrategy {

    @Resource
    private IOrdersManagerService ordersManagerService;

    //将所有策略封装到map中
    private final Map<String, OrderCancelStrategy> orderCancelStrategyMap = new HashMap<>();

    //初始化orderCancelStrategyMap
    @PostConstruct
    public void init() {
        //使用工具从spring容器拿到所有实现类
        Map<String, OrderCancelStrategy> strategies = SpringUtil.getBeansOfType(OrderCancelStrategy.class);
        orderCancelStrategyMap.putAll(strategies);
    }

    @Override
    public void cancel(OrderCancelDTO orderCancelDTO) {
        //查询订单
        Orders orders = ordersManagerService.getById(orderCancelDTO.getId());
        //取得当前订单状态的策略
        OrderCancelStrategy orderCancelStrategy = getStrategy(orderCancelDTO.getCurrentUserType(), orders.getOrdersStatus());
        //如果为空、
        if (orderCancelStrategy == null) {
            log.warn("订单取消策略不存在，userType:{},orderStatus:{}", orderCancelDTO.getCurrentUserType(), orders.getOrdersStatus());
            throw new RuntimeException("不被许可的操作");
        }
        orderCancelDTO.setId(orders.getId());
        orderCancelDTO.setCurrentUserId(orders.getUserId());
        orderCancelDTO.setServeStartTime(orders.getServeStartTime());
        orderCancelDTO.setCityCode(orders.getCityCode());
        orderCancelDTO.setRealPayAmount(orders.getRealPayAmount());
        orderCancelDTO.setTradingOrderNo(orders.getTradingOrderNo());
        orderCancelStrategy.cancel(orderCancelDTO);
    }

    public OrderCancelStrategy getStrategy(int userType, int orderStatus) {
        String key = userType + ":" + OrderStatusEnum.codeOf(orderStatus).getCode();
        OrderCancelStrategy orderCancelStrategy = orderCancelStrategyMap.get(key);
        return orderCancelStrategy;
    }
}
