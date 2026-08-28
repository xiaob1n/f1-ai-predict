-- ============================================================
-- 003：题目 / 题目快照 / 题目选项 表结构
-- 依据可行性分析 4.1 / 4.2：F1 Predict question Feed 字段。
-- 业务要点：题目内容变化时保留历史快照（question_snapshot），
--           题目本体保存当前状态与最近同步信息；选项挂在快照下。
-- Feed 字段映射：Id -> source_question_id，No -> question_no，
--               Text -> question_text，SubText -> sub_text，
--               OptionTemplateId -> option_template_id，
--               Config.ChoiceLimit -> choice_limit。
-- ============================================================

USE `f1_ai_predict`;

-- ------------------------------------------------------------
-- 题目表（当前状态）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `question` (
    `id`                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    `round_id`            BIGINT UNSIGNED  NOT NULL                COMMENT '所属分站 id（逻辑关联 round.id）',
    `gameday_id`          INT UNSIGNED     NOT NULL                COMMENT 'F1 Predict gamedayId（指定轮次）',
    `source_question_id`  INT UNSIGNED     NOT NULL                COMMENT 'Feed 中的题目唯一 Id',
    `question_no`         INT UNSIGNED     NULL                    COMMENT '题目序号 No',
    `question_text`       TEXT             NOT NULL                COMMENT '问题正文 Text',
    `sub_text`            TEXT             NULL                    COMMENT '补充说明 SubText',
    `option_template_id`  INT UNSIGNED     NULL                    COMMENT '题型标识 OptionTemplateId',
    `choice_limit`        INT UNSIGNED     NULL                    COMMENT '可选答案数量 Config.ChoiceLimit',
    `status`              VARCHAR(32)      NULL                    COMMENT '题目状态 Status（OPEN/CLOSED 等）',
    `content_hash`        CHAR(64)         NULL                    COMMENT '题目内容 SHA-256 哈希（去重与快照比对）',
    `latest_snapshot_id`  BIGINT UNSIGNED  NULL                    COMMENT '最新快照 id（逻辑关联 question_snapshot.id）',
    `first_seen_at`       DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次同步时间(UTC)',
    `last_synced_at`      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近同步时间(UTC)',
    `created_at`          DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    `updated_at`          DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_question_source` (`gameday_id`, `source_question_id`),
    KEY `idx_question_round` (`round_id`),
    KEY `idx_question_template` (`option_template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F1 Predict 预测题目表（当前状态）';

-- ------------------------------------------------------------
-- 题目快照表（保留内容变化历史，支撑可追溯性）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `question_snapshot` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question_id`     BIGINT UNSIGNED NOT NULL                COMMENT '题目 id（逻辑关联 question.id）',
    `snapshot_no`     INT UNSIGNED    NOT NULL                COMMENT '快照序号（同题内自增，从 1 开始）',
    `content_hash`    CHAR(64)        NOT NULL                COMMENT '快照内容 SHA-256（判重：相同则不新增强制快照）',
    `raw_json`        JSON            NULL                    COMMENT 'Feed 题目原始 JSON 留档',
    `snapshot_reason` VARCHAR(64)     NULL                    COMMENT '快照原因：INITIAL/CHANGED/ANSWER_UPDATE',
    `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '快照生成时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_snapshot_no` (`question_id`, `snapshot_no`),
    KEY `idx_snapshot_hash` (`content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题目快照表（历史版本留档）';

-- ------------------------------------------------------------
-- 题目选项表（挂在快照下）
-- Feed 字段：Options[].Id 不可靠时用行号，Points -> points，
--            Chance -> chance，选项文本 -> option_text。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `question_option` (
    `id`              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    `snapshot_id`     BIGINT UNSIGNED  NOT NULL                COMMENT '所属快照 id（逻辑关联 question_snapshot.id）',
    `option_no`       INT UNSIGNED     NOT NULL                COMMENT '选项序号（快照内自增，对应 Feed Options 数组下标,从0开始）',
    `option_id`       INT UNSIGNED     NULL                    COMMENT 'Feed 选项 Id（若有）',
    `option_text`     VARCHAR(255)     NULL                    COMMENT '选项文本',
    `points`          INT              NULL                    COMMENT '该选项对应积分 Options[].Points',
    `chance`          DECIMAL(6,4)     NULL                    COMMENT '网站提供的概率指标 Options[].Chance（0~1 或百分比，按源值存储）',
    `is_answer`       TINYINT(1)       NOT NULL DEFAULT 0      COMMENT '是否为官方答案（结算后由 official_answer 回填）',
    `created_at`      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_option_snapshot` (`snapshot_id`, `option_no`),
    KEY `idx_option_snapshot` (`snapshot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题目选项表（按快照版本存储）';