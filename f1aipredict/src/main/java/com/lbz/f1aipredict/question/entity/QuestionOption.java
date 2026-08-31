package com.lbz.f1aipredict.question.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.Instant;

/**
 * 问题选项实体，映射 question_option 表。
 * 选项以 snapshot_id 归属到某个问题快照 QuestionSnapshot（同一快照内 option_no 唯一），
 * option_id 为来源 Feed 侧的选项 ID，仅在快照范围内有含义，并非全局唯一；
 * is_answer 标记是否为正确答案，points/chance 为得分与预测概率。
 */
@Getter
@Setter
@TableName("question_option")
public class QuestionOption {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("snapshot_id")
    private Long snapshotId;

    @TableField("option_no")
    private Integer optionNo;

    @TableField("option_id")
    private Integer optionId;

    @TableField("option_text")
    private String optionText;

    @TableField("points")
    private Integer points;

    @TableField("chance")
    private java.math.BigDecimal chance;

    @TableField("is_answer")
    private Boolean isAnswer;

    @TableField("created_at")
    private Instant createdAt;
}