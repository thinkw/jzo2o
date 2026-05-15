package com.jzo2o.customer.controller.agency;

import com.jzo2o.customer.model.dto.request.EvaluationPageByTargetReqDTO;
import com.jzo2o.customer.model.dto.response.EvaluationResDTO;
import com.jzo2o.customer.service.EvaluationService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 机构端 - 评价相关接口
 *
 * @author itcast
 **/
@RestController("agencyEvaluationController")
@RequestMapping("/agency/evaluation")
@Api(tags = "机构端 - 评价相关接口")
public class EvaluationController {
    @Resource
    private EvaluationService evaluationService;

    @Resource
    private com.jzo2o.api.AiApi aiApi;

    @GetMapping("/pageByTarget")
    @ApiOperation("机构端分页查询评价列表")
    public Map<String, Object> pageByTarget(EvaluationPageByTargetReqDTO evaluationPageByTargetReqDTO) {
        return evaluationService.pageByTarget(evaluationPageByTargetReqDTO);
    }

    @GetMapping("/{id}")
    @ApiOperation("查询评价详情")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "id", value = "评价id", required = true, dataTypeClass = Long.class)
    })
    public EvaluationResDTO getById(@PathVariable("id") Long id) {
        return evaluationService.getById(id);
    }

    @PostMapping("/summarize")
    @ApiOperation("手动触发 AI 评价总结 (强制重新生成)")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "targetTypeId", value = "评价目标类型 (7=服务人员)", required = true, dataTypeClass = Integer.class),
            @ApiImplicitParam(name = "targetId", value = "目标ID", required = true, dataTypeClass = Long.class)
    })
    public Map<String, String> summarize(@RequestParam("targetTypeId") Integer targetTypeId,
                                         @RequestParam("targetId") Long targetId) {
        return aiApi.summarizeEvaluation(targetTypeId, targetId);
    }

    @GetMapping("/summarize")
    @ApiOperation("查询/生成 AI 评价总结 (已有则返回, 无则自动生成)")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "targetTypeId", value = "评价目标类型 (7=服务人员)", required = true, dataTypeClass = Integer.class),
            @ApiImplicitParam(name = "targetId", value = "目标ID", required = true, dataTypeClass = Long.class)
    })
    public Map<String, String> getSummary(@RequestParam("targetTypeId") Integer targetTypeId,
                                           @RequestParam("targetId") Long targetId) {
        Map<String, String> existing = aiApi.getEvaluationSummary(targetTypeId, targetId);
        if (existing != null && existing.get("summary") != null && !existing.get("summary").isEmpty()) {
            return existing;
        }
        return aiApi.summarizeEvaluation(targetTypeId, targetId);
    }
}
