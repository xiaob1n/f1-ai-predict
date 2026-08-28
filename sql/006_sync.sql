-- ============================================================
-- 006：同步记录 / 原始响应留档 表结构
-- 依据可行性分析 5.1（Java 唯一数据管理方）、12（风险应对：
-- Feed 路径变化、来源限流 -> 原始响应留档 + 格式校验 + 同步告警）。
-- 业务要点：
--   - sync_record 记录每次 Feed 同步的幂等键（内容哈希）、状态与错误，
--     支撑去重、补采和审计。
--   - feed_raw_payload 保存原始响应 JSON，用于变化比对、二次解析与排查。
-- ============================================================

USE `f1_ai_predict`;

-- ------------------------------------------------------------
-- Feed 同步记录表（幂等 + 审计）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sync_record` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `source_type`    VARCHAR(32)     NOT NULL                COMMENT '数据源类型：LIMITS/SCHEDULE/QUESTIONS/MIXAPI/WEB_CONFIG/OPENF1',
    `source_url`     VARCHAR(512)    NOT NULL                COMMENT '数据源 URL',
    `gameday_id`     INT UNSIGNED    NULL                    COMMENT '相关 gamedayId（问题 Feed 时有值）',
    `content_hash`   CHAR(64)        NULL                    COMMENT '本次响应内容 SHA-256（判重：内容未变则跳过入库）',
    `status`         VARCHAR(32)     NOT NULL DEFAULT 'SUCCESS' COMMENT '状态：SUCCESS/SKIPPED_UNCHANGED/FAILED',
    `http_status`    INT UNSIGNED    NULL                    COMMENT 'HTTP 状态码',
    `error_message`  TEXT            NULL                    COMMENT '失败原因（非 SUCCESS 时记录）',
    `duration_ms`    INT UNSIGNED    NULL                    COMMENT '请求耗时（毫秒）',
    `synced_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '同步时间(UTC)',
    `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    PRIMARY KEY (`id`),
    KEY `idx_sync_source` (`source_type`, `synced_at`),
    KEY `idx_sync_hash` (`content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Feed 同步记录表（幂等与审计）';

-- ------------------------------------------------------------
-- Feed 原始响应留档表（response 原始 JSON 完整保存）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `feed_raw_payload` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `source_type`    VARCHAR(32)     NOT NULL                COMMENT '数据源类型（同 sync_record.source_type）',
    `source_url`     VARCHAR(512)    NOT NULL                COMMENT '数据源 URL',
    `gameday_id`     INT UNSIGNED    NULL                    COMMENT '相关 gamedayId（问题 Feed 时有值）',
    `content_hash`   CHAR(64)        NOT NULL                COMMENT '响应内容 SHA-256（唯一键，防重复入库）',
    `raw_json`       JSON            NOT NULL                COMMENT '原始响应 JSON 留档',
    `fetched_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '抓取时间(UTC)',
    `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_payload_hash` (`content_hash`),
    KEY `idx_payload_source` (`source_type`, `fetched_at`),
    KEY `idx_payload_gameday` (`gameday_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Feed 原始响应留档表';