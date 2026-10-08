package com.lbz.f1aipredict.scoring.service;

import com.lbz.f1aipredict.scoring.dto.AnswerUpdateRequest;
import com.lbz.f1aipredict.scoring.entity.OfficialAnswer;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;

import java.util.Optional;

/** 官方答案同步与修订业务接口；答案不得回流到预测模型输入。 */
public interface OfficialAnswerService {

    SyncResultDto syncAnswers(Integer gamedayId);

    Optional<OfficialAnswer> getByQuestionId(Long questionId);

    void updateAnswer(Long questionId, AnswerUpdateRequest request);
}
