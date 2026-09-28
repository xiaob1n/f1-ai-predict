package com.lbz.f1aipredict.prediction.service;

import com.lbz.f1aipredict.prediction.dto.PredictionJobOutcomeDto;

/** 预测任务结果与安全失败详情的只读查询服务契约。 */
public interface PredictionJobOutcomeService {

    /**
     * 按公开任务业务键查询当前状态及其成功结果或安全失败详情。
     *
     * @param predictionJobId 预测任务公开业务键
     * @return 当前状态与终态详情；未完成时 result 和 failure 均为空
     */
    PredictionJobOutcomeDto getByBusinessId(String predictionJobId);
}
