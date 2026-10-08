package com.lbz.f1aipredict.scoring.engine.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.scoring.ScoreStatus;
import com.lbz.f1aipredict.scoring.engine.ScoringEngine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * SINGLE_BINARY_V1：单选题命中官方答案得 1 分，未命中得 0 分。
 * <p>
 * 不读取选项 points 或官方积分，二者来源尚未核实；官方答案按 Feed 的 {@code [{"Id":124}]} 形态解析。
 * 预测或答案不是恰好一个选项、或答案 Id 不在冻结快照选项内时返回 UNSCORED，
 * 而不是猜测一个选项参与比较，更不会把它当作 0 分。
 */
@Component
public class SingleChoiceScoringStrategy implements ScoringEngine {

    /** 规则版本常量，修改计分规则时必须新增版本而非改动本值。 */
    public static final String RULE_VERSION = "SINGLE_BINARY_V1";

    private final ObjectMapper objectMapper;

    public SingleChoiceScoringStrategy(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public String ruleVersion() {
        return RULE_VERSION;
    }

    @Override
    public ScoreOutcome score(List<Integer> predictedOptionIds, String officialAnswerJson,
                              Set<Integer> frozenOptionIds) {
        JsonNode answer = parse(officialAnswerJson);
        if (answer == null) {
            return unscored(null, null, "ANSWER_UNPARSEABLE");
        }
        // 空内容与空数组都表示官方答案尚未公布。
        if (answer.isNull() || (answer.isArray() && answer.isEmpty())) {
            return notCounted(ScoreStatus.NO_ANSWER, null, null, null);
        }
        Integer predicted = predictedOptionIds != null && predictedOptionIds.size() == 1
                ? predictedOptionIds.getFirst() : null;
        if (predicted == null) {
            return unscored(null, null, "PREDICTION_NOT_SINGLE");
        }
        if (!answer.isArray() || answer.size() != 1) {
            return unscored(predicted, null, "ANSWER_NOT_SINGLE");
        }
        JsonNode id = answer.get(0).path("Id");
        if (!id.isIntegralNumber() || !id.canConvertToInt()) {
            return unscored(predicted, null, "ANSWER_UNPARSEABLE");
        }
        int correct = id.intValue();
        // 官方答案必须是该题冻结快照里存在的选项，否则无法确认是同一道题的同一套选项。
        if (frozenOptionIds == null || !frozenOptionIds.contains(correct)) {
            return unscored(predicted, correct, "ANSWER_OPTION_NOT_IN_SNAPSHOT");
        }
        if (predicted == correct) {
            return new ScoreOutcome(ScoreStatus.SCORED_CORRECT, BigDecimal.ONE, BigDecimal.ONE,
                    predicted, correct, null);
        }
        return new ScoreOutcome(ScoreStatus.SCORED_ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                predicted, correct, null);
    }

    /** 无法解析为 JSON 时返回 null，由调用方转为 UNSCORED；缺失内容按 JSON null 处理。 */
    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static ScoreOutcome unscored(Integer predicted, Integer correct, String reason) {
        return notCounted(ScoreStatus.UNSCORED, predicted, correct, reason);
    }

    private static ScoreOutcome notCounted(ScoreStatus status, Integer predicted, Integer correct, String reason) {
        return new ScoreOutcome(status, BigDecimal.ZERO, BigDecimal.ZERO, predicted, correct, reason);
    }
}
