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
 * 用于指定快照查询与快照历史接口的返回。camelCase 显式 {@link JsonProperty}。
 * 仅返回 rawJson 是否存在（hasRawJson），绝不回传原始 Feed JSON 本体。
 * options 默认初始化为空列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionSnapshotDto {

    /** 快照 ID */
    @JsonProperty("snapshotId")
    private Long snapshotId;

    /** 快照所属题目 ID */
    @JsonProperty("questionId")
    private Long questionId;

    /** 快照序号，用于排序展示 */
    @JsonProperty("snapshotNo")
    private Integer snapshotNo;

    /** 快照内容哈希，用于内容校验 */
    @JsonProperty("contentHash")
    private String contentHash;

    /** 快照生成原因，可空 */
    @JsonProperty("snapshotReason")
    private String snapshotReason;

    /** 快照创建时间（UTC，ISO-8601） */
    @JsonProperty("createdAt")
    private Instant createdAt;

    /** 原始 Feed JSON 是否存在，不回传本体 */
    @JsonProperty("hasRawJson")
    private Boolean hasRawJson;

    /** 该快照下的选项列表，默认空列表 */
    @JsonProperty("options")
    @Builder.Default
    private List<QuestionOptionDto> options = new ArrayList<>();
}