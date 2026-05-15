package com.jzo2o.ai.service.impl;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jzo2o.ai.client.AiEngineWebSocketClient;
import com.jzo2o.ai.mapper.EvaluationSummaryMapper;
import com.jzo2o.ai.model.domain.EvaluationSummary;
import com.jzo2o.ai.service.EvaluationSummaryService;
import com.jzo2o.api.customer.EvaluationApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * AI 评价总结服务实现 — 增量模式
 */
@Slf4j
@Service
public class EvaluationSummaryServiceImpl implements EvaluationSummaryService {

    @Resource
    private AiEngineWebSocketClient wsClient;

    @Resource
    private EvaluationSummaryMapper summaryMapper;

    @Resource
    private EvaluationApi evaluationApi;

    @Override
    public String getSummary(Integer targetTypeId, Long targetId) {
        EvaluationSummary summary = summaryMapper.selectOne(
                new LambdaQueryWrapper<EvaluationSummary>()
                        .eq(EvaluationSummary::getTargetTypeId, targetTypeId)
                        .eq(EvaluationSummary::getTargetId, targetId));
        return summary != null ? summary.getSummaryContent() : null;
    }

    @Override
    public String summarize(Integer targetTypeId, Long targetId) {
        // 1. 查询上次总结
        EvaluationSummary prev = summaryMapper.selectOne(
                new LambdaQueryWrapper<EvaluationSummary>()
                        .eq(EvaluationSummary::getTargetTypeId, targetTypeId)
                        .eq(EvaluationSummary::getTargetId, targetId));

        LocalDateTime afterTime = null;
        String prevSummary = "";
        if (prev != null && prev.getLastEvaluationTime() != null) {
            afterTime = prev.getLastEvaluationTime();
            prevSummary = prev.getSummaryContent();
            log.info("增量总结: targetTypeId={}, targetId={}, 上次总结时间={}", targetTypeId, targetId, afterTime);
        } else {
            log.info("首次总结: targetTypeId={}, targetId={}", targetTypeId, targetId);
        }

        // 2. 检查是否有新评价
        log.info("查询新评价: targetTypeId={}, targetId={}, afterTime={}", targetTypeId, targetId, afterTime);
        String newEvaluationsJson;
        try {
            newEvaluationsJson = evaluationApi.queryByTargetIdAndTime(
                    targetTypeId, targetId,
                    afterTime != null ? afterTime.toString() : null);
        } catch (Exception e) {
            log.error("查询评价失败 (Feign调用异常): targetTypeId={}, targetId={}", targetTypeId, targetId, e);
            throw new RuntimeException("查询评价数据失败: " + e.getMessage(), e);
        }
        log.info("评价查询返回: length={}, preview={}",
                newEvaluationsJson != null ? newEvaluationsJson.length() : 0,
                newEvaluationsJson != null ? newEvaluationsJson.substring(0, Math.min(200, newEvaluationsJson.length())) : "null");

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Map<String, Object>> newEvals = (List) JSONUtil.toList(newEvaluationsJson, Map.class);
        if (newEvals == null || newEvals.isEmpty()) {
            log.info("无新评价, 跳过总结: targetTypeId={}, targetId={}", targetTypeId, targetId);
            throw new RuntimeException("该目标暂无新评价数据");
        }
        log.info("发现 {} 条新评价: targetTypeId={}, targetId={}", newEvals.size(), targetTypeId, targetId);

        // 3. 构建 prompt
        String targetLabel = targetTypeId == 7 ? "服务人员" : "服务项";
        String prompt;
        if (!prevSummary.isEmpty()) {
            prompt = String.format(
                    "你是云岚到家平台的评价分析助手。请根据以下信息为%s(ID=%d)更新评价总结。\n" +
                    "## 上次评价总结\n%s\n\n## 新增评价 (共%d条)\n%s\n\n" +
                    "请将新增评价的内容与上次总结合并, 生成一份完整、结构化的评价总结。" +
                    "总结应包括: 整体评价趋势、用户满意度、优点、待改进点、关键词标签。" +
                    "如果新评价中包含具体服务人员名字, 请务必在总结中体现。",
                    targetLabel, targetId, prevSummary, newEvals.size(), newEvaluationsJson);
        } else {
            prompt = String.format(
                    "你是云岚到家平台的评价分析助手。请根据以下评价内容为%s(ID=%d)生成评价总结。\n" +
                    "## 评价列表 (共%d条)\n%s\n\n" +
                    "请生成一份结构化的评价总结, 包括: 整体评价趋势、用户满意度、优点、待改进点、关键词标签。" +
                    "如果评价中包含具体服务人员名字, 请务必在总结中体现。",
                    targetLabel, targetId, newEvals.size(), newEvaluationsJson);
        }

        // 4. 通过 WebSocket 调用 AI
        String sessionId = "eval-summary-" + UUID.randomUUID().toString().substring(0, 8);
        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt));
        try {
            CompletableFuture<String> future = wsClient.connectAndCollect(sessionId, messages);
            String summary = future.get(180, TimeUnit.SECONDS);
            log.info("AI 总结完成: targetTypeId={}, targetId={}, 长度={}", targetTypeId, targetId,
                    summary != null ? summary.length() : 0);

            // 5. 保存/更新总结
            LocalDateTime now = LocalDateTime.now();
            // 取最后一条评价的时间作为游标 (JSON 中可能是 Long 时间戳或 String)
            Map<String, Object> lastEval = newEvals.get(newEvals.size() - 1);
            Object lastTimeObj = lastEval.get("createTime");
            LocalDateTime lastTime;
            if (lastTimeObj instanceof String) {
                lastTime = LocalDateTimeUtil.parse((String) lastTimeObj);
            } else if (lastTimeObj instanceof Number) {
                long ts = ((Number) lastTimeObj).longValue();
                // JSON 序列化可能是秒级或毫秒级时间戳, 以 10^10 为界区分
                lastTime = LocalDateTimeUtil.of(ts < 10_000_000_000L ? ts * 1000 : ts);
            } else {
                lastTime = now;
            }
            Object lastIdObj = lastEval.get("id");
            Long lastId;
            if (lastIdObj instanceof Number) {
                lastId = ((Number) lastIdObj).longValue();
            } else {
                lastId = Long.valueOf(String.valueOf(lastIdObj));
            }

            if (prev == null) {
                prev = new EvaluationSummary();
                prev.setTargetTypeId(targetTypeId);
                prev.setTargetId(targetId);
                prev.setSummaryContent(summary);
                prev.setLastEvaluationId(lastId);
                prev.setLastEvaluationTime(lastTime);
                summaryMapper.insert(prev);
            } else {
                prev.setSummaryContent(summary);
                prev.setLastEvaluationId(lastId);
                prev.setLastEvaluationTime(lastTime);
                summaryMapper.updateById(prev);
            }

            return summary;
        } catch (Exception e) {
            log.error("AI 总结失败: targetTypeId={}, targetId={}", targetTypeId, targetId, e);
            throw new RuntimeException("AI 评价总结失败: " + e.getMessage(), e);
        }
    }
}
