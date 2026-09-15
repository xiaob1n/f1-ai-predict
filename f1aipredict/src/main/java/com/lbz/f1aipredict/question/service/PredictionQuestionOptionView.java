package com.lbz.f1aipredict.question.service;

import lombok.Value;

import java.math.BigDecimal;

/**
 * 预测创建用的不可变选项视图。
 * <p>
 * 仅暴露预测冻结所需的选项身份与展示字段，不包含 {@code isAnswer} 等内部标记。
 * 本类型不是对外 REST DTO，不参与公开 JSON 契约。
 */
@Value
public class PredictionQuestionOptionView {

    /** Feed 侧选项 ID，仅在所属快照范围内有意义 */
    Integer optionId;

    /** 选项序号，保持 Mapper 返回的 option_no 顺序 */
    Integer optionNo;

    /** 选项文本 */
    String optionText;

    /** 选项分值 */
    Integer points;

    /** 预测概率 */
    BigDecimal chance;
}
