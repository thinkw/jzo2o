package com.jzo2o.orders.dispatch.strategys;

import com.jzo2o.common.utils.CollUtils;
import com.jzo2o.orders.dispatch.model.dto.ServeProviderDTO;

import java.util.List;

/**
 * @author Mr.M
 * @version 1.0
 * @description TODO
 * @date 2024/11/20 15:22
 */
public class LeastAcceptOrderStrategyImpl implements IProcessStrategy {

    private final IProcessRule processRule;
    public LeastAcceptOrderStrategyImpl() {
        //组成责任链
        IProcessRule scoreRule = new ScoreRule(null);
        //最少接单数
        IProcessRule leastAcceptNumRule = new AcceptNumRule(scoreRule);
        //获取责任链的第一个节点
        this.processRule = leastAcceptNumRule;
    }

    @Override
    public ServeProviderDTO getPrecedenceServeProvider(List<ServeProviderDTO> serveProviderDTOS) {
        // 1.判空
        if (CollUtils.isEmpty(serveProviderDTOS)) {
            return null;
        }

        // 2.根据优先级获取高优先级别的
        serveProviderDTOS = processRule.filter(serveProviderDTOS);

        // 3.数据返回
        // 3.1.唯一高优先级直接返回
        int size = 1;
        if ((size = CollUtils.size(serveProviderDTOS)) == 1) {
            return serveProviderDTOS.get(0);
        }
        // 3.2.多个高优先级随即将返回
        int randomIndex = (int) (Math.random() * size);
        return serveProviderDTOS.get(randomIndex);
    }

}
