package com.lbz.f1aipredict.prediction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.lbz.f1aipredict.prediction.dto.jackson.OffsetInstantDeserializer;
import com.lbz.f1aipredict.prediction.dto.validation.ValidCreatePredictionBatchSelection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 创建预测批次的请求体。
 * <p>
 * {@code questionIds} 与 {@code allOpenQuestions=true} 互斥：只能二选一。
 * {@code dataCutoff} 必须是带时区的 ISO-8601，反序列化后为 UTC {@link Instant}。
 * 版本字段长度与 {@code sql/004_prediction.sql} 中 VARCHAR 宽度一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ValidCreatePredictionBatchSelection
public class CreatePredictionBatchRequest {

    /** 单次显式指定题目的数量上限，防止一次拖垮后续查询。 */
    public static final int MAX_QUESTION_IDS = 100;

    /** {@code prediction_job.feature_version VARCHAR(32)} */
    public static final int FEATURE_VERSION_MAX_LENGTH = 32;

    /** {@code prediction_job.model_version VARCHAR(64)} */
    public static final int MODEL_VERSION_MAX_LENGTH = 64;

    /** {@code prediction_job.prompt_version VARCHAR(64)} */
    public static final int PROMPT_VERSION_MAX_LENGTH = 64;

    /**
     * 显式题目 ID 列表。null 表示未提供；空数组非法。
     * 与 {@link #allOpenQuestions}=true 互斥。
     */
    @JsonProperty("questionIds")
    @Size(max = MAX_QUESTION_IDS)
    private List<Long> questionIds;

    /**
     * 是否自动选取该分站全部可预测 OPEN 题目。
     * 仅 {@code true} 且未声明 questionIds 时合法；{@code false}/null 必须改走 questionIds。
     */
    @JsonProperty("allOpenQuestions")
    private Boolean allOpenQuestions;

    /** 本批次统一数据截止时间，必须带时区且最多精确到毫秒，入库为 UTC Instant。允许未来值。 */
    @JsonProperty("dataCutoff")
    @JsonDeserialize(using = OffsetInstantDeserializer.class)
    @NotNull
    private Instant dataCutoff;

    /** 特征版本，对应 SQL VARCHAR(32)，拒绝空白。 */
    @JsonProperty("featureVersion")
    @NotBlank
    @Size(max = FEATURE_VERSION_MAX_LENGTH)
    private String featureVersion;

    /** 模型版本，对应 SQL VARCHAR(64)，拒绝空白。 */
    @JsonProperty("modelVersion")
    @NotBlank
    @Size(max = MODEL_VERSION_MAX_LENGTH)
    private String modelVersion;

    /** Prompt 版本，对应 SQL VARCHAR(64)，拒绝空白。 */
    @JsonProperty("promptVersion")
    @NotBlank
    @Size(max = PROMPT_VERSION_MAX_LENGTH)
    private String promptVersion;
}
