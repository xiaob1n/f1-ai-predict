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
 * REST JSON 键使用 snake_case，与 {@link com.lbz.f1aipredict.question.dto.QuestionDto} 风格一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncRecordDto {

    /** 同步记录主键 */
    @JsonProperty("sync_record_id")
    private Long syncRecordId;

    /** 数据源类型，如 SCHEDULE / LIMITS / QUESTIONS */
    @JsonProperty("source_type")
    private String sourceType;

    /** 数据源 URL */
    @JsonProperty("source_url")
    private String sourceUrl;

    /** 相关比赛日 ID（题目 Feed 时有值） */
    @JsonProperty("gameday_id")
    private Integer gamedayId;

    /** 本次响应内容 SHA-256 */
    @JsonProperty("content_hash")
    private String contentHash;

    /** 同步状态：SUCCESS / SKIPPED_UNCHANGED / FAILED；答案登记可为 RUNNING 或 SKIPPED_SUPERSEDED */
    @JsonProperty("status")
    private String status;

    /** HTTP 状态码 */
    @JsonProperty("http_status")
    private Integer httpStatus;

    /** 失败原因或跳过说明 */
    @JsonProperty("error_message")
    private String errorMessage;

    /** 请求耗时（毫秒） */
    @JsonProperty("duration_ms")
    private Integer durationMs;

    /** 同步时间（UTC Instant） */
    @JsonProperty("synced_at")
    private Instant syncedAt;
}
