package com.lbz.f1aipredict.prediction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 预测任务对外详情 DTO。
 * <p>
 * 只暴露公开业务键 {@code prediction_job_id}，不返回数据库自增 id、messageId、lastError。
 * 时间字段均为 UTC {@link Instant}。状态为字符串，值与 SQL 004 任务状态注释一致。
 * REST JSON 键为 snake_case，Java 字段保持 camelCase。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionJobDto {

    /** 任务公开业务幂等键 */
    @JsonProperty("prediction_job_id")
    private String predictionJobId;

    /** 所属批次 ID */
    @JsonProperty("batch_id")
    private Long batchId;

    /** 题目 ID */
    @JsonProperty("question_id")
    private Long questionId;

    /** 创建时冻结的题目快照 ID */
    @JsonProperty("question_snapshot_id")
    private Long questionSnapshotId;

    /** 任务状态，如 PENDING / RUNNING / SUCCEEDED */
    @JsonProperty("status")
    private String status;

    /** 已重试次数 */
    @JsonProperty("retry_count")
    private Integer retryCount;

    /** 最大重试次数 */
    @JsonProperty("max_retries")
    private Integer maxRetries;

    /** 实际处理的 Worker 节点标识，可空 */
    @JsonProperty("worker_node")
    private String workerNode;

    /** 任务数据截止时间（UTC） */
    @JsonProperty("data_cutoff")
    private Instant dataCutoff;

    /** 创建时冻结的特征版本 */
    @JsonProperty("feature_version")
    private String featureVersion;

    /** 创建时冻结的模型版本 */
    @JsonProperty("model_version")
    private String modelVersion;

    /** 创建时冻结的 Prompt 版本 */
    @JsonProperty("prompt_version")
    private String promptVersion;

    /** 预测锁定时间（UTC），可空 */
    @JsonProperty("locked_at")
    private Instant lockedAt;

    /** 完成时间（UTC），可空 */
    @JsonProperty("completed_at")
    private Instant completedAt;

    /** 创建时间（UTC） */
    @JsonProperty("created_at")
    private Instant createdAt;

    /** 更新时间（UTC） */
    @JsonProperty("updated_at")
    private Instant updatedAt;
}
