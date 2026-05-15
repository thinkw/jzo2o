package com.jzo2o.customer.controller.consumer;

import com.jzo2o.api.customer.dto.request.EvaluationSubmitReqDTO;
import com.jzo2o.common.utils.UserContext;
import com.jzo2o.customer.model.dto.request.EvaluationPageByTargetReqDTO;
import com.jzo2o.customer.model.dto.response.BooleanResDTO;
import com.jzo2o.customer.model.dto.response.EvaluationAndOrdersResDTO;
import com.jzo2o.customer.model.dto.response.EvaluationResDTO;
import com.jzo2o.customer.service.EvaluationService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 用户端 - 评价相关接口
 *
 * @author itcast
 **/
@RestController("consumerEvaluationController")
@RequestMapping("/consumer/evaluation")
@Api(tags = "用户端 - 评价相关接口")
public class EvaluationController {
    @Resource
    private EvaluationService evaluationService;

    @PostMapping
    @ApiOperation("发表评价")
    public BooleanResDTO submit(@RequestBody EvaluationSubmitReqDTO evaluationSubmitReqDTO) {
        evaluationSubmitReqDTO.setCurrentUserInfo(UserContext.currentUser());
        return evaluationService.submit(evaluationSubmitReqDTO);
    }

    @PutMapping("/{id}")
    @ApiOperation("修改评价")
    public void update(@PathVariable("id") Long id,
                       @RequestBody EvaluationSubmitReqDTO evaluationSubmitReqDTO) {
        evaluationService.update(id, evaluationSubmitReqDTO);
    }

    @GetMapping("/{id}")
    @ApiOperation("查询评价详情")
    public EvaluationResDTO getById(@PathVariable("id") Long id) {
        return evaluationService.getById(id);
    }

    @DeleteMapping("/{id}")
    @ApiOperation("删除评价")
    public void delete(@PathVariable("id") Long id) {
        evaluationService.delete(id);
    }

    @GetMapping("/pageByTarget")
    @ApiOperation("根据对象属性分页查询评价列表")
    public Map<String, Object> pageByTarget(EvaluationPageByTargetReqDTO evaluationPageByTargetReqDTO) {
        return evaluationService.pageByTarget(evaluationPageByTargetReqDTO);
    }

    @GetMapping("/pageByCurrentUser")
    @ApiOperation("分页查询当前用户评价列表")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "pageNo", value = "页码，默认为1", defaultValue = "1", dataTypeClass = Integer.class),
            @ApiImplicitParam(name = "pageSize", value = "页面大小，默认为10", defaultValue = "10", dataTypeClass = Integer.class)
    })
    public List<EvaluationAndOrdersResDTO> pageByCurrentUser(
            @RequestParam(value = "pageNo", required = false, defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", required = false, defaultValue = "10") Integer pageSize) {
        return evaluationService.pageByCurrentUser(pageNo, pageSize);
    }
}
