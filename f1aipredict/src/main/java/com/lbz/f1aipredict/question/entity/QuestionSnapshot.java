package com.lbz.f1aipredict.question.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.Instant;

/**
 * 问题快照实体，映射 question_snapshot 表。
 * 保存问题在某次同步时的不可变历史快照，question_id 关联 Question，
 * snapshot_no 递增表示第 N 版快照；raw_json 仅用于持久化原始同步数据（不做业务解析），
 * content_hash 用于对比内容是否变化。历史数据仅供回放与审计，不参与实时业务写入。
 */
@Getter
@Setter
@TableName("question_snapshot")
public class QuestionSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("question_id")
    private Long questionId;

    @TableField("snapshot_no")
    private Integer snapshotNo;

    @TableField("content_hash")
    private String contentHash;

    @TableField("raw_json")
    private String rawJson;

    @TableField("snapshot_reason")
    private String snapshotReason;

    @TableField("created_at")
    private Instant createdAt;
}