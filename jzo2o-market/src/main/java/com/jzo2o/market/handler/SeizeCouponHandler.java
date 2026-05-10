package com.jzo2o.market.handler;

import com.jzo2o.market.service.ICouponIssueService;
import com.jzo2o.market.service.ICouponService;
import com.jzo2o.redis.model.SyncMessage;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author Mr.M
 * @version 1.0
 * @description 抢券结果同步处理器
 * @date 2024/9/23 18:43
 */
@Slf4j
public class SeizeCouponHandler implements Runnable {

    //抢券结果同步的hash的大key
    public String hashKey;
    //couponService
    private ICouponService couponService;

    //分布式锁
    private RedissonClient redissonClient;
    //分布式锁
    private RedisTemplate redisTemplate;


    //构造方法
    public SeizeCouponHandler(String hashKey, ICouponService couponService, RedisTemplate redisTemplate, RedissonClient redissonClient) {
        this.hashKey = hashKey;
        this.couponService = couponService;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
    }

    @Override
    public void run() {
        //锁名称
       String lockKey = "LOCK:" + hashKey;
        //锁对象
        RLock lock = redissonClient.getLock(lockKey);

        try {
            //获取分布式锁
            boolean tryLock = lock.tryLock(1, -1, TimeUnit.SECONDS);
            if (!tryLock) {
                log.warn("抢券结果同步任务未获取到锁，退出");
                return;
            }
            ScanOptions scanOptions = ScanOptions.scanOptions().count(100).build();
            Cursor<Map.Entry<String, Object>> cursor = null;
            try {
                while (true) {
                    //通过游标从hash表的数据
                    cursor = redisTemplate.opsForHash().scan(hashKey, scanOptions);
                    //如果数据游标已经没有数据，退出循环
                    if (!cursor.hasNext()) {
                        break;
                    }
                    //遍历数据
                    List<SyncMessage<Object>> collect = cursor.stream().map(data -> {
                        SyncMessage<Object> build = SyncMessage.builder().key(data.getKey()).value(data.getValue()).build();
                        return build;
                    }).collect(Collectors.toList());
                    //遍历数据，一条一条处理，向coupon表插入，扣减库存，删除hash的那一条记录
                    collect.forEach(syncMessage -> {
                        //活动id
                        Long activityId = Long.parseLong(syncMessage.getValue().toString());
                        //用户id
                        Long userId = Long.parseLong(syncMessage.getKey());
                        //调用couponService向coupon插入数据,扣减库存
                        couponService.seizeCouponSync(activityId, userId);
                        //删除hash记录
                        redisTemplate.opsForHash().delete(hashKey, syncMessage.getKey());

                    });
                }


            } finally {
                //释放锁
                lock.unlock();
                //关闭游标
                if (cursor != null) {
                    cursor.close();
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }


    }
}
