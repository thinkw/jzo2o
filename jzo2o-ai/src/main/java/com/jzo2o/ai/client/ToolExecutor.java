package com.jzo2o.ai.client;

import cn.hutool.json.JSONUtil;
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
            default: {
                log.warn("未知远程工具: {}", toolName);
                return JSONUtil.toJsonStr(Map.of("error", "未知工具: " + toolName));
            }
        }
    }
}
