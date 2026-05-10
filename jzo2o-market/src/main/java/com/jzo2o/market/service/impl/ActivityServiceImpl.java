package com.jzo2o.market.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.common.expcetions.BadRequestException;
import com.jzo2o.common.model.PageResult;
import com.jzo2o.common.utils.*;
import com.jzo2o.market.constants.TabTypeConstants;
import com.jzo2o.market.enums.ActivityStatusEnum;
import com.jzo2o.market.mapper.ActivityMapper;
import com.jzo2o.market.model.domain.Activity;
import com.jzo2o.market.model.dto.request.ActivityQueryForPageReqDTO;
import com.jzo2o.market.model.dto.request.ActivitySaveReqDTO;
import com.jzo2o.market.model.dto.response.ActivityInfoResDTO;
import com.jzo2o.market.model.dto.response.SeizeCouponInfoResDTO;
import com.jzo2o.market.service.IActivityService;
import com.jzo2o.market.service.ICouponService;
import com.jzo2o.market.service.ICouponWriteOffService;
import com.jzo2o.mysql.utils.PageUtils;
import org.apache.ibatis.annotations.Param;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.jzo2o.market.constants.RedisConstants.RedisKey.*;
import static com.jzo2o.market.enums.ActivityStatusEnum.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author itcast
 * @since 2023-09-16
 */
@Service
public class ActivityServiceImpl extends ServiceImpl<ActivityMapper, Activity> implements IActivityService {
    private static final int MILLION = 1000000;

    @Resource
    private RedisTemplate redisTemplate;

    @Resource
    private ICouponService couponService;

    @Resource
    private ICouponWriteOffService couponWriteOffService;

    @Override
    public PageResult<ActivityInfoResDTO> queryForPage(ActivityQueryForPageReqDTO activityQueryForPageReqDTO) {
        LocalDateTime now = DateUtils.now();
        // 1.查询准备
        LambdaQueryWrapper<Activity> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        // 查询条件
        lambdaQueryWrapper.eq(ObjectUtils.isNotNull(activityQueryForPageReqDTO.getId()), Activity::getId, activityQueryForPageReqDTO.getId())
                .like(StringUtils.isNotEmpty(activityQueryForPageReqDTO.getName()), Activity::getName, activityQueryForPageReqDTO.getName())
                .eq(ObjectUtils.isNotNull(activityQueryForPageReqDTO.getType()), Activity::getType, activityQueryForPageReqDTO.getType())
                .eq(ObjectUtils.isNotNull(activityQueryForPageReqDTO.getStatus()), Activity::getStatus, activityQueryForPageReqDTO.getStatus());

        // 排序
        lambdaQueryWrapper.orderByDesc(Activity::getId);
        // 分页
        Page<Activity> activityPage = new Page<>(activityQueryForPageReqDTO.getPageNo().intValue(), activityQueryForPageReqDTO.getPageSize().intValue());
        activityPage = baseMapper.selectPage(activityPage, lambdaQueryWrapper);
        return PageUtils.toPage(activityPage, ActivityInfoResDTO.class);
    }

    @Override
    public ActivityInfoResDTO queryById(Long id) {
        // 1.获取活动
        Activity activity = baseMapper.selectById(id);
        // 判空
        if (activity == null) {
            return new ActivityInfoResDTO();
        }
        // 2.数据转换，并返回信息
        ActivityInfoResDTO activityInfoResDTO = BeanUtils.toBean(activity, ActivityInfoResDTO.class);
        // 设置状态
//        activityInfoResDTO.setStatus(getStatus(activity.getDistributeStartTime(), activity.getDistributeEndTime(), activity.getStatus()));
        // 3.领取数量
//        Integer receiveNum = couponService.countReceiveNumByActivityId(activity.getId());
        Integer receiveNum = activity.getTotalNum()-activity.getStockNum();
        activityInfoResDTO.setReceiveNum(receiveNum);
        // 4.核销量
        Long writeOffNum = couponWriteOffService.countByActivityId(id);
        activityInfoResDTO.setWriteOffNum(Math.toIntExact(NumberUtils.null2Zero(writeOffNum)));

        //
        return activityInfoResDTO;
    }

    @Override
    public void save(ActivitySaveReqDTO activitySaveReqDTO) {
        // 1.逻辑校验
        activitySaveReqDTO.check();
        // 2.活动数据组装
        // 转换
        Activity activity = BeanUtils.toBean(activitySaveReqDTO, Activity.class);
        // 状态
        activity.setStatus(NO_DISTRIBUTE.getStatus());
        //库存
        activity.setStockNum(activitySaveReqDTO.getTotalNum());
        if(activitySaveReqDTO.getId() == null) {
            activity.setId(IdUtils.getSnowflakeNextId());
        }
        //排序字段
//        long sortBy = DateUtils.toEpochMilli(activity.getDistributeStartTime()) * MILLION + activity.getId() % MILLION;
        // 3.保存
        saveOrUpdate(activity);
    }

    /**
     *
     * @param tabType 页面类型 1进行中，2待生效
     * @return
     */
    @Override
    public List<SeizeCouponInfoResDTO> queryForListFromCache(Integer tabType) {
        //查询redis查询活动信息
        String activityString = redisTemplate.opsForValue().get(ACTIVITY_CACHE_LIST).toString();
        List<SeizeCouponInfoResDTO> seizeCouponInfoResDTOS = JsonUtils.toList(activityString, SeizeCouponInfoResDTO.class);
        //要查询的活动状态
        //redis中的状态是1：待生效，2：进行中，3：已失效
        int status = tabType==1?2:1;
        //进行状态处理，根据时间去判断如果已开始这里将状态更改为已开始
//        seizeCouponInfoResDTOS.stream().forEach(seizeCouponInfoResDTO -> {
//            int status1 = getStatus(seizeCouponInfoResDTO.getDistributeStartTime(), seizeCouponInfoResDTO.getDistributeEndTime(), status);
//        });
        List<SeizeCouponInfoResDTO> collect = seizeCouponInfoResDTOS.stream()
                .filter(seizeCouponInfoResDTO -> status == getStatus(seizeCouponInfoResDTO.getDistributeStartTime(), seizeCouponInfoResDTO.getDistributeEndTime(), seizeCouponInfoResDTO.getStatus())).collect(Collectors.toList());

        return collect;
    }

    @Override
    public ActivityInfoResDTO getActivityInfoByIdFromCache(Long id) {
        // 1.从缓存中获取活动信息
        Object activityList = redisTemplate.opsForValue().get(ACTIVITY_CACHE_LIST);
        if (ObjectUtils.isNull(activityList)) {
            return null;
        }
        // 2.过滤指定活动信息
        List<ActivityInfoResDTO> list = JsonUtils.toList(activityList.toString(), ActivityInfoResDTO.class);
        if (CollUtils.isEmpty(list)) {
            return null;
        }
        // 3.过滤指定活动
        return list.stream()
                .filter(activityInfoResDTO -> activityInfoResDTO.getId().equals(id))
                .findFirst().orElse(null);
    }

    @Override
    public void preHeat() {
        //查询活动信息，进行中，待生效
        List<Activity> list = lambdaQuery()
                .in(Activity::getStatus, Arrays.asList(DISTRIBUTING.getStatus(), NO_DISTRIBUTE.getStatus()))
                .orderByAsc(Activity::getDistributeStartTime)//按活动开始时间升序排序
                .list();

        //将list 转成  List<SeizeCouponInfoResDTO>
         List<SeizeCouponInfoResDTO> seizeCouponInfoResDTOS = BeanUtils.copyToList(list, SeizeCouponInfoResDTO.class);
         seizeCouponInfoResDTOS.stream().forEach(seizeCouponInfoResDTO -> {
             seizeCouponInfoResDTO.setRemainNum(seizeCouponInfoResDTO.getStockNum());
         });
        String jsonString = JsonUtils.toJsonStr(seizeCouponInfoResDTOS);
        //写入redis
        redisTemplate.opsForValue().set(ACTIVITY_CACHE_LIST, jsonString);

        //同步库存
       //将待生效的活动库存同步到redis
        seizeCouponInfoResDTOS.stream().filter(seizeCouponInfoResDTO -> getStatus(seizeCouponInfoResDTO.getDistributeStartTime(), seizeCouponInfoResDTO.getDistributeEndTime(), seizeCouponInfoResDTO.getStatus())==1).forEach(seizeCouponInfoResDTO -> {
            String redisKey = String.format(COUPON_RESOURCE_STOCK, seizeCouponInfoResDTO.getId() % 10) ;
            redisTemplate.opsForHash().put(redisKey, seizeCouponInfoResDTO.getId(), seizeCouponInfoResDTO.getStockNum());
        });

        //将已生效的活动库存同步到redis
          seizeCouponInfoResDTOS.stream().filter(seizeCouponInfoResDTO ->getStatus(seizeCouponInfoResDTO.getDistributeStartTime(), seizeCouponInfoResDTO.getDistributeEndTime(), seizeCouponInfoResDTO.getStatus())==2).forEach(seizeCouponInfoResDTO -> {
           String redisKey = String.format(COUPON_RESOURCE_STOCK, seizeCouponInfoResDTO.getId() % 10) ;
            redisTemplate.opsForHash().putIfAbsent(redisKey, seizeCouponInfoResDTO.getId(), seizeCouponInfoResDTO.getStockNum());
        });

    }

    @Override
    public void activityFinished() {
        LocalDateTime now = DateUtils.now();
        // 1.查询1天内结束活动
        List<Activity> activities = lambdaQuery().ge(Activity::getDistributeEndTime, now)
                .eq(Activity::getStatus, DISTRIBUTING.getStatus())
                .gt(Activity::getDistributeEndTime, now.minusDays(1))
                .le(Activity::getDistributeEndTime, now)
                .list();
        if(CollUtils.isEmpty(activities)) {
            return;
        }
        // 2.转换redisKey列表(库存和抢单列表)
        List<String> deleteRedisKeyList = new ArrayList<>();
        activities.stream().forEach(activity -> {
            int index = (int) (activity.getId() % 10);
            // 资源库存redisKey
            deleteRedisKeyList.add(String.format(COUPON_RESOURCE_STOCK, index));
            // 抢券列表
            deleteRedisKeyList.add(String.format(COUPON_SEIZE_LIST,activity.getId(), index));
        });
        // 3.删除deleteRedisKeyList
        redisTemplate.delete(deleteRedisKeyList);

    }

    @Override
    public void updateStatus() {
        LocalDateTime now = DateUtils.now();
        // 1.更新已经进行中的状态
        lambdaUpdate()
                .set(Activity::getStatus, ActivityStatusEnum.DISTRIBUTING.getStatus())//更新活动状态为进行中
                .eq(Activity::getStatus, NO_DISTRIBUTE.getStatus())//检索待生效的活动
                .le(Activity::getDistributeStartTime, now)//活动开始时间小于等于当前时间
                .gt(Activity::getDistributeEndTime,now)//活动结束时间大于当前时间
                .update();
        // 2.更新已经结束的
        lambdaUpdate()
                .set(Activity::getStatus, LOSE_EFFICACY.getStatus())//更新活动状态为已失效
                .in(Activity::getStatus, Arrays.asList(DISTRIBUTING.getStatus(), NO_DISTRIBUTE.getStatus()))//检索待生效及进行中的活动
                .lt(Activity::getDistributeEndTime, now)//活动结束时间小于当前时间
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long id) {
        // 1.活动作废
        boolean update = lambdaUpdate()
                .set(Activity::getStatus, ActivityStatusEnum.VOIDED.getStatus())
                .eq(Activity::getId, id)
                .in(Activity::getStatus, Arrays.asList(NO_DISTRIBUTE.getStatus(), DISTRIBUTING.getStatus()))
                .update();
        if(!update) {
            return;
        }
        // 2.未使用优惠券作废
        couponService.revoke(id);

    }

    @Override
    public boolean updateStockNum(Long id,Integer num) {
        //sql=update activity set stock_num=stock_num-#{num} where id=#{activityId} and stock_num>=#{num}
        boolean update = lambdaUpdate()
                .setSql("stock_num=stock_num-"+num)
                .eq(Activity::getId, id)
                .ge(Activity::getStockNum, num)
                .update();
        return update;
    }

    /**
     * 获取状态，
     * 用于xxl或其他定时任务在高性能要求下无法做到实时状态
     *
     * @return
     */
    private int getStatus(LocalDateTime distributeStartTime, LocalDateTime distributeEndTime, Integer status) {
        if (NO_DISTRIBUTE.equals(status) &&
                distributeStartTime.isBefore(DateUtils.now()) &&
                distributeEndTime.isAfter(DateUtils.now())) {//待生效状态，实际活动已开始
            return DISTRIBUTING.getStatus();
        }else if(NO_DISTRIBUTE.equals(status) &&
                distributeEndTime.isBefore(DateUtils.now())){//待生效状态，实际活动已结束
            return LOSE_EFFICACY.getStatus();
        }else if (DISTRIBUTING.equals(status) &&
                distributeEndTime.isBefore(DateUtils.now())) {//进行中状态，实际活动已结束
            return LOSE_EFFICACY.getStatus();
        }
        return status;
    }
}
