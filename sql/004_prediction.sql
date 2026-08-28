-- ============================================================
-- 004：预测批次 / 预测任务 / 预测结果 / 结果选项 / 证据 表结构
-- 依据可行性分析 5.2（RabbitMQ 异步消息流）、7.1（单条预测输出结构）、
-- 6.5（消息体边界）。业务要点：
--   - prediction_job 是 RabbitMQ 任务侧记录，prediction_job_id 为幂等键，
--     与消息中 messageId、predictionJobId 对应；最终状态以本表为准。
--   - prediction_result 保存 Agent 结构化输出（含版本与截止时间），
--     预测必须绑定 optionId（见 prediction_result_item.selected_options）。
--   - 证据表保存来源 URL 与发布时间，支撑双端可追溯（anti-leakage）。
-- ============================================================

USE `f1_ai_predict`;

-- ------------------------------------------------------------
-- 预测批次表（一轮比赛的一次整轮预测）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `prediction_batch` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `round_id`        BIGINT UNSIGNED NOT NULL                COMMENT '分站 id（逻辑关联 round.id）',
    `batch_no`        INT UNSIGNED    NOT NULL                COMMENT '批次序号（同分站内自增）',
    `status`          VARCHAR(32)     NOT NULL DEFAULT 'PENDING' COMMENT '批次状态：PENDING/TASK_CREATED/PARTIAL/COMPLETED/FAILED',
    `data_cutoff`     DATETIME(3)     NULL                    COMMENT '本批次统一数据截止时间(UTC)，仅允许该时间前的数据',
    `question_count`  INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '批次内题目总数',
    `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    `updated_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_round_no` (`round_id`, `batch_no`),
    KEY `idx_batch_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预测批次表（每轮一次整轮预测）';

-- ------------------------------------------------------------
-- 预测任务表（Java -> RabbitMQ -> Python 的异步任务，状态机）
-- status 状态机：PENDING -> RUNNING -> SUCCEEDED
--                    |-> RETRYING -> (重试上限) FAILED / DEAD_LETTER
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `prediction_job` (
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `prediction_job_id`  VARCHAR(64)     NOT NULL                COMMENT '业务幂等键（UUID），Java/Python 双端幂等依据',
    `message_id`         VARCHAR(64)     NULL                    COMMENT 'RabbitMQ 消息 ID（跨端排查追踪）',
    `batch_id`           BIGINT UNSIGNED NOT NULL                COMMENT '所属批次 id（逻辑关联 prediction_batch.id）',
    `question_id`        BIGINT UNSIGNED NOT NULL                COMMENT '题目 id（逻辑关联 question.id）',
    `question_snapshot_id` BIGINT UNSIGNED NULL                  COMMENT '使用的题目快照 id（逻辑关联 question_snapshot.id）',
    `data_cutoff`        DATETIME(3)     NULL                    COMMENT '任务数据截止时间(UTC)',
    `feature_version`    VARCHAR(32)     NULL                    COMMENT '特征版本',
    `model_version`      VARCHAR(64)     NULL                    COMMENT '模型版本',
    `prompt_version`     VARCHAR(64)     NULL                    COMMENT 'Prompt 版本',
    `status`             VARCHAR(32)     NOT NULL DEFAULT 'PENDING' COMMENT '状态机：PENDING/RUNNING/RETRYING/SUCCEEDED/FAILED/DEAD_LETTER',
    `retry_count`        INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '已重试次数',
    `max_retries`        INT UNSIGNED    NOT NULL DEFAULT 3      COMMENT '最大重试次数（超过进入 DEAD_LETTER）',
    `worker_node`        VARCHAR(64)     NULL                    COMMENT '实际处理的 Worker 节点标识（家庭主机名）',
    `last_error`         TEXT            NULL                    COMMENT '最近一次失败原因',
    `locked_at`          DATETIME(3)     NULL                    COMMENT '预测锁定时间(UTC)（截止后锁定）',
    `completed_at`       DATETIME(3)     NULL                    COMMENT '完成时间(UTC)',
    `created_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    `updated_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_job_prediction_id` (`prediction_job_id`),
    KEY `idx_job_batch` (`batch_id`),
    KEY `idx_job_question` (`question_id`),
    KEY `idx_job_status` (`status`),
    KEY `idx_job_cutoff` (`data_cutoff`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预测任务表（RabbitMQ 异步任务状态机）';

-- ------------------------------------------------------------
-- 预测结果表（Agent 结构化输出主记录，与 7.1 字段对齐）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `prediction_result` (
    `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `job_id`              BIGINT UNSIGNED NOT NULL                COMMENT '关联任务 id（逻辑关联 prediction_job.id）',
    `question_id`         BIGINT UNSIGNED NOT NULL                COMMENT '题目 id（逻辑关联 question.id）',
    `question_snapshot_id` BIGINT UNSIGNED NULL                   COMMENT '实际使用的快照 id',
    `confidence`          DECIMAL(6,4)    NULL                    COMMENT 'Agent 置信度（0~1）',
    `reasoning_summary`   TEXT            NULL                    COMMENT '简短分析摘要',
    `source_data_cutoff`  DATETIME(3)     NOT NULL                COMMENT '实际使用的数据截止时间(UTC)（anti-leakage 硬边界）',
    `model`               VARCHAR(64)     NULL                    COMMENT '模型名称',
    `agent_version`       VARCHAR(32)     NULL                    COMMENT 'Agent 版本',
    `prompt_version`      VARCHAR(32)     NULL                    COMMENT 'Prompt 版本',
    `feature_version`     VARCHAR(32)     NULL                    COMMENT '特征版本',
    `raw_agent_response`  JSON            NULL                    COMMENT '原始 Agent 响应留档',
    `generated_at`        DATETIME(3)     NULL                    COMMENT '生成时间(UTC)',
    `locked_at`           DATETIME(3)     NULL                    COMMENT '锁定时间(UTC)',
    `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    `updated_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_result_job` (`job_id`),
    KEY `idx_result_question` (`question_id`),
    KEY `idx_result_cutoff` (`source_data_cutoff`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预测结果表（Agent 结构化输出，一任务至多一条最终结果）';

-- ------------------------------------------------------------
-- 预测结果-选中选项明细表（selectedOptions：选项 + 排序位置）
-- 支持单选 / 多选 / 排序题；numeric 题可仅存一条 position=1。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `prediction_result_item` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `result_id`    BIGINT UNSIGNED NOT NULL                COMMENT '结果 id（逻辑关联 prediction_result.id）',
    `option_id`    INT UNSIGNED    NOT NULL                COMMENT '选项 Id（对应 question_option.option_id）',
    `position`     INT UNSIGNED    NOT NULL                COMMENT '排序位置（1 开始；单选恒为 1）',
    `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_item_result_pos` (`result_id`, `position`),
    KEY `idx_item_option` (`option_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预测结果选中选项明细表';

-- ------------------------------------------------------------
-- 预测证据表（evidence：来源 URL、发布时间）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `prediction_evidence` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `result_id`     BIGINT UNSIGNED NOT NULL                COMMENT '结果 id（逻辑关联 prediction_result.id）',
    `source_name`   VARCHAR(128)    NULL                    COMMENT '数据来源名称',
    `source_url`    VARCHAR(512)    NULL                    COMMENT '证据来源 URL',
    `published_at`  DATETIME(3)     NULL                    COMMENT '证据发布时间(UTC)，须早于 source_data_cutoff',
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    PRIMARY KEY (`id`),
    KEY `idx_evidence_result` (`result_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预测证据来源表';