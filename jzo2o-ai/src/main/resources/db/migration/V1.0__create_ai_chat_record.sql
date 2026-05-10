CREATE DATABASE IF NOT EXISTS `jzo2o-ai` DEFAULT CHARSET utf8mb4;
-- AI聊天记录表
CREATE TABLE IF NOT EXISTS ai_chat_record (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT NOT NULL COMMENT '用户ID',
    user_type   TINYINT NOT NULL COMMENT '用户类型: 1-机构用户 2-服务人员',
    session_id  VARCHAR(64) NOT NULL COMMENT '会话ID, 同一会话消息聚合',
    role        VARCHAR(16) NOT NULL COMMENT '角色: user / assistant / system',
    content     TEXT NOT NULL COMMENT '消息内容',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_session (user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI聊天记录表';
