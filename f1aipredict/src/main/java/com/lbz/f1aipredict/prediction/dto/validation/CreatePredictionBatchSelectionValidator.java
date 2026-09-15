package com.lbz.f1aipredict.prediction.dto.validation;

import com.lbz.f1aipredict.prediction.dto.CreatePredictionBatchRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link ValidCreatePredictionBatchSelection} 的校验实现。
 * <p>
 * 只检查题目选择互斥与 questionIds 去重；数量上限、版本长度、截止时间由字段注解承担。
 */
public class CreatePredictionBatchSelectionValidator
        implements ConstraintValidator<ValidCreatePredictionBatchSelection, CreatePredictionBatchRequest> {

    /**
     * 校验题目选择是否恰好一种且 questionIds 无重复。
     *
     * @param request 创建请求，null 视为由外层处理
     * @param context 约束上下文
     * @return 合法返回 true
     */
    @Override
    public boolean isValid(CreatePredictionBatchRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        boolean selectingAllOpen = Boolean.TRUE.equals(request.getAllOpenQuestions());
        List<Long> questionIds = request.getQuestionIds();
        boolean idsDeclared = questionIds != null;
        boolean hasNonEmptyIds = idsDeclared && !questionIds.isEmpty();

        // 同时存在：allOpenQuestions=true 且 JSON/对象里声明了 questionIds（含空数组）
        if (selectingAllOpen && idsDeclared) {
            replaceMessage(context, "不能同时提供 questionIds 与 allOpenQuestions=true");
            return false;
        }
        // 均无 / false+无 IDs / 空数组
        if (!selectingAllOpen && !hasNonEmptyIds) {
            replaceMessage(context, "必须提供非空 questionIds 或 allOpenQuestions=true");
            return false;
        }
        // 重复 ID
        if (hasNonEmptyIds && hasDuplicateIds(questionIds)) {
            replaceMessage(context, "questionIds 不得包含重复 ID");
            return false;
        }
        return true;
    }

    /**
     * 判断题目 ID 列表是否存在重复。
     */
    private static boolean hasDuplicateIds(List<Long> questionIds) {
        Set<Long> unique = new HashSet<>(questionIds.size());
        for (Long questionId : questionIds) {
            if (!unique.add(questionId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用更具体的中文原因替换默认约束文案，便于测试与后续日志区分分支。
     */
    private static void replaceMessage(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
    }
}
