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
                // LLM 可能传 String 或 Integer, 统一转换为 Long
                Long orderId = toLong(args.get("order_id"));
                if (orderId == null) {
                    return toError("缺少参数 order_id");
                }
                try {
                    OrderResDTO order = ordersApi.queryById(orderId);
                    if (order == null) {
                        return toError("订单不存在: " + orderId);
                    }
                    return safeToJson(order);
                } catch (Exception e) {
                    return toError("查询订单失败: " + e.getMessage());
                }
            }
            case "get_evaluation_summary": {
                // 读取上次 AI 总结内容 (evaluation_summary 表)
                Integer targetTypeId = toInt(args.get("target_type_id"));
                Long targetId = toLong(args.get("target_id"));
                if (targetTypeId == null || targetId == null) {
                    return toError("缺少参数 target_type_id 或 target_id");
                }
                EvaluationSummary summary = evaluationSummaryMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EvaluationSummary>()
                                .eq(EvaluationSummary::getTargetTypeId, targetTypeId)
                                .eq(EvaluationSummary::getTargetId, targetId)
                );
                if (summary == null) {
                    return safeToJson(Map.of(
                            "has_previous", false,
                            "summary", "",
                            "last_evaluation_time", ""));
                }
                return safeToJson(Map.of(
                        "has_previous", true,
                        "summary", summary.getSummaryContent() != null ? summary.getSummaryContent() : "",
                        "last_evaluation_time", summary.getLastEvaluationTime() != null
                                ? summary.getLastEvaluationTime().toString() : ""));
            }
            case "query_evaluations": {
                // 查询指定目标的新增评价 (Feign → jzo2o-customer)
                Integer targetTypeId = toInt(args.get("target_type_id"));
                Long targetId = toLong(args.get("target_id"));
                if (targetTypeId == null || targetId == null) {
                    return toError("缺少参数 target_type_id 或 target_id");
                }
                String afterTime = args.get("after_time") instanceof String
                        ? (String) args.get("after_time")
                        : null;
                try {
                    String result = evaluationApi.queryByTargetIdAndTime(targetTypeId, targetId, afterTime);
                    return result != null ? result : toError("查询评价返回为空");
                } catch (Exception e) {
                    return toError("查询评价失败: " + e.getMessage());
                }
            }
            default: {
                log.warn("未知远程工具: {}", toolName);
                return toError("未知工具: " + toolName);
            }
        }
    }

    /** 生成错误 JSON, 保证永不为 null */
    private static String toError(String msg) {
        return "{\"error\": \"" + (msg != null ? msg.replace("\"", "'") : "unknown") + "\"}";
    }

    /** 安全 JSON 序列化, 保证永不为 null */
    private static String safeToJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        return JSONUtil.toJsonStr(obj);
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
