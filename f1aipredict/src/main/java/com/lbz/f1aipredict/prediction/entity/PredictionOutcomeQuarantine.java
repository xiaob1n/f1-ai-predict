package com.lbz.f1aipredict.prediction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 被隔离的预测终态消息原文及受控重放状态。 */
@Getter
@Setter
@TableName("prediction_outcome_quarantine")
public class PredictionOutcomeQuarantine {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("message_id")
    private String messageId;

    @TableField("body_sha256")
    private String bodySha256;

    @TableField("reason_code")
    private String reasonCode;

    @TableField("raw_body")
    private byte[] rawBody;

    @TableField("received_at")
    private Instant receivedAt;

    @TableField("replay_status")
    private String replayStatus;

    @TableField("replayed_at")
    private Instant replayedAt;
}
