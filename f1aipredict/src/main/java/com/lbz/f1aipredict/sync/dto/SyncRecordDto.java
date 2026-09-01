package com.lbz.f1aipredict.sync.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 同步审计记录 DTO。
 * <p>
 * 管理端查询 {@code GET /api/v1/admin/sync/records} 的列表项，
 * 对应 {@code sync_record} 表公开字段；绝不直接返回实体。
 * JSON 键使用 camelCase，与 {@link com.lbz.f1aipredict.question.dto.QuestionDto} 风格一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncRecordDto {

    /** 同步记录主键 */
    @JsonProperty("syncRecordId")
    private Long syncRecordId;

    /** 数据源类型，如 SCHEDULE / LIMITS / QUESTIONS */
    @JsonProperty("sourceType")
    private String sourceType;

    /** 数据源 URL */
    @JsonProperty("sourceUrl")
    private String sourceUrl;

    /** 相关比赛日 ID（题目 Feed 时有值） */
    @JsonProperty("gamedayId")
    private Integer gamedayId;

    /** 本次响应内容 SHA-256 */
    @JsonProperty("contentHash")
    private String contentHash;

    /** 同步状态：SUCCESS / SKIPPED_UNCHANGED / FAILED */
    @JsonProperty("status")
    private String status;

    /** HTTP 状态码 */
    @JsonProperty("httpStatus")
    private Integer httpStatus;

    /** 失败原因或跳过说明 */
    @JsonProperty("errorMessage")
    private String errorMessage;

    /** 请求耗时（毫秒） */
    @JsonProperty("durationMs")
    private Integer durationMs;

    /** 同步时间（UTC Instant） */
    @JsonProperty("syncedAt")
    private Instant syncedAt;
}
