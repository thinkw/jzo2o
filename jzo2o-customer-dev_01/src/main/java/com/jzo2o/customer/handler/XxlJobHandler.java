package com.jzo2o.customer.handler;

import com.jzo2o.api.AiApi;
import com.jzo2o.customer.model.domain.Evaluation;
import com.jzo2o.customer.service.EvaluationService;
import com.jzo2o.rabbitmq.plugins.RabbitMqResender;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * xxl-job定时任务
 */
@Component
@Slf4j
public class XxlJobHandler {

    @Resource
    private RabbitMqResender rabbitMqResender;

    @Resource
    private AiApi aiApi;

    @Resource
    private EvaluationService evaluationService;

    /**
     * rabbitmq异常消息拉取并重新发回队列
     */
    @XxlJob("rabbitmqErrorMsgPullAndResend")
    public void rabbitmqErrorMsgPullAndResend() {
        log.debug("rabbitmq异常消息重新");
        for (int count = 0; count < 100; count++) {
            try {
                if (!rabbitMqResender.getOneMessageAndProcess()) {
                    break;
                }
            } catch (Exception e) {
                log.error("rabbitmq异常消息拉取失败,e:", e);
            }
        }
    }

    /**
     * 评价 AI 总结定时任务 — 每周为有新增评价的服务人员生成/更新总结
     */
    @XxlJob("evaluationSummaryJob")
    public void evaluationSummaryJob() {
        log.info("评价 AI 总结定时任务开始");
        java.time.LocalDateTime weekAgo = java.time.LocalDateTime.now().minusDays(7);
        List<Evaluation> recentEvals = evaluationService
                .queryByTargetIdAndTime(7, null, weekAgo);
        // 收集不重复的服务人员ID
        Set<Long> providerIds = new HashSet<>();
        for (Evaluation e : recentEvals) {
            if (e.getTargetId() != null) {
                providerIds.add(e.getTargetId());
            }
        }
        log.info("发现 {} 个服务人员有新增评价, 共 {} 条", providerIds.size(), recentEvals.size());
        int successCount = 0;
        for (Long targetId : providerIds) {
            try {
                Map<String, String> result = aiApi.summarizeEvaluation(7, targetId);
                if (result != null && result.get("summary") != null && !result.get("summary").isEmpty()) {
                    successCount++;
                }
            } catch (Exception e) {
                log.error("服务人员 {} 评价总结失败", targetId, e);
            }
        }
        log.info("评价 AI 总结定时任务完成, 成功={}/{}, 失败={}",
                successCount, providerIds.size(), providerIds.size() - successCount);
    }
}
