-- 预测终态接收与持久化扩展；上线前须在隔离 MySQL 演练并备份。
-- 本文件仅定义增量 DDL，不应由应用启动或测试自动执行。

USE `f1_ai_predict`;

-- 成功结果映射 Python v2 字段，并保留完整版本与微秒级 UTC 时间。
ALTER TABLE `prediction_result`
    MODIFY COLUMN `confidence` DOUBLE NULL COMMENT 'Agent 置信度（0~1，保留 JSON 双精度值）',
    MODIFY COLUMN `source_data_cutoff` DATETIME(6) NOT NULL COMMENT '实际使用的数据截止时间(UTC)',
    MODIFY COLUMN `model` VARCHAR(128) NULL COMMENT '模型名称',
    MODIFY COLUMN `prompt_version` VARCHAR(128) NULL COMMENT 'Prompt 版本',
    MODIFY COLUMN `feature_version` VARCHAR(128) NULL COMMENT '特征版本',
    MODIFY COLUMN `generated_at` DATETIME(6) NULL COMMENT '生成时间(UTC)',
    ADD COLUMN `model_version` VARCHAR(128) NULL COMMENT '模型版本',
    ADD COLUMN `embedding_version` VARCHAR(128) NULL COMMENT 'Embedding 版本',
    ADD COLUMN `retriever_version` VARCHAR(128) NULL COMMENT 'Retriever 版本',
    ADD CONSTRAINT `chk_prediction_result_confidence`
        CHECK (`confidence` IS NULL OR (`confidence` >= 0 AND `confidence` <= 1));

-- Evidence firstSeenAt 与 eventTime 分开保存，不能用 publishedAt 代替。
ALTER TABLE `prediction_evidence`
    MODIFY COLUMN `source_name` VARCHAR(256) NULL COMMENT '数据来源名称',
    MODIFY COLUMN `source_url` VARCHAR(2048) NULL COMMENT '证据来源 URL',
    MODIFY COLUMN `published_at` DATETIME(6) NULL COMMENT '证据发布时间(UTC)',
    ADD COLUMN `source_type` VARCHAR(64) NULL COMMENT '来源类型',
    ADD COLUMN `first_seen_at` DATETIME(6) NULL COMMENT '证据首次可见时间(UTC)',
    ADD COLUMN `event_time` DATETIME(6) NULL COMMENT '证据所述事件时间(UTC)',
    ADD COLUMN `document_id` VARCHAR(256) NULL COMMENT '来源文档标识',
    ADD COLUMN `chunk_id` VARCHAR(256) NULL COMMENT '来源片段标识';

-- 每个任务仅接受一种终态；消息 ID 全局唯一，摘要用于区分相同 ID 的重投与冲突。
CREATE TABLE IF NOT EXISTS `prediction_outcome_receipt` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `job_id` BIGINT UNSIGNED NOT NULL COMMENT 'prediction_job.id',
    `message_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '结果或失败消息 ID',
    `outcome_type` VARCHAR(16) NOT NULL COMMENT 'RESULT 或 FAILURE',
    `payload_sha256` CHAR(64) NOT NULL COMMENT '规范化载荷 SHA-256',
    `trace_id` VARCHAR(128) NULL COMMENT '关联追踪标识',
    `received_at` DATETIME(6) NOT NULL COMMENT '接收时间(UTC)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录创建时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outcome_receipt_job` (`job_id`),
    UNIQUE KEY `uk_outcome_receipt_message` (`message_id`),
    CONSTRAINT `fk_outcome_receipt_job` FOREIGN KEY (`job_id`) REFERENCES `prediction_job` (`id`),
    CONSTRAINT `chk_outcome_receipt_type` CHECK (`outcome_type` IN ('RESULT', 'FAILURE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预测终态接收凭证';

-- 失败明细独立保留，避免仅将安全摘要写入 prediction_job.last_error。
CREATE TABLE IF NOT EXISTS `prediction_failure` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `job_id` BIGINT UNSIGNED NOT NULL COMMENT 'prediction_job.id',
    `message_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '失败消息 ID',
    `trace_id` VARCHAR(128) NULL COMMENT '关联追踪标识',
    `failure_code` VARCHAR(64) NOT NULL COMMENT '失败原因码',
    `summary` VARCHAR(512) NOT NULL COMMENT '安全失败摘要',
    `attempt` INT UNSIGNED NOT NULL COMMENT 'Worker 尝试次数',
    `generated_at` DATETIME(6) NOT NULL COMMENT '失败消息生成时间(UTC)',
    `schema_version` VARCHAR(16) NOT NULL COMMENT '消息 schema 版本',
    `model_version` VARCHAR(128) NULL COMMENT '消息携带的模型版本',
    `prompt_version` VARCHAR(128) NULL COMMENT '消息携带的 Prompt 版本',
    `feature_version` VARCHAR(128) NULL COMMENT '消息携带的特征版本',
    `embedding_version` VARCHAR(128) NULL COMMENT '消息携带的 Embedding 版本',
    `retriever_version` VARCHAR(128) NULL COMMENT '消息携带的 Retriever 版本',
    `source_data_cutoff` DATETIME(6) NULL COMMENT '消息携带的数据截止时间(UTC)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录创建时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prediction_failure_job` (`job_id`),
    UNIQUE KEY `uk_prediction_failure_message` (`message_id`),
    CONSTRAINT `fk_prediction_failure_job` FOREIGN KEY (`job_id`) REFERENCES `prediction_job` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预测最终失败详情';

-- 隔离原始消息供受控审计/重放；应用须在写入前限制 body 不超过 1 MiB。
CREATE TABLE IF NOT EXISTS `prediction_outcome_quarantine` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `message_id` VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '可解析时记录的消息 ID',
    `body_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始消息体 SHA-256',
    `reason_code` VARCHAR(64) NOT NULL COMMENT '隔离原因码，不含敏感消息内容',
    `raw_body` MEDIUMBLOB NOT NULL COMMENT '受限访问的原始消息体，应用限制不超过 1 MiB',
    `received_at` DATETIME(6) NOT NULL COMMENT '隔离时间(UTC)',
    `replay_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '重放状态：PENDING/REPLAYED/REJECTED',
    `replayed_at` DATETIME(6) NULL COMMENT '受控重放时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outcome_quarantine_body_reason` (`body_sha256`, `reason_code`),
    KEY `idx_outcome_quarantine_message` (`message_id`),
    CONSTRAINT `chk_outcome_quarantine_body_size` CHECK (OCTET_LENGTH(`raw_body`) <= 1048576),
    KEY `idx_outcome_quarantine_status` (`replay_status`, `received_at`),
    CONSTRAINT `chk_outcome_quarantine_replay_status` CHECK (`replay_status` IN ('PENDING', 'REPLAYED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预测终态消息隔离记录（需限制访问与设置保留期）';
