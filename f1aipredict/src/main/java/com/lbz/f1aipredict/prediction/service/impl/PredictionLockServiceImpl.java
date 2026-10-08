package com.lbz.f1aipredict.prediction.service.impl;

import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.prediction.entity.PredictionBatch;
import com.lbz.f1aipredict.prediction.mapper.PredictionBatchMapper;
import com.lbz.f1aipredict.prediction.service.PredictionLockService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/** 借助数据库条件更新保证同一批次只会留下首次锁定时刻。 */
@Service
public class PredictionLockServiceImpl implements PredictionLockService {

    private final PredictionBatchMapper batchMapper;

    public PredictionLockServiceImpl(PredictionBatchMapper batchMapper) {
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper must not be null");
    }

    @Override
    public LockResult lockBatch(Long batchId, Instant lockTime) {
        validateBatchId(batchId);
        if (lockTime == null || lockTime.getNano() % 1_000_000 != 0) {
            throw new InvalidRequestException("lockTime must be a server time with millisecond precision");
        }
        PredictionBatch batch = findBatch(batchId);
        if (batch.getLockedAt() != null) {
            return new LockResult(false, batch.getLockedAt(), batch.getPredictionDeadline());
        }
        Instant deadline = requireDeadline(batch);
        if (lockTime.isBefore(deadline)) {
            throw new InvalidRequestException("Prediction deadline has not been reached");
        }
        if (batch.getLockVersion() == null) {
            throw new IllegalStateException("Prediction batch lock version is missing");
        }
        // 数据库比较截止、空锁定时刻与版本；并发时只有首次条件更新可成功。
        if (batchMapper.lockIfUnchanged(batchId, batch.getLockVersion(), lockTime) == 1) {
            return new LockResult(true, lockTime, deadline);
        }
        PredictionBatch current = findBatch(batchId);
        if (current.getLockedAt() != null) {
            return new LockResult(false, current.getLockedAt(), current.getPredictionDeadline());
        }
        throw new IllegalStateException("Prediction batch lock update failed");
    }

    @Override
    public boolean isLocked(Long batchId) {
        validateBatchId(batchId);
        return findBatch(batchId).getLockedAt() != null;
    }

    @Override
    public boolean isLate(Long batchId, Instant resultTime) {
        validateBatchId(batchId);
        if (resultTime == null) {
            throw new InvalidRequestException("resultTime is required");
        }
        return isLate(findBatch(batchId).getPredictionDeadline(), resultTime);
    }

    @Override
    public boolean isLate(Instant predictionDeadline, Instant resultTime) {
        if (predictionDeadline == null || resultTime == null) {
            throw new InvalidRequestException("predictionDeadline and resultTime are required");
        }
        // 只比较业务截止与服务端结果时间，不依赖特征截止或实际加锁时刻。
        return resultTime.isAfter(predictionDeadline);
    }

    private PredictionBatch findBatch(Long batchId) {
        PredictionBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("Prediction batch not found: " + batchId);
        }
        return batch;
    }

    private static Instant requireDeadline(PredictionBatch batch) {
        if (batch.getPredictionDeadline() == null) {
            throw new InvalidRequestException("Prediction deadline is not configured");
        }
        return batch.getPredictionDeadline();
    }

    private static void validateBatchId(Long batchId) {
        if (batchId == null || batchId <= 0) {
            throw new InvalidRequestException("batchId must be positive");
        }
    }
}
