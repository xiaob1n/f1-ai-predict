package com.lbz.f1aipredict.prediction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lbz.f1aipredict.prediction.service.PredictionLockService.LockResult;

import java.time.Instant;

/** 预测批次管理端锁定操作的响应。 */
public record PredictionBatchLockDto(
        @JsonProperty("newlyLocked") boolean newlyLocked,
        @JsonProperty("lockedAt") Instant lockedAt,
        @JsonProperty("predictionDeadline") Instant predictionDeadline) {

    /** 将领域锁定结果转换为 REST DTO。 */
    public static PredictionBatchLockDto from(LockResult result) {
        return new PredictionBatchLockDto(result.newlyLocked(), result.lockedAt(), result.predictionDeadline());
    }
}
