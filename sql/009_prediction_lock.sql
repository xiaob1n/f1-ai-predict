-- 预测批次业务截止及锁定状态；须在 004_prediction.sql 之后执行。
-- data_cutoff 只约束特征可见性，不作为预测业务截止时间。
USE `f1_ai_predict`;

ALTER TABLE `prediction_batch`
    ADD COLUMN `prediction_deadline` DATETIME(3) NULL COMMENT '业务预测截止时间(UTC)，与数据可见性截止分离',
    ADD COLUMN `locked_at` DATETIME(3) NULL COMMENT '服务器实际锁定时间(UTC)',
    ADD COLUMN `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '批次锁定乐观锁版本';
