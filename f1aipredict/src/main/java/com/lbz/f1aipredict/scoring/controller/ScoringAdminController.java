package com.lbz.f1aipredict.scoring.controller;

import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.scoring.dto.AnswerUpdateRequest;
import com.lbz.f1aipredict.scoring.dto.BatchTotalScoreDto;
import com.lbz.f1aipredict.scoring.dto.OfficialAnswerDto;
import com.lbz.f1aipredict.scoring.dto.RescoreRequest;
import com.lbz.f1aipredict.scoring.service.OfficialAnswerService;
import com.lbz.f1aipredict.scoring.service.ScoringService;
import com.lbz.f1aipredict.sync.FeedSyncException;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官方答案与评分管理端入口。访问控制由部署网关负责，本应用不做额外鉴权。
 * <p>
 * 错误语义沿用全局异常处理：参数或状态非法 400，批次/答案不存在 404，答案 Feed 同步失败 502。
 * 评分只做首次评分且幂等；答案修订后需显式调用重算，绝不自动重算。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/scoring")
public class ScoringAdminController {

    private static final String STATUS_FAILED = "FAILED";

    private final OfficialAnswerService answerService;
    private final ScoringService scoringService;

    public ScoringAdminController(OfficialAnswerService answerService, ScoringService scoringService) {
        this.answerService = answerService;
        this.scoringService = scoringService;
    }

    /**
     * 同步指定比赛日的官方答案（POST /api/v1/admin/scoring/answers/sync/{gamedayId}）。
     * Feed 拉取或解析失败时同步审计已落库，对外返回 502，不回显上游原文。
     */
    @PostMapping("/answers/sync/{gamedayId}")
    public SyncResultDto syncAnswers(@PathVariable Integer gamedayId) {
        requirePositive(gamedayId, "gamedayId");
        log.info("管理端触发官方答案同步: gamedayId={}", gamedayId);
        SyncResultDto result = answerService.syncAnswers(gamedayId);
        if (result == null || STATUS_FAILED.equals(result.getStatus())) {
            log.warn("管理端官方答案同步失败: gamedayId={}, syncRecordId={}", gamedayId,
                    result == null ? null : result.getSyncRecordId());
            throw new FeedSyncException("Official answer sync failed");
        }
        return result;
    }

    /** 读取题目当前官方答案（GET /api/v1/admin/scoring/answers/{questionId}），无答案时 404。 */
    @GetMapping("/answers/{questionId}")
    public OfficialAnswerDto getAnswer(@PathVariable Long questionId) {
        requirePositive(questionId, "questionId");
        return OfficialAnswerDto.from(answerService.getByQuestionId(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Official answer not found: " + questionId)));
    }

    /**
     * 人工修订官方答案（PUT /api/v1/admin/scoring/answers/{questionId}），旧版本先归档；
     * 只修订答案，不触发重算。返回修订后的当前态。
     */
    @PutMapping("/answers/{questionId}")
    public OfficialAnswerDto updateAnswer(@PathVariable Long questionId,
                                          @Valid @RequestBody AnswerUpdateRequest request) {
        requirePositive(questionId, "questionId");
        log.info("管理端人工修订官方答案: questionId={}", questionId);
        answerService.updateAnswer(questionId, request);
        return getAnswer(questionId);
    }

    /** 首次评分（POST /api/v1/admin/scoring/batches/{batchId}/score），已有总分时原样返回。 */
    @PostMapping("/batches/{batchId}/score")
    public BatchTotalScoreDto scoreBatch(@PathVariable Long batchId) {
        log.info("管理端触发批次评分: batchId={}", batchId);
        return BatchTotalScoreDto.from(scoringService.scoreBatch(batchId));
    }

    /** 显式重算（POST /api/v1/admin/scoring/batches/{batchId}/rescore），旧评分归档到历史表。 */
    @PostMapping("/batches/{batchId}/rescore")
    public BatchTotalScoreDto rescoreBatch(@PathVariable Long batchId, @RequestBody RescoreRequest request) {
        log.info("管理端触发批次显式重算: batchId={}", batchId);
        return BatchTotalScoreDto.from(scoringService.rescoreBatch(batchId, request.getReason()));
    }

    private static void requirePositive(Number id, String name) {
        if (id == null || id.longValue() <= 0) {
            throw new InvalidRequestException(name + " must be positive");
        }
    }
}
