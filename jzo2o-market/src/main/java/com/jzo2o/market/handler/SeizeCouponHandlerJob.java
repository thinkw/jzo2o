package com.jzo2o.market.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jzo2o.market.model.domain.Activity;
import com.jzo2o.market.service.IActivityService;
import com.jzo2o.market.service.ICouponIssueService;
import com.jzo2o.market.service.ICouponService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author Mr.M
 * @version 1.0
 * @description 自动发放优惠券任务
 * @date 2024/9/23 19:49
 */
@Slf4j
@Component
public class SeizeCouponHandlerJob {
    //定义线程池
    private static ThreadPoolExecutor threadPoolExecutor;

    @Resource
    private ICouponService couponService;

    @Resource
    private RedisTemplate redisTemplate;

    @Resource
    private RedissonClient redissonClient;

    static {
        threadPoolExecutor = new ThreadPoolExecutor(10, 10, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100));
    }


    public void start() {
        log.info("自动发放优惠券任务开始");
        //启动10个线程
        for (int i = 0; i < 10; i++) {
            String hashKey = String.format("QUEUE:COUPON:SEIZE:SYNC:{%s}",i);
            //String hashKey, ICouponService couponService, RedisTemplate redisTemplate, RedissonClient redissonClient
            threadPoolExecutor.execute(new SeizeCouponHandler(hashKey, couponService, redisTemplate, redissonClient));
        }
    }
}
