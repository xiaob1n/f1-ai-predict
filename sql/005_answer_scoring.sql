-- ============================================================
-- 005：官方答案 / 评分明细 / 批次总分 表结构
-- 依据可行性分析 2.1（自动评分）、7.1、14（验收标准）。
-- 业务要点：
--   - official_answer 保存比赛结束后的官方答案（原始留档 + 结构化）。
--   - scoring_detail 按题型（单选/排序/数值/区间）记录评分明细，
--     评分规则版本化，支持用历史轮次回归验证。
--   - batch_total_score 保存整轮预测批次的总分与统计。
-- ============================================================

USE `f1_ai_predict`;

-- ------------------------------------------------------------
-- 官方答案表（比赛结束后自动同步）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `official_answer` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question_id`      BIGINT UNSIGNED NOT NULL                COMMENT '题目 id（逻辑关联 question.id）',
    `gameday_id`       INT UNSIGNED    NOT NULL                COMMENT 'F1 Predict gamedayId',
    `raw_json`         JSON            NULL                    COMMENT '官方答案原始 JSON 留档',
    `answer_content`   TEXT            NULL                    COMMENT '答案内容可读化描述',
    `official_points`  DECIMAL(8,2)    NULL                    COMMENT '官方积分（若提供）',
    `published_at`     DATETIME(3)     NULL                    COMMENT '官方答案发布/同步时间(UTC)',
    `content_hash`     CHAR(64)        NULL                    COMMENT '答案内容 SHA-256（判重与审计）',
    `synced_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '同步入库时间(UTC)',
    `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_answer_question` (`question_id`),
    KEY `idx_answer_gameday` (`gameday_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='官方答案表';

-- ------------------------------------------------------------
-- 评分明细表（每道题目一条评分记录，题型专属计分）
-- score 为该题得分；detail_json 保存逐选项/逐位置得分明细，
-- 便于按题型（单选/多选/排序/数值/区间）统计。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `scoring_detail` (
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `batch_id`           BIGINT UNSIGNED NOT NULL                COMMENT '批次 id（逻辑关联 prediction_batch.id）',
    `result_id`          BIGINT UNSIGNED NOT NULL                COMMENT '预测结果 id（逻辑关联 prediction_result.id）',
    `question_id`        BIGINT UNSIGNED NOT NULL                COMMENT '题目 id（逻辑关联 question.id）',
    `question_type`      VARCHAR(32)     NOT NULL                COMMENT '题型：SINGLE/MULTIPLE/RANKING/NUMERIC/RANGE/UNKNOWN',
    `score`              DECIMAL(10,4)   NOT NULL DEFAULT 0      COMMENT '本题得分',
    `max_score`          DECIMAL(10,4)   NOT NULL DEFAULT 0      COMMENT '本题满分',
    `partial_score`      DECIMAL(10,4)   NULL                    COMMENT '部分位置得分（排序题等）',
    `scoring_rule_version` VARCHAR(32)   NOT NULL                COMMENT '评分规则版本（支持回归验证）',
    `detail_json`        JSON            NULL                    COMMENT '评分明细 JSON（逐选项/逐位置）',
    `scored_at`          DATETIME(3)     NULL                    COMMENT '评分时间(UTC)',
    `created_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    `updated_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scoring_result` (`result_id`),
    KEY `idx_scoring_batch` (`batch_id`),
    KEY `idx_scoring_question` (`question_id`),
    KEY `idx_scoring_type` (`question_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评分明细表（按题型计分，规则版本化）';

-- ------------------------------------------------------------
-- 批次总分表（单场/赛季/全局准确率统计的数据基础）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `batch_total_score` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `batch_id`          BIGINT UNSIGNED NOT NULL                COMMENT '批次 id（逻辑关联 prediction_batch.id）',
    `round_id`          BIGINT UNSIGNED NOT NULL                COMMENT '分站 id（逻辑关联 round.id）',
    `total_score`       DECIMAL(12,4)   NOT NULL DEFAULT 0      COMMENT '本批次总分',
    `max_score`         DECIMAL(12,4)   NOT NULL DEFAULT 0      COMMENT '本批次满分',
    `accuracy_rate`     DECIMAL(6,4)    NULL                    COMMENT '准确率（0~1）',
    `type_stats_json`   JSON            NULL                    COMMENT '按题型统计 JSON（各题型得分/满分）',
    `created_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    `updated_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_total_batch` (`batch_id`),
    KEY `idx_total_round` (`round_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批次总分统计表';