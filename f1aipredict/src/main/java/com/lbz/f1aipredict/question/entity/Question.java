package com.lbz.f1aipredict.question.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.Instant;

/**
 * 题库问题实体，映射 question 表。
 * 保存问题的当前状态（最新一轮的游戏日、题号、题目文案、选项模板与生效状态等），
 * 并通过 latest_snapshot_id 指向当前生效的问题快照 QuestionSnapshot，
 * 实现"当前状态表 + 历史快照表"的读写分离：状态随每次同步更新，历史不可变。
 */
@Getter
@Setter
@TableName("question")
public class Question {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("round_id")
    private Long roundId;

    @TableField("gameday_id")
    private Integer gamedayId;

    @TableField("source_question_id")
    private Integer sourceQuestionId;

    @TableField("question_no")
    private Integer questionNo;

    @TableField("question_text")
    private String questionText;

    @TableField("sub_text")
    private String subText;

    @TableField("option_template_id")
    private Integer optionTemplateId;

    @TableField("choice_limit")
    private Integer choiceLimit;

    @TableField("status")
    private String status;

    @TableField("content_hash")
    private String contentHash;

    @TableField("latest_snapshot_id")
    private Long latestSnapshotId;

    @TableField("first_seen_at")
    private Instant firstSeenAt;

    @TableField("last_synced_at")
    private Instant lastSyncedAt;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}