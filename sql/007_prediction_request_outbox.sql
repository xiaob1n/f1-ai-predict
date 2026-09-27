-- 预测请求事务外投递记录；部署时先审核已应用的 001..006，再单独执行本文件。
-- 一任务一条不可变 v2 消息，投递状态与 prediction_job 的推理状态完全独立。
CREATE TABLE IF NOT EXISTS prediction_request_outbox (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    prediction_job_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    lease_token CHAR(36) DEFAULT NULL,
    lease_until DATETIME(3) DEFAULT NULL,
    last_error VARCHAR(128) DEFAULT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_outbox_prediction_job (prediction_job_id),
    UNIQUE KEY uk_outbox_message (message_id),
    KEY idx_outbox_due (status, next_attempt_at, id),
    KEY idx_outbox_lease (status, lease_until, id),
    CONSTRAINT fk_outbox_prediction_job FOREIGN KEY (prediction_job_id)
        REFERENCES prediction_job (prediction_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
