package com.lbz.f1aipredict.prediction.service;

import java.time.Instant;

/** 预测批次业务截止锁定与迟到判断；数据可见性截止不参与时间判断。 */
public interface PredictionLockService {

    /** 在业务截止时按服务器可信时钟锁定批次；重复调用不改变首次锁定时间。 */
    LockResult lockBatch(Long batchId, Instant lockTime);

    /** 仅查询实际锁定状态，不将业务截止自动视作已持久锁定。 */
    boolean isLocked(Long batchId);

    /** 到达时间严格晚于业务截止才算迟到，等于截止仍有效。 */
    boolean isLate(Long batchId, Instant resultTime);

    /** 纯时间判定：严格晚于业务截止才迟到，等于截止有效；任一时间为空则拒绝请求。 */
    boolean isLate(Instant predictionDeadline, Instant resultTime);

    /** 首次锁定与重复锁定均返回原始冻结时点。 */
    record LockResult(boolean newlyLocked, Instant lockedAt, Instant predictionDeadline) {
    }
}
