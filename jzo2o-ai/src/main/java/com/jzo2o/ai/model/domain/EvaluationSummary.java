package com.jzo2o.ai.model.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 评价总结 — 按评价目标 (服务项/服务人员) 记录增量总结
 */
@Data
@TableName("evaluation_summary")
public class EvaluationSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 评价目标类型: 6=服务项, 7=服务人员 */
    private Integer targetTypeId;

    /** 评价目标ID */
    private Long targetId;

    /** AI 生成的总结文本 */
    private String summaryContent;

    /** 本次总结覆盖的最后一条评价ID (增量游标) */
    private Long lastEvaluationId;

    /** 本次总结覆盖的最后一条评价时间 (增量游标) */
    private LocalDateTime lastEvaluationTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
