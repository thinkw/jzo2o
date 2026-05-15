package com.jzo2o.customer.model.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 评价表
 * </p>
 *
 * @author itcast
 * @since 2024-01-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("evaluation")
public class Evaluation implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 评价id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 评价对象类型：6=服务项，7=服务人员/机构
     */
    private Integer targetTypeId;

    /**
     * 评价对象id（服务项id或服务人员id）
     */
    private Long targetId;

    /**
     * 评价对象名称
     */
    private String targetName;

    /**
     * 关联订单id
     */
    private Long relationId;

    /**
     * 评价人id
     */
    private Long evaluatorId;

    /**
     * 评价人昵称
     */
    private String evaluatorNickname;

    /**
     * 评价人头像
     */
    private String evaluatorAvatar;

    /**
     * 是否匿名：0=不匿名，1=匿名
     */
    private Integer isAnonymous;

    /**
     * 评价内容
     */
    private String content;

    /**
     * 总分
     */
    private BigDecimal totalScore;

    /**
     * 评价等级：1=差评，2=中评，3=好评
     */
    private Integer scoreLevel;

    /**
     * 评分明细JSON
     */
    private String scoreItems;

    /**
     * 评价图片URL数组JSON
     */
    private String pictureArray;

    /**
     * 状态：0=正常，1=已删除
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
