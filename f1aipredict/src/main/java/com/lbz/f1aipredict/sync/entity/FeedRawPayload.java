package com.lbz.f1aipredict.sync.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.Instant;

/**
 * Feed 原始响应留档实体，映射 feed_raw_payload 表。
 * 按 content_hash 唯一保存原始 JSON，用于变化比对、二次解析与排查。
 * JSON 列以 String 承载（MyBatis 可将 JSON 映射为字符串），字段与 sql/006_sync.sql 一一对应。
 */
@Getter
@Setter
@TableName("feed_raw_payload")
public class FeedRawPayload {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源类型（同 sync_record.source_type） */
    @TableField("source_type")
    private String sourceType;

    /** 数据源 URL */
    @TableField("source_url")
    private String sourceUrl;

    /** 相关 gamedayId（问题 Feed 时有值） */
    @TableField("gameday_id")
    private Integer gamedayId;

    /** 响应内容 SHA-256（唯一键，防重复入库） */
    @TableField("content_hash")
    private String contentHash;

    /** 原始响应 JSON 留档 */
    @TableField("raw_json")
    private String rawJson;

    /** 抓取时间(UTC) */
    @TableField("fetched_at")
    private Instant fetchedAt;

    /** 创建时间(UTC) */
    @TableField("created_at")
    private Instant createdAt;
}
