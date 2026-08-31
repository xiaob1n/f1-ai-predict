package com.lbz.f1aipredict.question.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 题目列表查询参数 DTO。
 * <p>
 * 用于 GET /api/v1/rounds/{roundId}/questions 的查询条件绑定。
 * 字段均显式声明 camelCase {@link JsonProperty} 以锁定 JSON 序列化名称。
 * includeOptions 默认 true（未显式传 false 时附带回选项列表）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionQuery {

    /** 题目状态过滤条件，如 OPEN，可空表示不过滤 */
    @JsonProperty("status")
    private String status;

    /** 分站（gameday）过滤条件，可空表示不过滤 */
    @JsonProperty("gamedayId")
    private Integer gamedayId;

    /** 是否附带选项列表，默认 true */
    @JsonProperty("includeOptions")
    @Builder.Default
    private Boolean includeOptions = true;

    /** 指定快照 ID：仅返回该快照所属题目（须属于目标 round），可空 */
    @JsonProperty("snapshotId")
    private Long snapshotId;
}