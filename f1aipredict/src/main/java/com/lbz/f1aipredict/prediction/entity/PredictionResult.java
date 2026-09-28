package com.lbz.f1aipredict.prediction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 预测成功结果主记录。 */
@Getter
@Setter
@TableName("prediction_result")
public class PredictionResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("job_id")
    private Long jobId;

    @TableField("question_id")
    private Long questionId;

    @TableField("question_snapshot_id")
    private Long questionSnapshotId;

    @TableField("confidence")
    private Double confidence;

    @TableField("reasoning_summary")
    private String reasoningSummary;

    @TableField("source_data_cutoff")
    private Instant sourceDataCutoff;

    @TableField("model")
    private String model;

    @TableField("model_version")
    private String modelVersion;

    @TableField("agent_version")
    private String agentVersion;

    @TableField("prompt_version")
    private String promptVersion;

    @TableField("feature_version")
    private String featureVersion;

    @TableField("embedding_version")
    private String embeddingVersion;

    @TableField("retriever_version")
    private String retrieverVersion;

    @TableField("raw_agent_response")
    private String rawAgentResponse;

    @TableField("generated_at")
    private Instant generatedAt;

    @TableField("locked_at")
    private Instant lockedAt;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
