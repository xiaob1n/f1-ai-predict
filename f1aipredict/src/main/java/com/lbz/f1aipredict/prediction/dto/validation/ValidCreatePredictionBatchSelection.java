package com.lbz.f1aipredict.prediction.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 创建预测批次时的题目选择互斥约束。
 * <p>
 * 合法组合只有两种：非空且无重复的 {@code questionIds}，或 {@code allOpenQuestions=true}
 * 且未声明 {@code questionIds}。同时提供、均未提供、空数组、重复 ID 均失败。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CreatePredictionBatchSelectionValidator.class)
public @interface ValidCreatePredictionBatchSelection {

    /** 默认校验失败文案，仅用于 Bean Validation，不直接作为对外 HTTP 响应。 */
    String message() default "questionIds 与 allOpenQuestions 必须互斥且恰好选择一种";

    /** 校验分组，默认无。 */
    Class<?>[] groups() default {};

    /** 负载类型，默认无。 */
    Class<? extends Payload>[] payload() default {};
}
