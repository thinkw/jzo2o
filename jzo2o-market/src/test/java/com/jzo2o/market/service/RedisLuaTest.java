package com.jzo2o.market.service;

import com.jzo2o.redis.model.SyncMessage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Mr.M
 * @version 1.0
 * @description TODO
 * @date 2023/10/13 16:28
 */
@SpringBootTest
@Slf4j
public class RedisLuaTest {

    @Resource
    private RedisTemplate redisTemplate;

    @Resource
    private DefaultRedisScript<Integer> lua_test01;

    //测试执行lua脚本
    @Test
    public void test01() {

        Object execute = redisTemplate.execute(lua_test01, Arrays.asList("test_key02", "test_key03"), "field01", "aa", "field02", "bb");
        System.out.println(execute);

    }

    @Test
    void batchGet() {

        //hash的大key
        String bigKey = "QUEUE:COUPON:SEIZE:SYNC:{6}";

        ScanOptions scanOptions = ScanOptions.scanOptions().count(10).build();
        Cursor<Map.Entry<String, Object>> cursor = null;

        try {
            //使用游标的方式从hash中批量查询数据
            cursor = redisTemplate.opsForHash().scan(bigKey, scanOptions);
            //遍历数据
            List<SyncMessage<Object>> collect = cursor.stream().map(data -> {
                SyncMessage<Object> build = SyncMessage.builder().key(data.getKey()).value(data.getValue()).build();
                return build;
            }).collect(Collectors.toList());
            log.info("查询到数据：{}",collect);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //关闭游标
            if(cursor!=null){
                 cursor.close();
            }

        }

    }


}

