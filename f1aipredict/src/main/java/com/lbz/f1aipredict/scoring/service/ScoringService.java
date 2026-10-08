package com.lbz.f1aipredict.scoring.service;

import com.lbz.f1aipredict.scoring.entity.BatchTotalScore;

/** 预测批次评分业务接口；评分只读取预测结果与官方答案，结果不得回流到预测模型输入。 */
public interface ScoringService {

    /**
     * 对已锁定批次内全部预测结果评分，并在同一事务内写入评分明细与批次总分。
     * <p>
     * 迟到结果标记 LATE、未公布答案标记 NO_ANSWER，二者均不计入总分与满分。
     * 批次已有总分时不改动任何数据，直接返回既有总分，因此重复或并发调用只会留下一套有效评分。
     *
     * @param batchId 预测批次主键，批次必须已锁定
     * @return 本次写入或此前已存在的批次总分
     */
    BatchTotalScore scoreBatch(Long batchId);

    /**
     * 显式重算：答案修订后由调用方主动触发，绝不自动执行。
     * 同一事务内先把旧明细与旧总分归档到历史表，再删除并写入新的一套评分，失败整体回滚。
     *
     * @param batchId 预测批次主键，批次必须已锁定且已有评分
     * @param reason  重算原因，非空且不超过 64 个字符，记入历史表 revision_reason
     * @return 重算后的批次总分
     */
    BatchTotalScore rescoreBatch(Long batchId, String reason);
}
