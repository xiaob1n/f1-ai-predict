package com.lbz.f1aipredict.sync.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次 Feed 同步结果（最小字段集，后续 Todo 9 再扩展）。
 * JSON 键使用 camelCase，与 QuestionDto 风格一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncResultDto {

    /** 数据源类型，如 SCHEDULE */
    @JsonProperty("sourceType")
    private String sourceType;

    /** 同步状态：SUCCESS / SKIPPED_UNCHANGED / FAILED */
    @JsonProperty("status")
    private String status;

    /** 本次响应 SHA-256（64 位小写十六进制；拉取失败时可空） */
    @JsonProperty("contentHash")
    private String contentHash;

    /** 本次写入的 sync_record 主键 */
    @JsonProperty("syncRecordId")
    private Long syncRecordId;

    /** 本次关联的 feed_raw_payload 主键（拉取失败时为空） */
    @JsonProperty("payloadId")
    private Long payloadId;

    /** 失败原因或跳过说明（成功且无跳过时为空） */
    @JsonProperty("errorMessage")
    private String errorMessage;

    /** 当前比赛日 ID（syncCurrent 从 limits 解析；子结果可空） */
    @JsonProperty("gamedayId")
    private Integer gamedayId;

    /** 串行子结果：赛程同步（仅 CURRENT 汇总时有值） */
    @JsonProperty("scheduleResult")
    private SyncResultDto scheduleResult;

    /** 串行子结果：题目同步（仅 CURRENT 汇总时有值） */
    @JsonProperty("questionsResult")
    private SyncResultDto questionsResult;
}
