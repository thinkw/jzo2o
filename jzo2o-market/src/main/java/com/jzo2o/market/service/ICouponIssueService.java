package com.jzo2o.market.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jzo2o.market.model.domain.CouponIssue;
import com.jzo2o.market.model.dto.request.CouponIssueReqDTO;

import java.util.List;

/**
 * <p>
 *  发放优惠券服务类
 * </p>
 *
 * @author mrt
 * @since  2023-09-23
 */
public interface ICouponIssueService extends IService<CouponIssue> {

    /**
     * 发放优惠券
     */
    void issue(CouponIssueReqDTO couponIssueReqDTO);

    /**
     * 自动发放优惠券
     */
    void autoIssue(Long activityId);

    /**
     * 提交待发放优惠券数据,保存到待发放优惠券表
     */
    List<CouponIssue> save(CouponIssueReqDTO couponIssueReqDTO);
}
