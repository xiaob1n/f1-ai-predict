package com.lbz.f1aipredict.prediction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 预测任务实体，映射 {@code prediction_job} 表。
 * 字段与 {@code sql/004_prediction.sql} 逐列对应：Java → RabbitMQ → Python 的异步任务状态机。
 * {@code prediction_job_id} 是业务幂等键（唯一键 uk_job_prediction_id）。
 * 状态列存 VARCHAR，取值复用 {@link com.lbz.f1aipredict.prediction.PredictionJobStatus}，
 * 本实体不承载状态更新方法。
 */
@Getter
@Setter
@TableName("prediction_job")
public class PredictionJob {

    /** 主键，自增（BIGINT UNSIGNED → Long） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务幂等键（UUID），Java/Python 双端幂等依据 */
    @TableField("prediction_job_id")
    private String predictionJobId;

    /** RabbitMQ 消息 ID（跨端排查追踪），本阶段可空 */
    @TableField("message_id")
    private String messageId;

    /** 所属批次 id（逻辑关联 prediction_batch.id） */
    @TableField("batch_id")
    private Long batchId;

    /** 题目 id（逻辑关联 question.id） */
    @TableField("question_id")
    private Long questionId;

    /** 使用的题目快照 id（逻辑关联 question_snapshot.id） */
    @TableField("question_snapshot_id")
    private Long questionSnapshotId;

    /** 任务数据截止时间(UTC)，DATETIME(3) → Instant */
    @TableField("data_cutoff")
    private Instant dataCutoff;

    /** 特征版本 */
    @TableField("feature_version")
    private String featureVersion;

    /** 模型版本 */
    @TableField("model_version")
    private String modelVersion;

    /** Prompt 版本 */
    @TableField("prompt_version")
    private String promptVersion;

    /** 状态机：PENDING/RUNNING/RETRYING/SUCCEEDED/FAILED/DEAD_LETTER，默认 PENDING */
    @TableField("status")
    private String status;

    /** 已重试次数，默认 0 */
    @TableField("retry_count")
    private Integer retryCount;

    /** 最大重试次数（超过进入 DEAD_LETTER），默认 3 */
    @TableField("max_retries")
    private Integer maxRetries;

    /** 实际处理的 Worker 节点标识 */
    @TableField("worker_node")
    private String workerNode;

    /** 最近一次失败原因 */
    @TableField("last_error")
    private String lastError;

    /** 预测锁定时间(UTC) */
    @TableField("locked_at")
    private Instant lockedAt;

    /** 完成时间(UTC) */
    @TableField("completed_at")
    private Instant completedAt;

    /** 创建时间(UTC) */
    @TableField("created_at")
    private Instant createdAt;

    /** 更新时间(UTC) */
    @TableField("updated_at")
    private Instant updatedAt;
}
