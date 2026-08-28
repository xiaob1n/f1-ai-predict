-- ============================================================
-- F1 AI Predict 业务数据库初始化脚本（001）
-- 目标数据库：MySQL 8.0.x
-- 字符集：utf8mb4 / utf8mb4_unicode_ci（支持完整 Unicode 与中文）
-- 说明：业务库保存赛程、题目快照、预测批次、Agent 输出、
--       官方答案与评分等强一致业务数据；OpenF1 原始数据仍在 MongoDB。
-- 时间约定：数据库一律存储 UTC 时间，接口层再按客户端时区展示。
-- ============================================================

-- 创建业务数据库（若不存在则不重复创建）
CREATE DATABASE IF NOT EXISTS `f1_ai_predict`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- 切换到目标数据库
USE `f1_ai_predict`;