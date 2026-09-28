package com.lbz.f1aipredict.prediction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 预测结果引用的来源证据。 */
@Getter
@Setter
@TableName("prediction_evidence")
public class PredictionEvidence {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("result_id")
    private Long resultId;

    @TableField("source_type")
    private String sourceType;

    @TableField("source_name")
    private String sourceName;

    @TableField("source_url")
    private String sourceUrl;

    @TableField("first_seen_at")
    private Instant firstSeenAt;

    @TableField("event_time")
    private Instant eventTime;

    @TableField("document_id")
    private String documentId;

    @TableField("chunk_id")
    private String chunkId;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("created_at")
    private Instant createdAt;
}
