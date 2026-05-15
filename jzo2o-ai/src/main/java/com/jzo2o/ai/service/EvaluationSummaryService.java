package com.jzo2o.ai.service;

/**
 * AI 评价总结服务 — 增量汇总服务人员/服务项的评价
 */
public interface EvaluationSummaryService {

    /**
     * 对指定目标生成/更新 AI 评价总结 (增量模式)
     *
     * @param targetTypeId 评价目标类型 (6=服务项, 7=服务人员)
     * @param targetId     目标ID
     * @return AI 生成的总结文本, 无新评价时返回 null
     */
    String summarize(Integer targetTypeId, Long targetId);

    /**
     * 查询已有的评价总结
     *
     * @param targetTypeId 评价目标类型
     * @param targetId     目标ID
     * @return 总结文本, 不存在时返回 null
     */
    String getSummary(Integer targetTypeId, Long targetId);
}
