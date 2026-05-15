package com.jzo2o.customer.service;

import com.jzo2o.api.customer.dto.request.EvaluationSubmitReqDTO;
import com.jzo2o.common.model.CurrentUserInfo;
import com.jzo2o.customer.model.domain.Evaluation;
import com.jzo2o.customer.model.dto.request.EvaluationPageByTargetReqDTO;
import com.jzo2o.customer.model.dto.response.EvaluationAndOrdersResDTO;
import com.jzo2o.customer.model.dto.response.EvaluationResDTO;
import com.jzo2o.customer.model.dto.response.BooleanResDTO;

import java.util.List;
import java.util.Map;

/**
 * 评价服务接口
 *
 * @author itcast
 */
public interface EvaluationService {

    /**
     * 发表评价
     *
     * @param evaluationSubmitReqDTO 发表评价请求体
     */
    BooleanResDTO submit(EvaluationSubmitReqDTO evaluationSubmitReqDTO);

    /**
     * 修改评价
     *
     * @param id                     评价id
     * @param evaluationSubmitReqDTO 评价内容
     */
    void update(Long id, EvaluationSubmitReqDTO evaluationSubmitReqDTO);

    /**
     * 根据id查询评价
     *
     * @param id 评价id
     * @return 评价详情
     */
    EvaluationResDTO getById(Long id);

    /**
     * 删除评价（软删除）
     *
     * @param id 评价id
     */
    void delete(Long id);

    /**
     * 根据对象属性分页查询评价列表
     *
     * @param evaluationPageByTargetReqDTO 分页查询请求体
     * @return 分页结果，包含list和total
     */
    Map<String, Object> pageByTarget(EvaluationPageByTargetReqDTO evaluationPageByTargetReqDTO);

    /**
     * 分页查询当前用户评价列表
     *
     * @param pageNo   页码，默认为1
     * @param pageSize 页面大小，默认为10
     * @return 当前用户评价列表
     */
    List<EvaluationAndOrdersResDTO> pageByCurrentUser(Integer pageNo, Integer pageSize);

    /**
     * 根据评价等级分页查询当前用户评价列表
     *
     * @param scoreLevel 评价等级，1差评，2中评，3好评
     * @param pageNo     页码
     * @param pageSize   页面大小
     * @return 评价列表
     */
    List<EvaluationAndOrdersResDTO> pageByCurrentUserAndScoreLevel(Integer scoreLevel, Integer pageNo, Integer pageSize);

    /**
     * 根据订单id列表查询师傅评分
     *
     * @param orderIds 订单id列表
     * @return 订单id->评分
     */
    Map<String, Double> queryServeProviderScoreByOrdersId(List<Long> orderIds);

    /**
     * 根据对象类型和订单id查询评价
     *
     * @param targetTypeId 对象类型id
     * @param ordersId     订单id
     * @return 评价
     */
    EvaluationResDTO queryByTargetTypeIdAndOrdersId(CurrentUserInfo currentUserInfo, String targetTypeId, Long ordersId);

    /**
     * 自动评价（默认5分好评）
     *
     * @param evaluationSubmitReqDTO 评价信息
     */
    void autoEvaluate(EvaluationSubmitReqDTO evaluationSubmitReqDTO);
}
