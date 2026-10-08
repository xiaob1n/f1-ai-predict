package com.lbz.f1aipredict.question.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 题目快照 DTO。
 * <p>
 * 用于指定快照查询与快照历史接口的返回。snake_case 显式 {@link JsonProperty}，Java 字段保持 camelCase。
 * 仅返回原始 JSON 是否存在（has_raw_json），绝不回传原始 Feed JSON 本体（raw_json）。
 * options 默认初始化为空列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionSnapshotDto {

    /** 快照 ID */
    @JsonProperty("snapshot_id")
    private Long snapshotId;

    /** 快照所属题目 ID */
    @JsonProperty("question_id")
    private Long questionId;

    /** 快照序号，用于排序展示 */
    @JsonProperty("snapshot_no")
    private Integer snapshotNo;

    /** 快照内容哈希，用于内容校验 */
    @JsonProperty("content_hash")
    private String contentHash;

    /** 快照生成原因，可空 */
    @JsonProperty("snapshot_reason")
    private String snapshotReason;

    /** 快照创建时间（UTC，ISO-8601） */
    @JsonProperty("created_at")
    private Instant createdAt;

    /** 原始 Feed JSON 是否存在，不回传本体 */
    @JsonProperty("has_raw_json")
    private Boolean hasRawJson;

    /** 该快照下的选项列表，默认空列表 */
    @JsonProperty("options")
    @Builder.Default
    private List<QuestionOptionDto> options = new ArrayList<>();
}