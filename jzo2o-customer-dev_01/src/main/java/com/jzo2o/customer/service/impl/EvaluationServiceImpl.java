package com.jzo2o.customer.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jzo2o.api.customer.dto.request.EvaluationSubmitReqDTO;
import com.jzo2o.api.customer.dto.request.ScoreItem;
import com.jzo2o.api.orders.OrdersApi;
import com.jzo2o.api.orders.OrdersServeApi;
import com.jzo2o.api.orders.dto.response.OrderResDTO;
import com.jzo2o.api.orders.dto.response.ServeProviderIdResDTO;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.expcetions.BadRequestException;
import com.jzo2o.common.expcetions.ForbiddenOperationException;
import com.jzo2o.common.model.CurrentUserInfo;
import com.jzo2o.common.utils.UserContext;
import com.jzo2o.customer.mapper.EvaluationMapper;
import com.jzo2o.customer.model.domain.CommonUser;
import com.jzo2o.customer.model.domain.Evaluation;
import com.jzo2o.customer.model.domain.ServeProvider;
import com.jzo2o.customer.model.dto.request.EvaluationPageByTargetReqDTO;
import com.jzo2o.customer.model.dto.response.EvaluationAndOrdersResDTO;
import com.jzo2o.customer.model.dto.response.EvaluationResDTO;
import com.jzo2o.customer.model.dto.response.BooleanResDTO;
import com.jzo2o.customer.properties.EvaluationProperties;
import com.jzo2o.customer.service.EvaluationService;
import com.jzo2o.customer.service.ICommonUserService;
import com.jzo2o.customer.service.IServeProviderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 评价服务 - 业务层
 *
 * @author itcast
 **/
@Service
public class EvaluationServiceImpl implements EvaluationService {
    @Resource
    private EvaluationMapper evaluationMapper;
    @Resource
    private EvaluationProperties evaluationProperties;
    @Resource
    private OrdersApi ordersApi;
    @Resource
    private OrdersServeApi ordersServeApi;
    @Resource
    private IServeProviderService serveProviderService;
    @Resource
    private ICommonUserService commonUserService;

    /**
     * 默认评价内容
     */
    private static final String DEFAULT_EVALUATION = "此用户没有填写评价，系统默认好评";

    /**
     * 默认匿名状态
     */
    private static final Integer DEFAULT_ANONYMOUS_STATUS = 1;

    /**
     * 计算总分和评价等级
     */
    private void calculateScore(Evaluation evaluation) {
        if (evaluation.getScoreItems() == null) {
            evaluation.setTotalScore(BigDecimal.valueOf(5.0));
            evaluation.setScoreLevel(3);
            return;
        }
        cn.hutool.json.JSONArray scoreArray = JSONUtil.parseArray(evaluation.getScoreItems());
        if (scoreArray == null || scoreArray.isEmpty()) {
            evaluation.setTotalScore(BigDecimal.valueOf(5.0));
            evaluation.setScoreLevel(3);
            return;
        }
        double avg = scoreArray.stream()
                .map(s -> Double.parseDouble(((cn.hutool.json.JSONObject) s).getStr("score")))
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(5.0);
        BigDecimal totalScore = BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP);
        evaluation.setTotalScore(totalScore);
        // 根据总分计算等级：1=差评(0-1.9)，2=中评(2-3.9)，3=好评(4-5)
        double score = totalScore.doubleValue();
        int level;
        if (score >= 4.0) {
            level = 3;
        } else if (score >= 2.0) {
            level = 2;
        } else {
            level = 1;
        }
        evaluation.setScoreLevel(level);
    }

    /**
     * 将Evaluation实体转为EvaluationResDTO
     */
    private EvaluationResDTO toEvaluationResDTO(Evaluation evaluation) {
        EvaluationResDTO dto = new EvaluationResDTO();
        dto.setId(evaluation.getId().toString());
        dto.setTargetId(evaluation.getTargetId().toString());
        dto.setRelationId(evaluation.getRelationId().toString());
        dto.setTargetName(evaluation.getTargetName());
        dto.setEvaluatorId(evaluation.getEvaluatorId().toString());
        dto.setContent(evaluation.getContent());
        dto.setTotalScore(evaluation.getTotalScore() != null ? evaluation.getTotalScore().doubleValue() : null);
        dto.setScoreLevel(evaluation.getScoreLevel());
        dto.setCreateTime(evaluation.getCreateTime() != null ? Date.from(evaluation.getCreateTime().atZone(ZoneId.systemDefault()).toInstant()) : null);
        dto.setUpdateTime(evaluation.getUpdateTime() != null ? Date.from(evaluation.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant()) : null);

        // 评价人信息
        EvaluationResDTO.Person person = new EvaluationResDTO.Person();
        person.setNickName(evaluation.getEvaluatorNickname());
        person.setAvatar(evaluation.getEvaluatorAvatar());
        person.setIsAnonymous(evaluation.getIsAnonymous());
        dto.setEvaluatorInfo(person);

        // 评分明细
        if (evaluation.getScoreItems() != null) {
            cn.hutool.json.JSONArray scoreArray = JSONUtil.parseArray(evaluation.getScoreItems());
            EvaluationResDTO.ScoreItem[] resultArray = scoreArray.stream().map(s -> {
                cn.hutool.json.JSONObject obj = (cn.hutool.json.JSONObject) s;
                EvaluationResDTO.ScoreItem item = new EvaluationResDTO.ScoreItem();
                item.setItemName(obj.getStr("itemName"));
                item.setScore(obj.getDouble("score"));
                return item;
            }).toArray(EvaluationResDTO.ScoreItem[]::new);
            dto.setScoreArray(resultArray);
        }

        // 图片
        if (evaluation.getPictureArray() != null) {
            dto.setPictureArray(JSONUtil.parseArray(evaluation.getPictureArray()).toArray(new String[0]));
        }

        return dto;
    }

    /**
     * 重算服务人员/机构评分
     */
    private void recalculateServeProviderScore(Long serveProviderId) {
        // 查询该服务人员所有评价
        List<Evaluation> evaluations = evaluationMapper.selectList(
                Wrappers.<Evaluation>lambdaQuery()
                        .eq(Evaluation::getTargetTypeId, Integer.valueOf(evaluationProperties.getServeProvider().getTargetTypeId()))
                        .eq(Evaluation::getTargetId, serveProviderId)
                        .eq(Evaluation::getStatus, 0)
        );
        if (CollUtil.isEmpty(evaluations)) {
            return;
        }
        // 综合评分 = 平均值
        double avgScore = evaluations.stream()
                .mapToDouble(e -> e.getTotalScore() != null ? e.getTotalScore().doubleValue() : 5.0)
                .average()
                .orElse(5.0);
        Double score = NumberUtil.round(avgScore, 1).doubleValue();
        // 好评率 = 好评数 / 总数
        long goodCount = evaluations.stream().filter(e -> ObjectUtil.equal(e.getScoreLevel(), 3)).count();
        String goodLevelRate = NumberUtil.decimalFormat("#.##%", NumberUtil.div(goodCount, evaluations.size(), 4));
        // 更新服务人员评分
        serveProviderService.updateScoreById(serveProviderId, score, goodLevelRate);
    }

    /**
     * 发表评价
     */
    @Override
    @Transactional
    public BooleanResDTO submit(EvaluationSubmitReqDTO evaluationSubmitReqDTO) {
        // 校验订单状态（400=待评价）
        OrderResDTO orderResDTO = ordersApi.queryById(evaluationSubmitReqDTO.getOrdersId());
        if (ObjectUtil.notEqual(orderResDTO.getOrdersStatus(), 400)) {
            throw new ForbiddenOperationException("非待评价状态不可评价");
        }

        CurrentUserInfo currentUserInfo = evaluationSubmitReqDTO.getCurrentUserInfo();

        // 发送服务项评价
        EvaluationResDTO serveItemEvaluation = queryByTargetTypeIdAndOrdersId(
                currentUserInfo, evaluationProperties.getServeItem().getTargetTypeId(), evaluationSubmitReqDTO.getOrdersId());
        if (serveItemEvaluation == null) {
            submitServeItemEvaluation(evaluationSubmitReqDTO, currentUserInfo);
        }

        // 发送服务人员/机构评价
        EvaluationResDTO serveProviderEvaluation = queryByTargetTypeIdAndOrdersId(
                currentUserInfo, evaluationProperties.getServeProvider().getTargetTypeId(), evaluationSubmitReqDTO.getOrdersId());
        if (serveProviderEvaluation == null) {
            submitServeProviderEvaluation(evaluationSubmitReqDTO, currentUserInfo);
        }

        // 更新订单评价状态
        ordersApi.evaluate(evaluationSubmitReqDTO.getOrdersId());

        return new BooleanResDTO(true);
    }

    /**
     * 提交服务项评价
     */
    private void submitServeItemEvaluation(EvaluationSubmitReqDTO req, CurrentUserInfo currentUserInfo) {
        OrderResDTO orderResDTO = ordersApi.queryById(req.getOrdersId());
        Evaluation evaluation = new Evaluation();
        evaluation.setTargetTypeId(Integer.valueOf(evaluationProperties.getServeItem().getTargetTypeId()));
        evaluation.setTargetId(orderResDTO.getServeItemId());
        evaluation.setTargetName(orderResDTO.getServeItemName());
        evaluation.setRelationId(req.getOrdersId());
        evaluation.setEvaluatorId(currentUserInfo.getId());
        evaluation.setEvaluatorNickname(currentUserInfo.getName());
        evaluation.setEvaluatorAvatar(currentUserInfo.getAvatar());
        evaluation.setIsAnonymous(req.getIsAnonymous());
        evaluation.setContent(req.getServeItemEvaluationContent());
        evaluation.setPictureArray(req.getServeItemPictureArray() != null
                ? JSONUtil.toJsonStr(req.getServeItemPictureArray()) : null);
        evaluation.setScoreItems(req.getServeItemScoreItems() != null
                ? JSONUtil.toJsonStr(req.getServeItemScoreItems()) : null);
        calculateScore(evaluation);
        evaluation.setStatus(0);
        evaluation.setCreateTime(LocalDateTime.now());
        evaluation.setUpdateTime(LocalDateTime.now());
        evaluationMapper.insert(evaluation);
    }

    /**
     * 提交服务人员/机构评价
     */
    private void submitServeProviderEvaluation(EvaluationSubmitReqDTO req, CurrentUserInfo currentUserInfo) {
        ServeProviderIdResDTO serveProviderIdResDTO = ordersServeApi.queryServeProviderIdByOrderId(req.getOrdersId());
        ServeProvider serveProvider = serveProviderService.getById(serveProviderIdResDTO.getServeProviderId());

        Evaluation evaluation = new Evaluation();
        evaluation.setTargetTypeId(Integer.valueOf(evaluationProperties.getServeProvider().getTargetTypeId()));
        evaluation.setTargetId(serveProvider.getId());
        evaluation.setTargetName(serveProvider.getName());
        evaluation.setRelationId(req.getOrdersId());
        evaluation.setEvaluatorId(currentUserInfo.getId());
        evaluation.setEvaluatorNickname(currentUserInfo.getName());
        evaluation.setEvaluatorAvatar(currentUserInfo.getAvatar());
        evaluation.setIsAnonymous(req.getIsAnonymous());
        evaluation.setContent(req.getServeProviderEvaluationContent());
        evaluation.setScoreItems(req.getServeProviderScoreItems() != null
                ? JSONUtil.toJsonStr(req.getServeProviderScoreItems()) : null);
        calculateScore(evaluation);
        evaluation.setStatus(0);
        evaluation.setCreateTime(LocalDateTime.now());
        evaluation.setUpdateTime(LocalDateTime.now());
        evaluationMapper.insert(evaluation);

        // 重算服务人员评分
        recalculateServeProviderScore(serveProvider.getId());
    }

    /**
     * 修改评价
     */
    @Override
    public void update(Long id, EvaluationSubmitReqDTO req) {
        Evaluation evaluation = evaluationMapper.selectById(id);
        if (evaluation == null || ObjectUtil.equal(evaluation.getStatus(), 1)) {
            throw new BadRequestException("评价不存在");
        }
        Long currentUserId = UserContext.currentUserId();
        if (ObjectUtil.notEqual(evaluation.getEvaluatorId(), currentUserId)) {
            throw new ForbiddenOperationException("只能修改自己的评价");
        }

        // 根据评价类型使用对应的内容
        String serveProviderTargetTypeId = evaluationProperties.getServeProvider().getTargetTypeId();
        boolean isServeProvider = ObjectUtil.equal(evaluation.getTargetTypeId(), Integer.valueOf(serveProviderTargetTypeId));
        String content = isServeProvider ? req.getServeProviderEvaluationContent() : req.getServeItemEvaluationContent();
        ScoreItem[] scoreItems = isServeProvider ? req.getServeProviderScoreItems() : req.getServeItemScoreItems();
        String[] pictures = isServeProvider ? null : req.getServeItemPictureArray();

        evaluation.setContent(content);
        evaluation.setIsAnonymous(req.getIsAnonymous());
        evaluation.setScoreItems(scoreItems != null ? JSONUtil.toJsonStr(scoreItems) : null);
        if (pictures != null) {
            evaluation.setPictureArray(JSONUtil.toJsonStr(pictures));
        }
        calculateScore(evaluation);
        evaluation.setUpdateTime(LocalDateTime.now());
        evaluationMapper.updateById(evaluation);

        // 如果是服务人员评价，重算评分
        if (isServeProvider) {
            recalculateServeProviderScore(evaluation.getTargetId());
        }
    }

    /**
     * 根据id查询评价
     */
    @Override
    public EvaluationResDTO getById(Long id) {
        Evaluation evaluation = evaluationMapper.selectById(id);
        if (evaluation == null || ObjectUtil.equal(evaluation.getStatus(), 1)) {
            throw new BadRequestException("评价不存在");
        }
        return toEvaluationResDTO(evaluation);
    }

    /**
     * 删除评价（软删除）
     */
    @Override
    @Transactional
    public void delete(Long id) {
        Evaluation evaluation = evaluationMapper.selectById(id);
        if (evaluation == null || ObjectUtil.equal(evaluation.getStatus(), 1)) {
            throw new BadRequestException("评价不存在");
        }
        Long currentUserId = UserContext.currentUserId();
        // 运营端可删除任意评价，用户端只能删除自己的
        if (ObjectUtil.notEqual(UserContext.currentUser().getUserType(), UserType.OPERATION)
                && ObjectUtil.notEqual(evaluation.getEvaluatorId(), currentUserId)) {
            throw new ForbiddenOperationException("只能删除自己的评价");
        }

        evaluation.setStatus(1);
        evaluation.setUpdateTime(LocalDateTime.now());
        evaluationMapper.updateById(evaluation);

        // 如果是服务人员评价，重算评分
        if (ObjectUtil.equal(evaluation.getTargetTypeId(),
                Integer.valueOf(evaluationProperties.getServeProvider().getTargetTypeId()))) {
            recalculateServeProviderScore(evaluation.getTargetId());
        }
    }

    /**
     * 根据对象属性分页查询评价列表
     */
    @Override
    public Map<String, Object> pageByTarget(EvaluationPageByTargetReqDTO req) {
        CurrentUserInfo currentUserInfo = UserContext.currentUser();

        // 如果是用户端请求，默认查询服务项评价
        if (ObjectUtil.equal(UserType.C_USER, currentUserInfo.getUserType()) && req.getTargetTypeId() == null) {
            req.setTargetTypeId(evaluationProperties.getServeItem().getTargetTypeId());
        }

        int pageNo = ObjectUtil.defaultIfNull(req.getPageNo(), 1);
        int pageSize = ObjectUtil.defaultIfNull(req.getPageSize(), 10);

        LambdaQueryWrapper<Evaluation> wrapper = Wrappers.<Evaluation>lambdaQuery()
                .eq(Evaluation::getStatus, 0)
                .eq(req.getTargetTypeId() != null, Evaluation::getTargetTypeId, req.getTargetTypeId() != null ? Integer.valueOf(req.getTargetTypeId()) : null)
                .eq(req.getTargetId() != null, Evaluation::getTargetId, req.getTargetId() != null ? Long.valueOf(req.getTargetId()) : null)
                .orderByDesc(Evaluation::getCreateTime);

        Page<Evaluation> page = new Page<>(pageNo, pageSize);
        Page<Evaluation> result = evaluationMapper.selectPage(page, wrapper);

        List<EvaluationResDTO> list = result.getRecords().stream().map(this::toEvaluationResDTO).collect(Collectors.toList());
        Map<String, Object> map = new HashMap<>();
        map.put("list", list);
        map.put("total", result.getTotal());
        return map;
    }

    /**
     * 分页查询当前用户评价列表
     */
    @Override
    public List<EvaluationAndOrdersResDTO> pageByCurrentUser(Integer pageNo, Integer pageSize) {
        Long currentUserId = UserContext.currentUserId();
        pageNo = ObjectUtil.defaultIfNull(pageNo, 1);
        pageSize = ObjectUtil.defaultIfNull(pageSize, 10);

        LambdaQueryWrapper<Evaluation> wrapper = Wrappers.<Evaluation>lambdaQuery()
                .eq(Evaluation::getStatus, 0)
                .eq(Evaluation::getEvaluatorId, currentUserId)
                .eq(Evaluation::getTargetTypeId, Integer.valueOf(evaluationProperties.getServeItem().getTargetTypeId()))
                .orderByDesc(Evaluation::getCreateTime);

        Page<Evaluation> page = new Page<>(pageNo, pageSize);
        Page<Evaluation> result = evaluationMapper.selectPage(page, wrapper);

        if (CollUtil.isEmpty(result.getRecords())) {
            return Collections.emptyList();
        }

        // 组装评价数据
        List<EvaluationAndOrdersResDTO> list = result.getRecords().stream().map(e -> {
            EvaluationAndOrdersResDTO dto = new EvaluationAndOrdersResDTO();
            dto.setId(e.getId().toString());
            dto.setTargetId(e.getTargetId().toString());
            dto.setRelationId(e.getRelationId().toString());
            dto.setTargetName(e.getTargetName());
            dto.setEvaluatorId(e.getEvaluatorId().toString());
            dto.setContent(e.getContent());
            dto.setTotalScore(e.getTotalScore() != null ? e.getTotalScore().doubleValue() : null);
            dto.setScoreLevel(e.getScoreLevel());
            dto.setCreateTime(e.getCreateTime() != null ? Date.from(e.getCreateTime().atZone(ZoneId.systemDefault()).toInstant()) : null);
            dto.setUpdateTime(e.getUpdateTime() != null ? Date.from(e.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant()) : null);
            if (e.getPictureArray() != null) {
                dto.setPictureArray(JSONUtil.parseArray(e.getPictureArray()).toArray(new String[0]));
            }
            EvaluationAndOrdersResDTO.Person person = new EvaluationAndOrdersResDTO.Person();
            person.setNickName(e.getEvaluatorNickname());
            person.setAvatar(e.getEvaluatorAvatar());
            person.setIsAnonymous(e.getIsAnonymous());
            dto.setEvaluatorInfo(person);
            return dto;
        }).collect(Collectors.toList());

        // 关联订单信息
        List<Long> orderIds = list.stream().map(e -> Long.valueOf(e.getRelationId())).distinct().collect(Collectors.toList());
        List<OrderResDTO> orderResDTOList = ordersApi.queryByIds(orderIds);
        Map<Long, OrderResDTO> ordersMap = orderResDTOList.stream().collect(Collectors.toMap(OrderResDTO::getId, o -> o));
        list.forEach(e -> {
            Long ordersId = Long.valueOf(e.getRelationId());
            OrderResDTO order = ordersMap.get(ordersId);
            if (order != null) {
                e.setServeStartTime(order.getServeStartTime());
                e.setServeAddress(order.getServeAddress());
                e.setServeItemImg(order.getServeItemImg());
            }
        });
        return list;
    }

    /**
     * 根据评价等级分页查询当前用户（服务人员）的评价列表
     */
    @Override
    public List<EvaluationAndOrdersResDTO> pageByCurrentUserAndScoreLevel(Integer scoreLevel, Integer pageNo, Integer pageSize) {
        Long currentUserId = UserContext.currentUserId();
        pageNo = ObjectUtil.defaultIfNull(pageNo, 1);
        pageSize = ObjectUtil.defaultIfNull(pageSize, 10);

        LambdaQueryWrapper<Evaluation> wrapper = Wrappers.<Evaluation>lambdaQuery()
                .eq(Evaluation::getStatus, 0)
                .eq(Evaluation::getTargetTypeId, Integer.valueOf(evaluationProperties.getServeProvider().getTargetTypeId()))
                .eq(Evaluation::getTargetId, currentUserId)
                .eq(scoreLevel != null, Evaluation::getScoreLevel, scoreLevel)
                .orderByDesc(Evaluation::getCreateTime);

        Page<Evaluation> page = new Page<>(pageNo, pageSize);
        Page<Evaluation> result = evaluationMapper.selectPage(page, wrapper);

        if (CollUtil.isEmpty(result.getRecords())) {
            return Collections.emptyList();
        }

        List<EvaluationAndOrdersResDTO> list = result.getRecords().stream().map(e -> {
            EvaluationAndOrdersResDTO dto = new EvaluationAndOrdersResDTO();
            dto.setId(e.getId().toString());
            dto.setTargetId(e.getTargetId().toString());
            dto.setRelationId(e.getRelationId().toString());
            dto.setTargetName(e.getTargetName());
            dto.setEvaluatorId(e.getEvaluatorId().toString());
            dto.setContent(e.getContent());
            dto.setTotalScore(e.getTotalScore() != null ? e.getTotalScore().doubleValue() : null);
            dto.setScoreLevel(e.getScoreLevel());
            dto.setCreateTime(e.getCreateTime() != null ? Date.from(e.getCreateTime().atZone(ZoneId.systemDefault()).toInstant()) : null);
            dto.setUpdateTime(e.getUpdateTime() != null ? Date.from(e.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant()) : null);
            if (e.getPictureArray() != null) {
                dto.setPictureArray(JSONUtil.parseArray(e.getPictureArray()).toArray(new String[0]));
            }
            EvaluationAndOrdersResDTO.Person person = new EvaluationAndOrdersResDTO.Person();
            person.setNickName(e.getEvaluatorNickname());
            person.setAvatar(e.getEvaluatorAvatar());
            person.setIsAnonymous(e.getIsAnonymous());
            dto.setEvaluatorInfo(person);
            return dto;
        }).collect(Collectors.toList());

        // 关联订单信息
        List<Long> orderIds = list.stream().map(e -> Long.valueOf(e.getRelationId())).distinct().collect(Collectors.toList());
        List<OrderResDTO> orderResDTOList = ordersApi.queryByIds(orderIds);
        Map<Long, OrderResDTO> ordersMap = orderResDTOList.stream().collect(Collectors.toMap(OrderResDTO::getId, o -> o));
        list.forEach(e -> {
            Long ordersId = Long.valueOf(e.getRelationId());
            OrderResDTO order = ordersMap.get(ordersId);
            if (order != null) {
                e.setServeStartTime(order.getServeStartTime());
                e.setServeAddress(order.getServeAddress());
                e.setServeItemImg(order.getServeItemImg());
            }
        });
        return list;
    }

    /**
     * 根据订单id列表查询师傅评分
     */
    @Override
    public Map<String, Double> queryServeProviderScoreByOrdersId(List<Long> orderIds) {
        if (CollUtil.isEmpty(orderIds)) {
            return Collections.emptyMap();
        }
        List<Evaluation> evaluations = evaluationMapper.selectList(
                Wrappers.<Evaluation>lambdaQuery()
                        .eq(Evaluation::getTargetTypeId, Integer.valueOf(evaluationProperties.getServeProvider().getTargetTypeId()))
                        .in(Evaluation::getRelationId, orderIds)
                        .eq(Evaluation::getStatus, 0)
        );
        return evaluations.stream()
                .collect(Collectors.toMap(
                        e -> e.getRelationId().toString(),
                        e -> e.getTotalScore() != null ? e.getTotalScore().doubleValue() : 5.0,
                        (v1, v2) -> v1
                ));
    }

    /**
     * 根据对象类型和订单id查询评价
     */
    @Override
    public EvaluationResDTO queryByTargetTypeIdAndOrdersId(CurrentUserInfo currentUserInfo, String targetTypeId, Long ordersId) {
        List<Evaluation> evaluations = evaluationMapper.selectList(
                Wrappers.<Evaluation>lambdaQuery()
                        .eq(Evaluation::getTargetTypeId, Integer.valueOf(targetTypeId))
                        .eq(Evaluation::getRelationId, ordersId)
                        .eq(Evaluation::getEvaluatorId, currentUserInfo.getId())
                        .eq(Evaluation::getStatus, 0)
        );
        if (CollUtil.isEmpty(evaluations)) {
            return null;
        }
        return toEvaluationResDTO(evaluations.get(0));
    }

    /**
     * 查询指定目标在指定时间之后的新增评价 (供 AI 增量总结使用)
     * targetId 为 null 时查询该类型下所有目标
     */
    @Override
    public List<Evaluation> queryByTargetIdAndTime(Integer targetTypeId, Long targetId, LocalDateTime afterTime) {
        return evaluationMapper.selectList(
                Wrappers.<Evaluation>lambdaQuery()
                        .eq(Evaluation::getTargetTypeId, targetTypeId)
                        .eq(targetId != null, Evaluation::getTargetId, targetId)
                        .eq(Evaluation::getStatus, 0)
                        .gt(afterTime != null, Evaluation::getCreateTime, afterTime)
                        .orderByAsc(Evaluation::getCreateTime)
        );
    }

    /**
     * 自动评价（默认5分好评）
     */
    @Override
    @Transactional
    public void autoEvaluate(EvaluationSubmitReqDTO req) {
        // 查询用户信息
        CommonUser commonUser = commonUserService.getById(req.getUserId());
        CurrentUserInfo currentUserInfo = new CurrentUserInfo(commonUser.getId(), commonUser.getNickname(), commonUser.getAvatar(), UserType.C_USER);

        // 默认服务项评分项
        ScoreItem[] defaultServeItemScores = {
                new ScoreItem("1", "服务态度", 5.0),
                new ScoreItem("2", "服务质量", 5.0),
                new ScoreItem("3", "服务效率", 5.0)
        };
        // 默认服务人员评分项
        ScoreItem[] defaultServeProviderScores = {
                new ScoreItem("1", "专业能力", 5.0),
                new ScoreItem("2", "服务态度", 5.0),
                new ScoreItem("3", "准时到达", 5.0)
        };

        req.setServeItemEvaluationContent(DEFAULT_EVALUATION);
        req.setServeItemScoreItems(defaultServeItemScores);
        req.setServeProviderEvaluationContent(DEFAULT_EVALUATION);
        req.setServeProviderScoreItems(defaultServeProviderScores);
        req.setIsAnonymous(DEFAULT_ANONYMOUS_STATUS);
        req.setCurrentUserInfo(currentUserInfo);

        // 提交服务项评价
        EvaluationResDTO serveItemEvaluation = queryByTargetTypeIdAndOrdersId(
                currentUserInfo, evaluationProperties.getServeItem().getTargetTypeId(), req.getOrdersId());
        if (serveItemEvaluation == null) {
            submitServeItemEvaluation(req, currentUserInfo);
        }

        // 提交服务人员评价
        EvaluationResDTO serveProviderEvaluation = queryByTargetTypeIdAndOrdersId(
                currentUserInfo, evaluationProperties.getServeProvider().getTargetTypeId(), req.getOrdersId());
        if (serveProviderEvaluation == null) {
            submitServeProviderEvaluation(req, currentUserInfo);
        }
    }
}
