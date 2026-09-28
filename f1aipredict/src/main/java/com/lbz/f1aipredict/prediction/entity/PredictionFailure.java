package com.lbz.f1aipredict.prediction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 预测任务的最终失败详情，不存储底层异常堆栈。 */
@Getter
@Setter
@TableName("prediction_failure")
public class PredictionFailure {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("job_id")
    private Long jobId;

    @TableField("message_id")
    private String messageId;

    @TableField("trace_id")
    private String traceId;

    @TableField("failure_code")
    private String failureCode;

    @TableField("summary")
    private String summary;

    @TableField("attempt")
    private Integer attempt;

    @TableField("generated_at")
    private Instant generatedAt;

    @TableField("schema_version")
    private String schemaVersion;

    @TableField("model_version")
    private String modelVersion;

    @TableField("prompt_version")
    private String promptVersion;

    @TableField("feature_version")
    private String featureVersion;

    @TableField("embedding_version")
    private String embeddingVersion;

    @TableField("retriever_version")
    private String retrieverVersion;

    @TableField("source_data_cutoff")
    private Instant sourceDataCutoff;

    @TableField("created_at")
    private Instant createdAt;
}
