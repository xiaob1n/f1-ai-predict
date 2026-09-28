package com.lbz.f1aipredict.prediction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 预测终态消息的幂等接收凭证。 */
@Getter
@Setter
@TableName("prediction_outcome_receipt")
public class PredictionOutcomeReceipt {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("job_id")
    private Long jobId;

    @TableField("message_id")
    private String messageId;

    @TableField("outcome_type")
    private String outcomeType;

    @TableField("payload_sha256")
    private String payloadSha256;

    @TableField("trace_id")
    private String traceId;

    @TableField("received_at")
    private Instant receivedAt;

    @TableField("created_at")
    private Instant createdAt;
}
