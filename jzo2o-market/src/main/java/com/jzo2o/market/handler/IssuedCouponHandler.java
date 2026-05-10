package com.jzo2o.market.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jzo2o.market.model.domain.CouponIssue;
import com.jzo2o.market.model.dto.request.CouponIssueReqDTO;
import com.jzo2o.market.service.ICouponIssueService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * @author Mr.M
 * @version 1.0
 * @description 自动发放优惠券处理器
 * 根据活动id从待发放记录表查找该活动的待发放记录，然后批量进行发放，一次拿出1000条进行发放。
 * @date 2024/9/23 18:43
 */
@Slf4j
public class IssuedCouponHandler implements Runnable {


    //优惠券发放service
    private ICouponIssueService couponIssueService;

    //分布式锁
    private RedissonClient redissonClient;

    //活动id
    private Long activityId;

    //构造方法
    public IssuedCouponHandler(Long activityId,ICouponIssueService couponIssueService,RedissonClient redissonClient) {
        this.activityId = activityId;
        this.couponIssueService = couponIssueService;
        this.redissonClient = redissonClient;
    }

    @Override
    public void run() {
        //分布式锁名称
        String lockKey = "activity:issued:lock:" + activityId;
        //获取分布式锁
        boolean tryLock = redissonClient.getLock(lockKey).tryLock();
        if (!tryLock) {
            log.warn("自动发放优惠券处理器未获取到锁，退出");
            return;
        }
        try {
            //批量发放优惠券
            couponIssueService.autoIssue(activityId);
        }finally {
            redissonClient.getLock(lockKey).unlock();
        }

    }
}
