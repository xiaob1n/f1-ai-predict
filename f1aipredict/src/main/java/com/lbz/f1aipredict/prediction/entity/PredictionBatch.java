package com.lbz.f1aipredict.prediction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 预测批次实体，映射 {@code prediction_batch} 表。
 * 字段与 {@code sql/004_prediction.sql} 逐列对应：一轮比赛的一次整轮预测。
 * 状态列存 VARCHAR，取值复用 {@link com.lbz.f1aipredict.prediction.PredictionBatchStatus}，
 * 本实体不承载状态流转。
 */
@Getter
@Setter
@TableName("prediction_batch")
public class PredictionBatch {

    /** 主键，自增（BIGINT UNSIGNED → Long） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分站 id（逻辑关联 round.id，不建物理外键） */
    @TableField("round_id")
    private Long roundId;

    /** 批次序号（同分站内自增；唯一键 uk_batch_round_no） */
    @TableField("batch_no")
    private Integer batchNo;

    /** 批次状态：PENDING/TASK_CREATED/PARTIAL/COMPLETED/FAILED，默认 PENDING */
    @TableField("status")
    private String status;

    /** 本批次统一数据截止时间(UTC)，DATETIME(3) → Instant */
    @TableField("data_cutoff")
    private Instant dataCutoff;

    /** 批次内题目总数，默认 0 */
    @TableField("question_count")
    private Integer questionCount;

    /** 创建时间(UTC) */
    @TableField("created_at")
    private Instant createdAt;

    /** 更新时间(UTC) */
    @TableField("updated_at")
    private Instant updatedAt;
}
