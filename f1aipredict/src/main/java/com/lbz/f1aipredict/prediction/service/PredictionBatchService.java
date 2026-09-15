package com.lbz.f1aipredict.prediction.service;

import com.lbz.f1aipredict.prediction.dto.CreatePredictionBatchRequest;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDetailDto;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDto;

/**
 * 预测批次服务契约。
 * <p>
 * 当前阶段负责批次创建和批次详情只读查询，不包含消息发布或任务分页查询。
 */
public interface PredictionBatchService {

    /**
     * 为指定分站创建新的预测批次。
     *
     * @param roundId 分站主键
     * @param request 创建参数
     * @return 已落库的批次与任务业务键
     */
    PredictionBatchDto create(Long roundId, CreatePredictionBatchRequest request);

    /**
     * 查询批次详情，并在同一次状态聚合查询中返回任务实时计数。
     *
     * @param batchId 批次主键
     * @return 批次详情及任务状态计数
     */
    PredictionBatchDetailDto getById(Long batchId);
}
