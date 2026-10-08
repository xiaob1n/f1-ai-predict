-- 评分状态与评分修订历史增量。
--   1) scoring_detail 必须区分“已评分得 0 分”与“迟到/无答案/取消/未评分”。
--   2) 答案修订后显式重算前，旧 scoring_detail / batch_total_score 归档到历史表，保留旧版本。
-- 本文件不改动 005 已部署 DDL；上线前须在隔离 MySQL 演练并备份，不应由应用启动或测试自动执行。
-- 须在 005_answer_scoring.sql 之后执行。
USE `f1_ai_predict`;

-- 不设默认值：写入方必须显式给出状态，避免漏写时被静默当作某个有效评分状态。
-- 仅 SCORED_CORRECT / SCORED_ZERO 计入 batch_total_score；其余状态 score 与 max_score 均为 0 且不进入分母。
ALTER TABLE `scoring_detail`
    ADD COLUMN `score_status` VARCHAR(16) NOT NULL
        COMMENT '评分状态：SCORED_CORRECT/SCORED_ZERO/LATE/NO_ANSWER/CANCELLED/UNSCORED',
    ADD CONSTRAINT `chk_scoring_detail_status` CHECK (`score_status` IN
        ('SCORED_CORRECT', 'SCORED_ZERO', 'LATE', 'NO_ANSWER', 'CANCELLED', 'UNSCORED'));

-- 评分明细历史：列与 scoring_detail（005 + 本文件 score_status）一致，另加归档时间与重算原因。
-- 不复制 uk_scoring_result：同一结果可有多个历史版本。
CREATE TABLE IF NOT EXISTS `scoring_detail_history` (
    `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '历史记录主键',
    `batch_id`             BIGINT UNSIGNED NOT NULL                COMMENT '批次 id（逻辑关联 prediction_batch.id）',
    `result_id`            BIGINT UNSIGNED NOT NULL                COMMENT '预测结果 id（逻辑关联 prediction_result.id）',
    `question_id`          BIGINT UNSIGNED NOT NULL                COMMENT '题目 id（逻辑关联 question.id）',
    `question_type`        VARCHAR(32)     NOT NULL                COMMENT '重算前题型',
    `score`                DECIMAL(10,4)   NOT NULL                COMMENT '重算前本题得分',
    `max_score`            DECIMAL(10,4)   NOT NULL                COMMENT '重算前本题满分',
    `partial_score`        DECIMAL(10,4)   NULL                    COMMENT '重算前部分位置得分',
    `scoring_rule_version` VARCHAR(32)     NOT NULL                COMMENT '重算前评分规则版本',
    `detail_json`          JSON            NULL                    COMMENT '重算前评分明细 JSON（含所用答案 content_hash）',
    `scored_at`            DATETIME(3)     NULL                    COMMENT '重算前评分时间(UTC)',
    `created_at`           DATETIME(3)     NOT NULL                COMMENT '当前态原始创建时间(UTC)',
    `updated_at`           DATETIME(3)     NOT NULL                COMMENT '重算前最近更新时间(UTC)',
    `score_status`         VARCHAR(16)     NOT NULL                COMMENT '重算前评分状态',
    `archived_at`          DATETIME(3)     NOT NULL                COMMENT '本次归档时间(UTC)',
    `revision_reason`      VARCHAR(64)     NOT NULL                COMMENT '显式重算原因',
    PRIMARY KEY (`id`),
    KEY `idx_scoring_history_batch_archived` (`batch_id`, `archived_at`),
    KEY `idx_scoring_history_result_archived` (`result_id`, `archived_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评分明细修订历史';

-- 批次总分历史：列与 batch_total_score 一致，另加归档时间与重算原因；不复制 uk_total_batch。
CREATE TABLE IF NOT EXISTS `batch_total_score_history` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '历史记录主键',
    `batch_id`          BIGINT UNSIGNED NOT NULL                COMMENT '批次 id（逻辑关联 prediction_batch.id）',
    `round_id`          BIGINT UNSIGNED NOT NULL                COMMENT '分站 id（逻辑关联 round.id）',
    `total_score`       DECIMAL(12,4)   NOT NULL                COMMENT '重算前批次总分',
    `max_score`         DECIMAL(12,4)   NOT NULL                COMMENT '重算前批次满分',
    `accuracy_rate`     DECIMAL(6,4)    NULL                    COMMENT '重算前准确率（0~1）',
    `type_stats_json`   JSON            NULL                    COMMENT '重算前统计 JSON',
    `created_at`        DATETIME(3)     NOT NULL                COMMENT '当前态原始创建时间(UTC)',
    `updated_at`        DATETIME(3)     NOT NULL                COMMENT '重算前最近更新时间(UTC)',
    `archived_at`       DATETIME(3)     NOT NULL                COMMENT '本次归档时间(UTC)',
    `revision_reason`   VARCHAR(64)     NOT NULL                COMMENT '显式重算原因',
    PRIMARY KEY (`id`),
    KEY `idx_total_history_batch_archived` (`batch_id`, `archived_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批次总分修订历史';
