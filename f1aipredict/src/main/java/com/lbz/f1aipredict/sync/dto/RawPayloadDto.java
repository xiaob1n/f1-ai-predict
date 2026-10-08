package com.lbz.f1aipredict.sync.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Feed 原始响应留档 DTO。
 * <p>
 * 仅管理端 {@code GET /api/v1/admin/sync/raw-payloads/{payloadId}} 返回原始 JSON（raw_json），
 * 普通题目查询接口不得暴露原始 JSON。REST JSON 键使用 snake_case，Java 字段保持 camelCase；
 * raw_json 的字符串内容为 Feed 原文，其内部键名不做转换。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawPayloadDto {

    /** 留档主键 */
    @JsonProperty("payload_id")
    private Long payloadId;

    /** 数据源类型 */
    @JsonProperty("source_type")
    private String sourceType;

    /** 数据源 URL */
    @JsonProperty("source_url")
    private String sourceUrl;

    /** 相关比赛日 ID，可空 */
    @JsonProperty("gameday_id")
    private Integer gamedayId;

    /** 响应内容 SHA-256 */
    @JsonProperty("content_hash")
    private String contentHash;

    /** 原始响应 JSON 正文 */
    @JsonProperty("raw_json")
    private String rawJson;

    /** 抓取时间（UTC Instant） */
    @JsonProperty("fetched_at")
    private Instant fetchedAt;
}
