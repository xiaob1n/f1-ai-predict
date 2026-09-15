package com.lbz.f1aipredict.prediction.service;

import com.lbz.f1aipredict.prediction.dto.PredictionJobDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobPageDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobQuery;

/**
 * 预测任务只读查询服务契约。
 * <p>
 * 任务列表与单任务业务键查询独立于批次详情服务，避免把 Todo 7 的职责扩展到批次聚合。
 */
public interface PredictionJobService {

    /**
     * 先确认批次存在，再按状态和 0-based 页码查询任务。
     *
     * @param batchId 批次主键
     * @param query   分页及状态过滤参数
     * @return 任务分页结果
     */
    PredictionJobPageDto listByBatchId(Long batchId, PredictionJobQuery query);

    /**
     * 按公开业务键查询单个任务，不开放数据库自增主键。
     *
     * @param predictionJobId 任务业务键
     * @return 任务公开 DTO
     */
    PredictionJobDto getByBusinessId(String predictionJobId);
}
