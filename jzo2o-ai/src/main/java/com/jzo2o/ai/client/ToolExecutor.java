package com.jzo2o.ai.client;

import cn.hutool.json.JSONUtil;
import com.jzo2o.ai.mapper.EvaluationSummaryMapper;
import com.jzo2o.ai.model.domain.EvaluationSummary;
import com.jzo2o.api.customer.EvaluationApi;
import com.jzo2o.api.orders.OrdersApi;
import com.jzo2o.api.orders.dto.response.OrderResDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 远程工具执行器 — 根据 Python Agent 发来的 tool_call 帧调用对应的业务服务
 * <p>
 * 当前阶段用 switch 路由, 工具超过 5 个后再抽象为 ToolRegistry 模式。
 */
@Slf4j
@Component
public class ToolExecutor {

    @Resource
    private OrdersApi ordersApi;

    @Resource
    private EvaluationApi evaluationApi;

    @Resource
    private EvaluationSummaryMapper evaluationSummaryMapper;

    /**
     * 执行指定工具, 返回 JSON 字符串结果给 Python Agent
     *
     * @param toolName    Python tools.py 中定义的工具名
     * @param toolCallId  LLM 生成的 tool_call_id (仅用于日志)
     * @param args       工具参数
     * @return 工具执行结果, JSON 字符串. 出错时返回 {"error": "..."}
     */
    public String execute(String toolName, String toolCallId, Map<String, Object> args) {
        log.info("执行远程工具: {} (id={}), args={}", toolName, toolCallId, args);

        switch (toolName) {
            case "customer_order_query": {
                String orderIdStr = (String) args.get("order_id");
                Long orderId = Long.valueOf(orderIdStr);
                OrderResDTO order = ordersApi.queryById(orderId);
                return JSONUtil.toJsonStr(order);
            }
            case "get_evaluation_summary": {
                // 读取上次 AI 总结内容 (evaluation_summary 表)
                Integer targetTypeId = toInt(args.get("target_type_id"));
                Long targetId = toLong(args.get("target_id"));
                EvaluationSummary summary = evaluationSummaryMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EvaluationSummary>()
                                .eq(EvaluationSummary::getTargetTypeId, targetTypeId)
                                .eq(EvaluationSummary::getTargetId, targetId)
                );
                if (summary == null) {
                    return JSONUtil.toJsonStr(Map.of(
                            "has_previous", false,
                            "summary", "",
                            "last_evaluation_time", ""));
                }
                return JSONUtil.toJsonStr(Map.of(
                        "has_previous", true,
                        "summary", summary.getSummaryContent() != null ? summary.getSummaryContent() : "",
                        "last_evaluation_time", summary.getLastEvaluationTime() != null
                                ? summary.getLastEvaluationTime().toString() : ""));
            }
            case "query_evaluations": {
                // 查询指定目标的新增评价 (Feign → jzo2o-customer)
                Integer targetTypeId = toInt(args.get("target_type_id"));
                Long targetId = toLong(args.get("target_id"));
                String afterTime = (String) args.get("after_time");  // ISO 时间字符串, 可能为空
                return evaluationApi.queryByTargetIdAndTime(targetTypeId, targetId, afterTime);
            }
            default: {
                log.warn("未知远程工具: {}", toolName);
                return JSONUtil.toJsonStr(Map.of("error", "未知工具: " + toolName));
            }
        }
    }

    private static Integer toInt(Object val) {
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof String) return Integer.valueOf((String) val);
        if (val instanceof Number) return ((Number) val).intValue();
        return null;
    }

    private static Long toLong(Object val) {
        if (val instanceof Long) return (Long) val;
        if (val instanceof Integer) return ((Integer) val).longValue();
        if (val instanceof String) return Long.valueOf((String) val);
        if (val instanceof Number) return ((Number) val).longValue();
        return null;
    }
}
