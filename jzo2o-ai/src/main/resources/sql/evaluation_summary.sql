-- AI 评价总结表 — 记录每次增量总结结果, 按评价目标唯一
CREATE TABLE IF NOT EXISTS `evaluation_summary` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `target_type_id` tinyint NOT NULL COMMENT '评价目标类型, 6=服务项, 7=服务人员',
    `target_id` bigint NOT NULL COMMENT '评价目标ID',
    `summary_content` text COMMENT 'AI 生成的总结文本',
    `last_evaluation_id` bigint DEFAULT NULL COMMENT '本次总结覆盖的最后一条评价ID',
    `last_evaluation_time` datetime DEFAULT NULL COMMENT '本次总结覆盖的最后一条评价时间',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_target` (`target_type_id`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI评价总结表';
