-- ============================================================
-- 002：赛季 / 分站 / Session 表结构
-- 依据可行性分析 3.2 / 5.x：Java Spring Boot 统一管理赛季、分站、Session。
-- 注意：一个 Grand Prix（分站）可能包含多个 Session，
--       不能将每个 RaceId 直接当作独立分站。
-- 时间约定：一律 UTC。
-- ============================================================

USE `f1_ai_predict`;

-- ------------------------------------------------------------
-- 赛季表（如 2026 赛季）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `season` (
    `id`            BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT COMMENT '主键',
    `year`          SMALLINT UNSIGNED  NOT NULL                COMMENT '赛季年份，如 2026',
    `name`          VARCHAR(128)       NOT NULL                COMMENT '赛季名称，如 2026 FIA Formula One World Championship',
    `status`        VARCHAR(20)        NOT NULL DEFAULT 'UPCOMING' COMMENT '状态：UPCOMING/IN_PROGRESS/FINISHED',
    `start_date`    DATE               NULL                    COMMENT '赛季开始日期(UTC)',
    `end_date`      DATE               NULL                    COMMENT '赛季结束日期(UTC)',
    `created_at`    DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    `updated_at`    DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_season_year` (`year`),
    KEY `idx_season_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F1 赛季表';

-- ------------------------------------------------------------
-- 分站表（一个 Grand Prix 轮次）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `round` (
    `id`               BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `season_id`        BIGINT UNSIGNED   NOT NULL                COMMENT '赛季 id（逻辑关联 season.id，不建物理外键）',
    `round_number`     SMALLINT UNSIGNED NOT NULL                COMMENT '分站序号（第几站），如 3',
    `grand_prix_name`  VARCHAR(128)      NOT NULL                COMMENT '大奖赛名称，如 Monaco Grand Prix',
    `official_name`    VARCHAR(255)      NULL                    COMMENT '官方全名',
    `circuit_name`     VARCHAR(128)      NULL                    COMMENT '赛道名称',
    `country`          VARCHAR(64)       NULL                    COMMENT '国家/地区',
    `locality`         VARCHAR(128)      NULL                    COMMENT '比赛城市',
    `start_date`       DATE              NULL                    COMMENT '分站开始日期(UTC)',
    `end_date`         DATE              NULL                    COMMENT '分站结束日期(UTC)',
    `status`           VARCHAR(20)       NOT NULL DEFAULT 'SCHEDULED' COMMENT '状态：SCHEDULED/IN_PROGRESS/FINISHED/CANCELLED',
    `created_at`       DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    `updated_at`       DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_round_season_no` (`season_id`, `round_number`),
    KEY `idx_round_season` (`season_id`),
    KEY `idx_round_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分站表（大奖赛轮次）';

-- ------------------------------------------------------------
-- 分站下的会议/Session 表（对应 OpenF1 meeting/session）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `meeting_session` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `round_id`       BIGINT UNSIGNED NOT NULL                COMMENT '所属分站 id（逻辑关联 round.id）',
    `meeting_key`    INT UNSIGNED    NULL                    COMMENT 'OpenF1 meeting_key（会议标识）',
    `session_key`    INT UNSIGNED    NULL                    COMMENT 'OpenF1 session_key（会话标识）',
    `session_name`   VARCHAR(64)     NOT NULL                COMMENT 'Session 名称，如 Practice 1 / Qualifying / Race',
    `session_type`   VARCHAR(32)     NULL                    COMMENT 'Session 类型，如 Practice/Qualifying/Race',
    `gameday_id`     INT UNSIGNED    NULL                    COMMENT 'F1 Predict 当前比赛 gamedayId（如存在）',
    `start_date_utc` DATETIME(3)     NULL                    COMMENT '开始时间(UTC)',
    `end_date_utc`   DATETIME(3)     NULL                    COMMENT '结束时间(UTC)',
    `status`         VARCHAR(20)     NOT NULL DEFAULT 'SCHEDULED' COMMENT '状态：SCHEDULED/IN_PROGRESS/FINISHED',
    `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    `updated_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_key` (`session_key`),
    KEY `idx_session_round` (`round_id`),
    KEY `idx_session_meeting` (`meeting_key`),
    KEY `idx_session_gameday` (`gameday_id`),
    KEY `idx_session_start` (`start_date_utc`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分站下的比赛 Session 表';