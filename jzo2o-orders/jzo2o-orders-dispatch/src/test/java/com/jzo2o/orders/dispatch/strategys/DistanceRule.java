package com.jzo2o.orders.dispatch.strategys;

import com.jzo2o.common.utils.CollUtils;
import com.jzo2o.orders.dispatch.model.dto.ServeProviderDTO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Mr.M
 * @version 1.0
 * @description 距离优先
 * @date 2024/11/20 14:36
 */
public class DistanceRule implements IProcessRule {
    //下一个节点
    private IProcessRule next;

    public DistanceRule(IProcessRule next) {
        this.next = next;
    }
    public List<ServeProviderDTO> doFilter(List<ServeProviderDTO> serveProviderDTOS){
         //根据距离排序,按升序排序
        serveProviderDTOS.sort((o1, o2) -> {
            return o1.getAcceptanceDistance() - o2.getAcceptanceDistance();
        });

        //取出第一个
        ServeProviderDTO serveProviderDTO = serveProviderDTOS.get(0);
        //接单距离
        Integer acceptanceDistance = serveProviderDTO.getAcceptanceDistance();
        //取出距离最优先的人
        List<ServeProviderDTO> collect = serveProviderDTOS.stream().filter(source -> source.getAcceptanceDistance() == acceptanceDistance).collect(Collectors.toList());
        return collect;
    }

     @Override
    public List<ServeProviderDTO> filter(List<ServeProviderDTO> serveProviderDTOS) {
        //如果list为空或者元素数量小于等于1，直接返回
        if (CollUtils.isEmpty(serveProviderDTOS) || CollUtils.size(serveProviderDTOS) <= 1) {
            return serveProviderDTOS;
        }
        List<ServeProviderDTO> collect = doFilter(serveProviderDTOS);
        //如果集合只有一个，直接返回，否则继续向下调用
        if (CollUtils.size(collect) == 1) {
            return collect;
        }
        if (next != null) {
            return next.filter(collect);
        }
        return collect;
    }

    @Override
    public IProcessRule next() {
        return next;
    }


}
