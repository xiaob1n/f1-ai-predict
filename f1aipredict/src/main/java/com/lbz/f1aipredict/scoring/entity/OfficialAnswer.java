package com.lbz.f1aipredict.scoring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 官方答案当前态，与 005 中的 official_answer 表逐列对应。 */
@Getter
@Setter
@TableName("official_answer")
public class OfficialAnswer {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("question_id")
    private Long questionId;

    @TableField("gameday_id")
    private Integer gamedayId;

    @TableField("raw_json")
    private String rawJson;

    @TableField("answer_content")
    private String answerContent;

    @TableField("official_points")
    private BigDecimal officialPoints;

    /**
     * 官方发布时间(UTC)。仅当上游或人工修订显式给出时才有值；
     * NULL 表示 UNKNOWN，禁止用 createdAt / syncedAt 等本系统观察时间代替（D8）。
     */
    @TableField("published_at")
    private Instant publishedAt;

    @TableField("content_hash")
    private String contentHash;

    /** 最近一次写入当前态内容的时间(UTC)：Feed 内容变化或人工修订；哈希未变的重复同步不刷新。 */
    @TableField("synced_at")
    private Instant syncedAt;

    /**
     * 本系统首次观察到当前答案行的时间(UTC)：答案同步事务在拉取 Feed、写入原文、取得行锁之后读取的 Java 时钟，
     * 晚于实际拉取时刻且差值没有上界；Feed 修订内容不改变它。Feed 撤销会删除该行，重新公布时插入新行并重置；
     * 更早的值见 official_answer_history.created_at。不等于官方发布时间，也不是系统任何组件首次拿到答案的时间
     * （定时题目同步可能已把含答案的同端点原文写入 feed_raw_payload）。
     */
    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
