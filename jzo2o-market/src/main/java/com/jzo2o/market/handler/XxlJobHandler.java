package com.jzo2o.market.handler;

import com.jzo2o.market.service.IActivityService;
import com.jzo2o.market.service.ICouponService;
import com.jzo2o.redis.annotations.Lock;
import com.jzo2o.redis.constants.RedisSyncQueueConstants;
import com.jzo2o.redis.sync.SyncManager;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.jzo2o.market.constants.RedisConstants.Formatter.*;
import static com.jzo2o.market.constants.RedisConstants.RedisKey.COUPON_SEIZE_SYNC_QUEUE_NAME;

@Component
public class XxlJobHandler {

    @Resource
    private SyncManager syncManager;

    @Resource
    private IActivityService activityService;

    @Resource
    private ICouponService couponService;

    @Resource
    private IssuedCouponHandlerJob issuedCouponHandlerJob;

    @Resource
    private SeizeCouponHandlerJob seizeCouponHandlerJob;

    /**
     * 活动预热每分钟执行一次
     *
     */
    @XxlJob("activityPreheat")
    public void activityPreHeat() {
        activityService.preHeat();
    }


    /**
     * 活动状态修改，
     * 1.活动进行中状态修改
     * 2.活动已失效状态修改
     * 1分钟一次
     */
    @XxlJob("updateActivityStatus")
    public void updateActivitySatus(){
        activityService.updateStatus();
    }
    /**
     * 活动结束 处理任务 一天执行一次
     * 1.删除结束活动的库存
     * 2.删除结束活动的抢券列表
     *
     */
    @XxlJob("activityFinish")
    @Lock(formatter = ACTIVITY_FINISHED, startDog = true)
    public void activityFinished() {
        activityService.activityFinished();
    }

    @XxlJob("processExpireCoupon")
    public void processExpireCoupon() {
        couponService.processExpireCoupon();
    }

    /**
     * 自动发放优惠券
     */
    @XxlJob("issueCouponJob")
    public void issueCouponJob() {
        issuedCouponHandlerJob.start();
    }

        /**
     * 抢券同步队列
     * 5s一次
     */
    @XxlJob("seizeCouponSyncJob")
    public void seizeCouponSyncJob() {
        seizeCouponHandlerJob.start();
    }


}
