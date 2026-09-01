package com.lbz.f1aipredict.sync.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.Instant;

/**
 * Feed 同步记录实体，映射 sync_record 表。
 * 记录每次 Feed 拉取的数据源、内容哈希、状态与错误信息，支撑幂等去重、补采与审计。
 * 字段与 sql/006_sync.sql 一一对应，不额外暴露无关列。
 */
@Getter
@Setter
@TableName("sync_record")
public class SyncRecord {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源类型：LIMITS/SCHEDULE/QUESTIONS/MIXAPI/WEB_CONFIG/OPENF1 */
    @TableField("source_type")
    private String sourceType;

    /** 数据源 URL */
    @TableField("source_url")
    private String sourceUrl;

    /** 相关 gamedayId（问题 Feed 时有值） */
    @TableField("gameday_id")
    private Integer gamedayId;

    /** 本次响应内容 SHA-256（判重：内容未变则跳过入库） */
    @TableField("content_hash")
    private String contentHash;

    /** 状态：SUCCESS/SKIPPED_UNCHANGED/FAILED */
    @TableField("status")
    private String status;

    /** HTTP 状态码 */
    @TableField("http_status")
    private Integer httpStatus;

    /** 失败原因（非 SUCCESS 时记录） */
    @TableField("error_message")
    private String errorMessage;

    /** 请求耗时（毫秒） */
    @TableField("duration_ms")
    private Integer durationMs;

    /** 同步时间(UTC) */
    @TableField("synced_at")
    private Instant syncedAt;

    /** 创建时间(UTC) */
    @TableField("created_at")
    private Instant createdAt;
}
