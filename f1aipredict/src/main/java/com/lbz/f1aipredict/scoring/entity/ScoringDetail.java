package com.lbz.f1aipredict.scoring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 单题评分明细，与 005 的 scoring_detail 及 011 新增的 score_status 逐列对应。 */
@Getter
@Setter
@TableName("scoring_detail")
public class ScoringDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("batch_id")
    private Long batchId;

    @TableField("result_id")
    private Long resultId;

    @TableField("question_id")
    private Long questionId;

    @TableField("question_type")
    private String questionType;

    @TableField("score")
    private BigDecimal score;

    @TableField("max_score")
    private BigDecimal maxScore;

    @TableField("partial_score")
    private BigDecimal partialScore;

    @TableField("scoring_rule_version")
    private String scoringRuleVersion;

    @TableField("detail_json")
    private String detailJson;

    @TableField("scored_at")
    private Instant scoredAt;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("score_status")
    private String scoreStatus;
}
