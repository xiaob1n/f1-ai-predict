package com.lbz.f1aipredict.prediction.service.impl;

import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.prediction.dto.PredictionJobOutcomeDto;
import com.lbz.f1aipredict.prediction.entity.PredictionFailure;
import com.lbz.f1aipredict.prediction.entity.PredictionJob;
import com.lbz.f1aipredict.prediction.entity.PredictionOutcomeReceipt;
import com.lbz.f1aipredict.prediction.entity.PredictionResult;
import com.lbz.f1aipredict.prediction.entity.PredictionResultItem;
import com.lbz.f1aipredict.prediction.mapper.PredictionFailureMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionJobMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionOutcomeReceiptMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionResultItemMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionResultMapper;
import com.lbz.f1aipredict.prediction.service.PredictionJobOutcomeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 按任务业务键查询有界且经过白名单映射的终态详情。 */
@Service
public class PredictionJobOutcomeServiceImpl implements PredictionJobOutcomeService {

    private static final int BUSINESS_ID_MAX_LENGTH = 64;

    private final PredictionJobMapper jobMapper;
    private final PredictionResultMapper resultMapper;
    private final PredictionResultItemMapper resultItemMapper;
    private final PredictionFailureMapper failureMapper;
    private final PredictionOutcomeReceiptMapper receiptMapper;

    /** 使用构造器注入查询 Mapper，服务单测可在无数据库环境验证字段边界。 */
    public PredictionJobOutcomeServiceImpl(PredictionJobMapper jobMapper,
                                           PredictionResultMapper resultMapper,
                                           PredictionResultItemMapper resultItemMapper,
                                           PredictionFailureMapper failureMapper,
                                           PredictionOutcomeReceiptMapper receiptMapper) {
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper must not be null");
        this.resultMapper = Objects.requireNonNull(resultMapper, "resultMapper must not be null");
        this.resultItemMapper = Objects.requireNonNull(resultItemMapper, "resultItemMapper must not be null");
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper must not be null");
        this.receiptMapper = Objects.requireNonNull(receiptMapper, "receiptMapper must not be null");
    }

    /** 先确认任务存在，只对终态读取对应详情，避免无关的结果与失败查询。 */
    @Override
    public PredictionJobOutcomeDto getByBusinessId(String predictionJobId) {
        String normalizedId = normalizeBusinessId(predictionJobId);
        PredictionJob job = jobMapper.selectByPredictionJobId(normalizedId);
        if (job == null) {
            throw new ResourceNotFoundException("Prediction job not found: " + normalizedId);
        }

        PredictionJobOutcomeDto.PredictionJobOutcomeDtoBuilder response = PredictionJobOutcomeDto.builder()
                .predictionJobId(job.getPredictionJobId())
                .status(job.getStatus());
        if ("SUCCEEDED".equals(job.getStatus())) {
            PredictionOutcomeReceipt receipt = receiptMapper.selectByJobId(job.getId());
            PredictionResult result = resultMapper.selectByJobId(job.getId());
            PredictionFailure failure = failureMapper.selectByJobId(job.getId());
            if (receipt == null || !"RESULT".equals(receipt.getOutcomeType())
                    || result == null || failure != null) {
                throw inconsistentOutcome();
            }
            response.result(toResultDto(job.getId(), result));
        } else if ("FAILED".equals(job.getStatus())) {
            PredictionOutcomeReceipt receipt = receiptMapper.selectByJobId(job.getId());
            PredictionFailure failure = failureMapper.selectByJobId(job.getId());
            PredictionResult result = resultMapper.selectByJobId(job.getId());
            if (receipt == null || !"FAILURE".equals(receipt.getOutcomeType())
                    || failure == null || result != null) {
                throw inconsistentOutcome();
            }
            response.failure(toFailureDto(failure));
        }
        return response.build();
    }

    /** 终态写入必须同时有匹配凭证和唯一类型的详情，避免返回静默缺失或相互矛盾的数据。 */
    private static ResponseStatusException inconsistentOutcome() {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Prediction outcome is inconsistent");
    }

    /** 校验公开业务键并保持与单任务查询一致的规范化规则。 */
    private static String normalizeBusinessId(String predictionJobId) {
        if (predictionJobId == null) {
            throw new InvalidRequestException("predictionJobId is invalid");
        }
        String normalizedId = predictionJobId.trim();
        if (normalizedId.isEmpty() || normalizedId.length() > BUSINESS_ID_MAX_LENGTH) {
            throw new InvalidRequestException("predictionJobId is invalid");
        }
        return normalizedId;
    }

    /** 将成功详情转换为安全字段；子记录 Mapper 已按稳定顺序及 100 条上限查询。 */
    private PredictionJobOutcomeDto.Result toResultDto(Long jobId, PredictionResult result) {
        List<PredictionJobOutcomeDto.SelectedOption> options = new ArrayList<>();
        List<PredictionResultItem> items = resultItemMapper.selectByJobId(jobId);
        if (items != null) {
            for (PredictionResultItem item : items) {
                if (item != null) {
                    options.add(PredictionJobOutcomeDto.SelectedOption.builder()
                            .optionId(item.getOptionId())
                            .position(item.getPosition())
                            .build());
                }
            }
        }

        return PredictionJobOutcomeDto.Result.builder()
                .selectedOptions(options)
                .confidence(result.getConfidence())
                .build();
    }

    /** 仅返回失败协议定义的安全字段，不暴露 trace/message 标识或底层错误。 */
    private static PredictionJobOutcomeDto.Failure toFailureDto(PredictionFailure failure) {
        return PredictionJobOutcomeDto.Failure.builder()
                .failureCode(failure.getFailureCode())
                .summary(failure.getSummary())
                .build();
    }
}
