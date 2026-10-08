package com.lbz.f1aipredict.scoring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次总分，与 005 的 batch_total_score 逐列对应。
 * 评分规则版本、各状态题数与按题型得分统计保存在 type_stats_json。
 */
@Getter
@Setter
@TableName("batch_total_score")
public class BatchTotalScore {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("batch_id")
    private Long batchId;

    @TableField("round_id")
    private Long roundId;

    @TableField("total_score")
    private BigDecimal totalScore;

    @TableField("max_score")
    private BigDecimal maxScore;

    /** 准确率 0~1；没有任何计分题时为 null，以区别于“全部答错”的 0。 */
    @TableField("accuracy_rate")
    private BigDecimal accuracyRate;

    @TableField("type_stats_json")
    private String typeStatsJson;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
