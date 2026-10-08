-- 官方答案修订历史；在 005_answer_scoring.sql 建立当前态后执行。
-- 每次更新当前态前于同一事务保存旧版本，以便审计与回放。
USE `f1_ai_predict`;

CREATE TABLE IF NOT EXISTS `official_answer_history` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '历史记录主键',
    `question_id`     BIGINT UNSIGNED NOT NULL COMMENT '题目 id（逻辑关联 question.id）',
    `gameday_id`      INT UNSIGNED NOT NULL COMMENT 'F1 Predict gamedayId',
    `raw_json`        JSON NULL COMMENT '修订前官方答案原始 JSON',
    `answer_content`  TEXT NULL COMMENT '修订前答案描述',
    `official_points` DECIMAL(8,2) NULL COMMENT '修订前官方积分',
    `published_at`    DATETIME(3) NULL COMMENT '修订前答案发布时间(UTC)',
    `content_hash`    CHAR(64) NULL COMMENT '修订前答案 SHA-256',
    `synced_at`       DATETIME(3) NOT NULL COMMENT '修订前同步入库时间(UTC)',
    `created_at`      DATETIME(3) NOT NULL COMMENT '当前态原始创建时间(UTC)',
    `updated_at`      DATETIME(3) NOT NULL COMMENT '修订前最近更新时间(UTC)',
    `archived_at`     DATETIME(3) NOT NULL COMMENT '本次归档时间(UTC)',
    `revision_reason` VARCHAR(16) NOT NULL COMMENT '归档来源：FEED 或 MANUAL',
    PRIMARY KEY (`id`),
    KEY `idx_answer_history_question_archived` (`question_id`, `archived_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='官方答案修订历史';
