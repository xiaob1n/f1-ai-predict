package com.lbz.f1aipredict.question.service;

import java.util.List;

/**
 * 预测创建所需的题目内部只读批量读取契约。
 * <p>
 * 供 prediction 域在创建批次前冻结题目、最新快照与选项；
 * 调用方只依赖本接口，不得直接注入 question Mapper，也不得循环调用
 * {@link QuestionService#getDetail(Long)}。
 * <p>
 * 本接口不改变公开 REST {@link QuestionService} 的四个只读方法。
 */
public interface PredictionQuestionReadService {

    /**
     * 按分站批量加载可预测题目视图。
     * <p>
     * {@code questionIds == null} 为 OPEN 自动模式：只返回 OPEN、最新快照存在且选项非空的题目，
     * 无可预测项时返回空列表。<br>
     * {@code questionIds != null} 为显式模式：对不存在、跨 round、非 OPEN、无最新快照、空选项
     * 分类失败并整体抛出 {@code InvalidRequestException}，不静默跳过。
     * 两种模式的返回顺序均为 {@code questionNo ASC, id ASC}。
     *
     * @param roundId     分站 ID，必填
     * @param questionIds 显式题目 ID；null 表示自动模式；空列表非法
     * @return 不可变预测输入视图列表
     */
    List<PredictionQuestionView> loadForPrediction(Long roundId, List<Long> questionIds);
}
