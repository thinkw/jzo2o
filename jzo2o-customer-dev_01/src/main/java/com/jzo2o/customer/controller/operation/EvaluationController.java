package com.jzo2o.customer.controller.operation;

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
 * 运营端 - 评价相关接口
 *
 * @author itcast
 **/
@RestController("operationEvaluationController")
@RequestMapping("/operation/evaluation")
@Api(tags = "运营端 - 评价相关接口")
public class EvaluationController {
    @Resource
    private EvaluationService evaluationService;

    @GetMapping("/pageByTarget")
    @ApiOperation("运营端分页查询评价列表")
    public Map<String, Object> pageByTarget(EvaluationPageByTargetReqDTO evaluationPageByTargetReqDTO) {
        return evaluationService.pageByTarget(evaluationPageByTargetReqDTO);
    }

    @GetMapping("/{id}")
    @ApiOperation("查询评价详情")
    public EvaluationResDTO getById(@PathVariable("id") Long id) {
        return evaluationService.getById(id);
    }

    @DeleteMapping("/{id}")
    @ApiOperation("删除评价")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "id", value = "评价id", required = true, dataTypeClass = Long.class)
    })
    public void delete(@PathVariable("id") Long id) {
        evaluationService.delete(id);
    }
}
