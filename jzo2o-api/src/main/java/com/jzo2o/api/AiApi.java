package com.jzo2o.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 内部接口 - AI 能力调用
 */
@FeignClient(contextId = "jzo2o-ai", value = "jzo2o-ai", path = "/ai/inner/ai")
public interface AiApi {

    /**
     * 触发 AI 评价总结 (增量模式)
     *
     * @param targetTypeId 评价目标类型 (6=服务项, 7=服务人员)
     * @param targetId     目标ID
     * @return { "summary": "总结文本" }
     */
    @PostMapping("/evaluation/summarize")
    Map<String, String> summarizeEvaluation(@RequestParam("targetTypeId") Integer targetTypeId,
                                            @RequestParam("targetId") Long targetId);

    /**
     * 查询已有的 AI 评价总结
     *
     * @return { "summary": "总结文本" }
     */
    @GetMapping("/evaluation/summarize")
    Map<String, String> getEvaluationSummary(@RequestParam("targetTypeId") Integer targetTypeId,
                                             @RequestParam("targetId") Long targetId);
}
