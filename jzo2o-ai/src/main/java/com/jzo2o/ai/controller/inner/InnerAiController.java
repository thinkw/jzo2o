package com.jzo2o.ai.controller.inner;

import com.jzo2o.ai.service.EvaluationSummaryService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 内部接口 — AI 能力调用
 */
@RestController("innerAiController")
@RequestMapping("/inner/ai")
@Api(tags = "内部接口 - AI 能力调用")
public class InnerAiController {

    @Resource
    private EvaluationSummaryService evaluationSummaryService;

    @PostMapping("/evaluation/summarize")
    @ApiOperation("触发 AI 评价总结 (增量)")
    public Map<String, String> summarizeEvaluation(@RequestParam("targetTypeId") Integer targetTypeId,
                                                    @RequestParam("targetId") Long targetId) {
        String summary = evaluationSummaryService.summarize(targetTypeId, targetId);
        return Map.of("summary", summary != null ? summary : "");
    }

    @GetMapping("/evaluation/summarize")
    @ApiOperation("查询已有 AI 评价总结")
    public Map<String, String> getEvaluationSummary(@RequestParam("targetTypeId") Integer targetTypeId,
                                                     @RequestParam("targetId") Long targetId) {
        String summary = evaluationSummaryService.getSummary(targetTypeId, targetId);
        return Map.of("summary", summary != null ? summary : "");
    }
}
