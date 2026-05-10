package com.jzo2o.orders.dispatch.strategys;

import com.jzo2o.orders.dispatch.model.dto.ServeProviderDTO;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * @author Mr.M
 * @version 1.0
 * @description 测试责任链
 * @date 2024/11/20 14:52
 */
public class RuleHandlerTest {
    public static void main(String[] args) {
        //缓存责任链
        //按距离优先，先按距离排序，如果距离相同的大于一个，再按接单数排序
        //接单数优先
        IProcessRule acceptNumRule = new AcceptNumRule(null);
        IProcessRule processRule = new DistanceRule(acceptNumRule);

        //评分优先
//        IProcessRule distanceRule = new DistanceRule(null);
//        IProcessRule acceptNumRule = new AcceptNumRule(distanceRule);
//        IProcessRule processRule = new ScoreRule(acceptNumRule);


        //造服务人员列表
        List<ServeProviderDTO> serveProviderDTOS = Arrays.asList(
                //1号 接单数最少
                ServeProviderDTO.builder().id(1L).acceptanceNum(0).acceptanceDistance(30).evaluationScore(50).build(),
                //2号 得分最高
                ServeProviderDTO.builder().id(2L).acceptanceNum(2).acceptanceDistance(10).evaluationScore(100).build(),
                //3号 得分最高
                ServeProviderDTO.builder().id(3L).acceptanceNum(2).acceptanceDistance(5).evaluationScore(100).build(),
                //4号 距离最近
                ServeProviderDTO.builder().id(4L).acceptanceNum(2).acceptanceDistance(5).evaluationScore(50).build(),
                //4号 距离最近
                ServeProviderDTO.builder().id(5L).acceptanceNum(1).acceptanceDistance(5).evaluationScore(50).build()
        );
        List<ServeProviderDTO> filter = processRule.filter(serveProviderDTOS);

        ServeProviderDTO serveProviderDTO = null;
        //如果list元素个数为1此时直接取出，要派单的对象
        if (filter.size() == 1) {
            serveProviderDTO = filter.get(0);
        } else {
            //否则需要随机数一个
            serveProviderDTO = filter.get(new Random().nextInt(filter.size()));
        }
        System.out.println(serveProviderDTO);

    }
}
