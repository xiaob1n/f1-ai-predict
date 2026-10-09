package com.lbz.f1aipredict.scoring.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.lbz.f1aipredict.scoring.entity.BatchTotalScore;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次总分的 REST 响应；JSON 键为 snake_case。
 * type_stats 原样输出评分服务生成的统计 JSON（其内部键沿用持久化时的 camelCase），不做二次转换。
 */
public record BatchTotalScoreDto(
        @JsonProperty("batch_id") Long batchId,
        @JsonProperty("round_id") Long roundId,
        @JsonProperty("total_score") BigDecimal totalScore,
        @JsonProperty("max_score") BigDecimal maxScore,
        @JsonProperty("accuracy_rate") BigDecimal accuracyRate,
        @JsonProperty("type_stats") @JsonRawValue String typeStats,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {

    /** 由总分实体转换。 */
    public static BatchTotalScoreDto from(BatchTotalScore total) {
        return new BatchTotalScoreDto(total.getBatchId(), total.getRoundId(), total.getTotalScore(),
                total.getMaxScore(), total.getAccuracyRate(), total.getTypeStatsJson(), total.getCreatedAt(),
                total.getUpdatedAt());
    }
}
