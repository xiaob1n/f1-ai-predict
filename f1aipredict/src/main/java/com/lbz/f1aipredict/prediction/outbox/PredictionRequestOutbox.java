package com.lbz.f1aipredict.prediction.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 每个预测任务的不可变消息及独立投递状态。 */
@Getter
@Setter
@TableName("prediction_request_outbox")
public class PredictionRequestOutbox {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("prediction_job_id")
    private String predictionJobId;

    @TableField("message_id")
    private String messageId;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("status")
    private String status;

    @TableField("attempts")
    private Integer attempts;

    @TableField("next_attempt_at")
    private Instant nextAttemptAt;

    @TableField("lease_token")
    private String leaseToken;

    @TableField("lease_until")
    private Instant leaseUntil;

    @TableField("last_error")
    private String lastError;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
