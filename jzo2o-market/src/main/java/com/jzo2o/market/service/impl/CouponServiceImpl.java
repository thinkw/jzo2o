package com.jzo2o.market.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.customer.CommonUserApi;
import com.jzo2o.api.customer.dto.response.CommonUserResDTO;
import com.jzo2o.api.market.dto.request.CouponUseBackReqDTO;
import com.jzo2o.api.market.dto.request.CouponUseReqDTO;
import com.jzo2o.api.market.dto.response.AvailableCouponsResDTO;
import com.jzo2o.api.market.dto.response.CouponUseResDTO;
import com.jzo2o.common.expcetions.BadRequestException;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.expcetions.DBException;
import com.jzo2o.common.model.PageResult;
import com.jzo2o.common.utils.*;
import com.jzo2o.market.enums.ActivityStatusEnum;
import com.jzo2o.market.enums.CouponStatusEnum;
import com.jzo2o.market.mapper.CouponMapper;
import com.jzo2o.market.model.domain.Activity;
import com.jzo2o.market.model.domain.Coupon;
import com.jzo2o.market.model.domain.CouponWriteOff;
import com.jzo2o.market.model.dto.request.CouponOperationPageQueryReqDTO;
import com.jzo2o.market.model.dto.request.SeizeCouponReqDTO;
import com.jzo2o.market.model.dto.response.ActivityInfoResDTO;
import com.jzo2o.market.model.dto.response.CouponInfoResDTO;
import com.jzo2o.market.service.IActivityService;
import com.jzo2o.market.service.ICouponService;
import com.jzo2o.market.service.ICouponUseBackService;
import com.jzo2o.market.service.ICouponWriteOffService;
import com.jzo2o.market.utils.CouponUtils;
import com.jzo2o.mysql.utils.PageUtils;
import com.jzo2o.redis.properties.RedisSyncProperties;
import com.jzo2o.redis.utils.RedisSyncQueueUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.jzo2o.common.constants.ErrorInfo.Code.SEIZE_COUPON_FAILD;
import static com.jzo2o.market.constants.RedisConstants.RedisKey.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author itcast
 * @since 2023-09-16
 */
@Service
@Slf4j
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {

    @Resource(name = "seizeCouponScript")
    private DefaultRedisScript<String> seizeCouponScript;

    @Resource
    private RedisSyncProperties redisSyncProperties;

    @Resource
    private CommonUserApi commonUserApi;

    @Resource
    private RedisTemplate redisTemplate;

    @Resource
    private IActivityService activityService;

    @Resource
    private ICouponUseBackService couponUseBackService;

    @Resource
    private ICouponWriteOffService couponWriteOffService;

    @Override
    public PageResult<CouponInfoResDTO> queryForPageOfOperation(CouponOperationPageQueryReqDTO couponOperationPageQueryReqDTO) {
        // 1.数据校验
        if (ObjectUtils.isNull(couponOperationPageQueryReqDTO.getActivityId())) {
            throw new BadRequestException("请指定活动");
        }
        // 2.数据查询
        // 分页 排序
        Page<Coupon> couponQueryPage = PageUtils.parsePageQuery(couponOperationPageQueryReqDTO, Coupon.class);
        // 查询条件
        LambdaQueryWrapper<Coupon> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(Coupon::getActivityId, couponOperationPageQueryReqDTO.getActivityId());
        // 查询数据
        Page<Coupon> couponPage = baseMapper.selectPage(couponQueryPage, lambdaQueryWrapper);

        // 3.数据转化，并返回
        return PageUtils.toPage(couponPage, CouponInfoResDTO.class);
    }

    @Override
    public List<CouponInfoResDTO> queryForList(Long lastId, Long userId, Integer status) {

        // 1.校验
        if (status > 3 || status < 1) {
            throw new BadRequestException("请求状态不存在");
        }
        // 2.查询准备
        LambdaQueryWrapper<Coupon> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        // 查询条件
        lambdaQueryWrapper.eq(Coupon::getStatus, status)
                .eq(Coupon::getUserId, userId)
                .lt(ObjectUtils.isNotNull(lastId), Coupon::getId, lastId);
        // 查询字段
        lambdaQueryWrapper.select(Coupon::getId);
        // 排序
        lambdaQueryWrapper.orderByDesc(Coupon::getId);
        // 查询条数限制
        lambdaQueryWrapper.last(" limit 10 ");
        // 3.查询数据(数据中只含id)
        List<Coupon> couponsOnlyId = baseMapper.selectList(lambdaQueryWrapper);
        //判空
        if (CollUtils.isEmpty(couponsOnlyId)) {
            return new ArrayList<>();
        }

        // 4.获取数据且数据转换
        // 优惠id列表
        List<Long> ids = couponsOnlyId.stream()
                .map(Coupon::getId)
                .collect(Collectors.toList());
        // 获取优惠券数据
        List<Coupon> coupons = baseMapper.selectBatchIds(ids);
        // 数据转换
        return BeanUtils.copyToList(coupons, CouponInfoResDTO.class);
    }

    @Override
    public void seizeCoupon(SeizeCouponReqDTO seizeCouponReqDTO) {

        // 1.校验活动开始时间或结束
        // 抢券时间
        ActivityInfoResDTO activity = activityService.getActivityInfoByIdFromCache(seizeCouponReqDTO.getId());
        LocalDateTime now = DateUtils.now();
        if (activity == null ||
                activity.getDistributeStartTime().isAfter(now)) {
            throw new CommonException(SEIZE_COUPON_FAILD, "活动未开始");
        }
        if (activity.getDistributeEndTime().isBefore(now)) {
            throw new CommonException(SEIZE_COUPON_FAILD, "活动已结束");
        }

        // 2.抢券准备
//         key: 抢券同步队列，资源库存,抢券列表
//         argv：抢券id,用户id
        int index = (int) (seizeCouponReqDTO.getId() % 10);
        // 同步队列redisKey
        String couponSeizeSyncRedisKey = RedisSyncQueueUtils.getQueueRedisKey(COUPON_SEIZE_SYNC_QUEUE_NAME, index);
        // 资源库存redisKey
        String resourceStockRedisKey = String.format(COUPON_RESOURCE_STOCK, index);
        // 抢券列表
        String couponSeizeListRedisKey = String.format(COUPON_SEIZE_LIST, activity.getId(), index);

        log.debug("seize coupon keys -> couponSeizeListRedisKey->{},resourceStockRedisKey->{},couponSeizeListRedisKey->{},seizeCouponReqDTO.getId()->{},UserContext.currentUserId():{}",
                couponSeizeListRedisKey, resourceStockRedisKey, couponSeizeListRedisKey, seizeCouponReqDTO.getId(), UserContext.currentUserId());
        // 3.抢券结果
        Object execute = redisTemplate.execute(seizeCouponScript, Arrays.asList(couponSeizeSyncRedisKey, resourceStockRedisKey, couponSeizeListRedisKey),
                seizeCouponReqDTO.getId(), UserContext.currentUserId());
        log.debug("seize coupon result : {}", execute);
        // 4.处理lua脚本结果
        if (execute == null) {
            throw new CommonException(SEIZE_COUPON_FAILD, "抢券失败");
        }
        long result = NumberUtils.parseLong(execute.toString());
        if (result > 0) {
            return;
        }
        if (result == -1) {
            throw new CommonException(SEIZE_COUPON_FAILD, "限领一张");
        }
        if (result == -2 || result == -4) {
            throw new CommonException(SEIZE_COUPON_FAILD, "已抢光!");
        }
        throw new CommonException(SEIZE_COUPON_FAILD, "抢券失败");

    }

    @Override
    public List<AvailableCouponsResDTO> getAvailable(Long userId, BigDecimal totalAmount) {
//        Long userId = UserContext.currentUserId();
        // 1.查询优惠券
        List<Coupon> coupons = lambdaQuery()
                .eq(Coupon::getStatus, CouponStatusEnum.NO_USE.getStatus())
                .gt(Coupon::getValidityTime, DateUtils.now())
                .le(Coupon::getAmountCondition, totalAmount)
                .eq(Coupon::getUserId, userId)
                .list();
        // 判空
        if (CollUtils.isEmpty(coupons)) {
            return new ArrayList<>();
        }
        // 2.组装数据计数优惠金额
        return coupons.stream()
                //先计算优惠金额
                .peek(coupon -> coupon.setDiscountAmount(CouponUtils.calDiscountAmount(coupon, totalAmount)))
                //过滤优惠金额小于订单金额的优惠券
                .filter(coupon -> coupon.getDiscountAmount().compareTo(totalAmount) < 0)
                // 计算金额
                .map(coupon -> BeanUtils.copyBean(coupon, AvailableCouponsResDTO.class))
                //按优惠金额降序排
                .sorted(Comparator.comparing(AvailableCouponsResDTO::getDiscountAmount).reversed())
                .collect(Collectors.toList());

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CouponUseResDTO use(CouponUseReqDTO couponUseReqDTO) {
        String lockKey = "coupon:use:" + couponUseReqDTO.getId();

        boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);

        if (!locked) {
            throw new CommonException("操作频繁,请稍后再试");
        }
        try {
            // 1.校验优惠券
            // 1.1.获取优惠券
            Long userId = UserContext.currentUserId();
            Coupon coupon = baseMapper.selectById(couponUseReqDTO.getId());
            // 1.2.优惠券判空
            if (coupon == null) {
                throw new BadRequestException("优惠券不存在");
            }

            // 2.使用优惠券
            boolean update = lambdaUpdate()
                    .set(Coupon::getStatus, CouponStatusEnum.USED.getStatus())
                    .set(Coupon::getUseTime, DateUtils.now())
                    .set(Coupon::getOrdersId, couponUseReqDTO.getOrdersId())
                    .eq(Coupon::getId, couponUseReqDTO.getId())
                    .eq(Coupon::getStatus, CouponStatusEnum.NO_USE.getStatus())
                    .ge(Coupon::getValidityTime, DateUtils.now())
                    .le(Coupon::getAmountCondition, couponUseReqDTO.getTotalAmount())
                    .update();
            if (!update) {
                throw new DBException("优惠券已失效");
            }
            // 3.添加核销记录
            CouponWriteOff couponWriteOff = CouponWriteOff.builder()
                    .id(IdUtils.getSnowflakeNextId())
                    .couponId(couponUseReqDTO.getId())
                    .userId(userId)
                    .ordersId(couponUseReqDTO.getOrdersId())
                    .activityId(coupon.getActivityId())
                    .writeOffTime(DateUtils.now())
                    .writeOffManName(coupon.getUserName())
                    .writeOffManPhone(coupon.getUserPhone())
                    .build();
            if (!couponWriteOffService.save(couponWriteOff)) {
                throw new DBException("操作失败");
            }

            // 4.计算优惠金额
            BigDecimal discountAmount = CouponUtils.calDiscountAmount(coupon, couponUseReqDTO.getTotalAmount());
            CouponUseResDTO couponUseResDTO = new CouponUseResDTO();
            couponUseResDTO.setDiscountAmount(discountAmount);
            return couponUseResDTO;
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void useBack(CouponUseBackReqDTO couponUseBackReqDTO) {
        // 1.校验是否可以回退
        CouponWriteOff couponWriteOff = couponWriteOffService.queryByUserIdIdAndOrdersId(couponUseBackReqDTO.getUserId(), couponUseBackReqDTO.getOrdersId());
        // 未查询到无需回滚
        if (couponWriteOff == null) {
            return;
        }
        Coupon coupon = baseMapper.selectById(couponWriteOff.getCouponId());
        if (coupon == null) {
            return;
        }

        Activity activity = activityService.getById(couponWriteOff.getActivityId());
        // 2.回退记录
        couponUseBackService.add(couponWriteOff.getCouponId(), couponUseBackReqDTO.getUserId(), couponWriteOff.getWriteOffTime());

        // 3.回滚优惠券
        CouponStatusEnum couponStatusEnum = coupon.getValidityTime().isAfter(DateUtils.now())
                ? CouponStatusEnum.NO_USE : CouponStatusEnum.INVALID;
        if (ActivityStatusEnum.VOIDED.equals(activity.getStatus())) {
            // 活动作废
            couponStatusEnum = CouponStatusEnum.VOIDED;

        }
        boolean update = lambdaUpdate()
                .set(Coupon::getStatus, couponStatusEnum.getStatus())
                .set(Coupon::getOrdersId, null)
                .set(Coupon::getUseTime, null)
                .eq(Coupon::getId, coupon.getId())
                .update();
        if (!update) {
            throw new RuntimeException("优惠券回退失败");
        }
        // 4.删除核销记录
        couponWriteOffService.removeById(couponWriteOff.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long activityId) {
        lambdaUpdate()
                .set(Coupon::getStatus, CouponStatusEnum.VOIDED.getStatus())
                .eq(Coupon::getActivityId, activityId)
                .eq(Coupon::getStatus, CouponStatusEnum.NO_USE.getStatus())
                .update();
    }

    @Override
    public Long countReceiveNumByActivityId(Long activityId) {
        return lambdaQuery().eq(Coupon::getActivityId, activityId)
                .count();
    }

    @Override
    public void processExpireCoupon() {
        lambdaUpdate()
                .set(Coupon::getStatus, CouponStatusEnum.INVALID.getStatus())
                .eq(Coupon::getStatus, CouponStatusEnum.NO_USE.getStatus())
                .le(Coupon::getValidityTime, DateUtils.now())
                .update();
    }

    /**
     * 在抢券结果同步时通过多线程调用此方法
     *
     * @param activityId
     * @param userId
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void seizeCouponSync(Long activityId, Long userId) {

        // 1.获取活动
        Activity activity = activityService.getById(activityId);
        if (activity == null) {
            return;
        }
        CommonUserResDTO commonUserResDTO = commonUserApi.findById(userId);
        if (commonUserResDTO == null) {
            return;
        }
        // 2.新增优惠券
        Coupon coupon = new Coupon();
        coupon.setId(IdUtils.getSnowflakeNextId());
        coupon.setActivityId(activityId);
        coupon.setUserId(userId);
        coupon.setUserName(commonUserResDTO.getNickname());
        coupon.setUserPhone(commonUserResDTO.getPhone());
        coupon.setName(activity.getName());
        coupon.setType(activity.getType());
        coupon.setDiscountAmount(activity.getDiscountAmount());
        coupon.setDiscountRate(activity.getDiscountRate());
        coupon.setAmountCondition(activity.getAmountCondition());
        coupon.setValidityTime(DateUtils.now().plusDays(activity.getValidityDays()));
        coupon.setStatus(CouponStatusEnum.NO_USE.getStatus());

        boolean save = save(coupon);
        //扣减库存
        boolean b = activityService.updateStockNum(activity.getId(), 1);
        if (!b || !save) {
            throw new CommonException("优惠券同步失败");
        }

    }
}
