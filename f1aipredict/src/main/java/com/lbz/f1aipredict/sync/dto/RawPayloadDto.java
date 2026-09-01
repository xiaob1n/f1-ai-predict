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
 * 仅管理端 {@code GET /api/v1/admin/sync/raw-payloads/{payloadId}} 返回原始 JSON，
 * 普通题目查询接口不得暴露 rawJson。JSON 键使用 camelCase。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawPayloadDto {

    /** 留档主键 */
    @JsonProperty("payloadId")
    private Long payloadId;

    /** 数据源类型 */
    @JsonProperty("sourceType")
    private String sourceType;

    /** 数据源 URL */
    @JsonProperty("sourceUrl")
    private String sourceUrl;

    /** 相关比赛日 ID，可空 */
    @JsonProperty("gamedayId")
    private Integer gamedayId;

    /** 响应内容 SHA-256 */
    @JsonProperty("contentHash")
    private String contentHash;

    /** 原始响应 JSON 正文 */
    @JsonProperty("rawJson")
    private String rawJson;

    /** 抓取时间（UTC Instant） */
    @JsonProperty("fetchedAt")
    private Instant fetchedAt;
}
