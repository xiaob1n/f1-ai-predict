package com.lbz.f1aipredict.scoring.engine;

import com.lbz.f1aipredict.scoring.ScoreStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 单题评分规则。实现必须是无 IO 的纯函数，迟到、取消等批次上下文由调用方在评分前判定。
 */
public interface ScoringEngine {

    /** 评分规则版本，写入 scoring_detail.scoring_rule_version 以支持回归验证。 */
    String ruleVersion();

    /**
     * 对一道题评分。
     *
     * @param predictedOptionIds 预测选中的选项 Id，按 position 升序
     * @param officialAnswerJson 官方答案 JSON 原文，缺失时为 null
     * @param frozenOptionIds    预测任务冻结快照中的全部选项 Id，用于确认官方答案落在该题当时的选项内
     */
    ScoreOutcome score(List<Integer> predictedOptionIds, String officialAnswerJson, Set<Integer> frozenOptionIds);

    /**
     * 评分结果。未计分状态下 score 与 maxScore 均为 0；
     * predictedOptionId / correctOptionId 无法确定时为 null，reason 说明未评分原因。
     */
    record ScoreOutcome(ScoreStatus status, BigDecimal score, BigDecimal maxScore,
                        Integer predictedOptionId, Integer correctOptionId, String reason) {
    }
}
