package com.lbz.f1aipredict.question.service.impl;

import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.question.entity.Question;
import com.lbz.f1aipredict.question.entity.QuestionOption;
import com.lbz.f1aipredict.question.entity.QuestionSnapshot;
import com.lbz.f1aipredict.question.mapper.QuestionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionOptionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionSnapshotMapper;
import com.lbz.f1aipredict.question.service.PredictionQuestionOptionView;
import com.lbz.f1aipredict.question.service.PredictionQuestionReadService;
import com.lbz.f1aipredict.question.service.PredictionQuestionView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 预测创建用的题目内部批量读取实现。
 * <p>
 * 题目、最新快照、选项各查询一次，禁止循环 Mapper 与公开 {@code QuestionService.getDetail}。
 * 显式模式整批失败；自动模式只过滤不可预测题，空结果交给调用方处理。
 */
@Slf4j
@Service
public class PredictionQuestionReadServiceImpl implements PredictionQuestionReadService {

    /** 可预测题目必须处于 OPEN 状态 */
    private static final String STATUS_OPEN = "OPEN";

    private static final Comparator<Question> QUESTION_ORDER = Comparator
            .comparing(Question::getQuestionNo, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(Question::getId, Comparator.nullsLast(Long::compareTo));

    private final QuestionMapper questionMapper;
    private final QuestionSnapshotMapper snapshotMapper;
    private final QuestionOptionMapper optionMapper;

    public PredictionQuestionReadServiceImpl(QuestionMapper questionMapper,
                                             QuestionSnapshotMapper snapshotMapper,
                                             QuestionOptionMapper optionMapper) {
        this.questionMapper = questionMapper;
        this.snapshotMapper = snapshotMapper;
        this.optionMapper = optionMapper;
    }

    /**
     * 按分站批量加载预测输入视图。
     * <p>
     * {@code questionIds == null} 走自动模式；非 null 走显式模式（空列表非法）。
     */
    @Override
    public List<PredictionQuestionView> loadForPrediction(Long roundId, List<Long> questionIds) {
        if (roundId == null) {
            throw new InvalidRequestException("roundId is required");
        }
        if (questionIds != null) {
            // 显式空列表与含 null 的 ID 均视为畸形输入，在任何 Mapper 调用前拒绝。
            // 不可用 List.contains(null)：List.of 不可变列表对 null 元素会抛 NPE。
            if (questionIds.isEmpty()) {
                throw new InvalidRequestException("questionIds must not be empty");
            }
            for (Long questionId : questionIds) {
                if (questionId == null) {
                    throw new InvalidRequestException("questionIds must not contain null");
                }
            }
            return loadExplicit(roundId, questionIds);
        }
        return loadAuto(roundId);
    }

    /**
     * 显式 ID 模式：不存在、跨 round、非 OPEN、无最新快照、空选项均整批失败，不静默跳过。
     */
    private List<PredictionQuestionView> loadExplicit(Long roundId, List<Long> questionIds) {
        Set<Long> requestedIds = new LinkedHashSet<>(questionIds);
        log.debug("显式加载预测题目: roundId={}, requestedCount={}, distinctCount={}",
                roundId, questionIds.size(), requestedIds.size());

        // 题目一次读取：不过滤 status，才能把同 round 的非 OPEN 与“不存在/跨 round”区分开。
        List<Question> inRound = questionMapper.selectByRound(roundId, null, null);
        Map<Long, Question> inRoundById = indexById(inRound);

        List<Long> missingFromRound = requestedIds.stream()
                .filter(id -> !inRoundById.containsKey(id))
                .collect(Collectors.toList());
        // 缺 round 内命中时再批量查一次主键，区分“不存在”与“跨 round”，禁止逐条 selectById。
        if (!missingFromRound.isEmpty()) {
            failMissingOrCrossRound(roundId, missingFromRound);
        }

        List<Question> selected = requestedIds.stream()
                .map(inRoundById::get)
                .collect(Collectors.toList());

        List<Long> notOpenIds = selected.stream()
                .filter(question -> !STATUS_OPEN.equals(question.getStatus()))
                .map(Question::getId)
                .collect(Collectors.toList());
        if (!notOpenIds.isEmpty()) {
            log.warn("显式预测题目非 OPEN: roundId={}, questionIds={}", roundId, notOpenIds);
            throw new InvalidRequestException("Question not OPEN: " + joinIds(notOpenIds));
        }

        List<Long> noLatestSnapshotIds = selected.stream()
                .filter(question -> question.getLatestSnapshotId() == null)
                .map(Question::getId)
                .collect(Collectors.toList());
        if (!noLatestSnapshotIds.isEmpty()) {
            log.warn("显式预测题目缺少 latestSnapshotId: roundId={}, questionIds={}",
                    roundId, noLatestSnapshotIds);
            throw new InvalidRequestException(
                    "Question missing latest snapshot: " + joinIds(noLatestSnapshotIds));
        }

        Map<Long, List<QuestionOption>> optionsBySnapshotId = loadLatestSnapshotsAndOptions(selected, true);
        List<Long> emptyOptionIds = selected.stream()
                .filter(question -> optionsBySnapshotId
                        .getOrDefault(question.getLatestSnapshotId(), List.of())
                        .isEmpty())
                .map(Question::getId)
                .collect(Collectors.toList());
        if (!emptyOptionIds.isEmpty()) {
            log.warn("显式预测题目最新快照无选项: roundId={}, questionIds={}", roundId, emptyOptionIds);
            throw new InvalidRequestException("Question has empty options: " + joinIds(emptyOptionIds));
        }

        selected.sort(QUESTION_ORDER);
        List<PredictionQuestionView> views = toViews(selected, optionsBySnapshotId);
        log.debug("显式加载预测题目完成: roundId={}, viewCount={}", roundId, views.size());
        return List.copyOf(views);
    }

    /**
     * 自动模式：只返回 OPEN、最新快照存在且选项非空的题目；无可预测项时返回空列表。
     */
    private List<PredictionQuestionView> loadAuto(Long roundId) {
        log.debug("自动加载预测题目: roundId={}", roundId);
        List<Question> openQuestions = questionMapper.selectByRound(roundId, STATUS_OPEN, null);
        if (openQuestions.isEmpty()) {
            log.debug("自动模式无 OPEN 题目: roundId={}", roundId);
            return List.of();
        }

        List<Question> withLatestSnapshotId = openQuestions.stream()
                .filter(question -> question.getLatestSnapshotId() != null)
                .collect(Collectors.toList());
        if (withLatestSnapshotId.isEmpty()) {
            log.debug("自动模式 OPEN 题目均无 latestSnapshotId: roundId={}", roundId);
            return List.of();
        }

        Map<Long, List<QuestionOption>> optionsBySnapshotId =
                loadLatestSnapshotsAndOptions(withLatestSnapshotId, false);
        List<Question> predictable = withLatestSnapshotId.stream()
                .filter(question -> !optionsBySnapshotId
                        .getOrDefault(question.getLatestSnapshotId(), List.of())
                        .isEmpty())
                .sorted(QUESTION_ORDER)
                .collect(Collectors.toList());
        List<PredictionQuestionView> views = toViews(predictable, optionsBySnapshotId);
        log.debug("自动加载预测题目完成: roundId={}, viewCount={}", roundId, views.size());
        return List.copyOf(views);
    }

    /**
     * 对 round 内未命中的显式 ID 做一次主键批量查询，按“不存在”优先于“跨 round”整批失败。
     */
    private void failMissingOrCrossRound(Long roundId, List<Long> missingFromRound) {
        List<Question> foundByIds = questionMapper.selectByIds(missingFromRound);
        Map<Long, Question> foundById = indexById(foundByIds == null ? List.of() : foundByIds);

        List<Long> notFoundIds = missingFromRound.stream()
                .filter(id -> !foundById.containsKey(id))
                .collect(Collectors.toList());
        if (!notFoundIds.isEmpty()) {
            log.warn("显式预测题目不存在: roundId={}, questionIds={}", roundId, notFoundIds);
            throw new InvalidRequestException("Question not found: " + joinIds(notFoundIds));
        }

        List<Long> crossRoundIds = missingFromRound.stream()
                .filter(id -> !Objects.equals(foundById.get(id).getRoundId(), roundId))
                .collect(Collectors.toList());
        log.warn("显式预测题目跨 round: roundId={}, questionIds={}", roundId, crossRoundIds);
        throw new InvalidRequestException("Question not in round: " + joinIds(crossRoundIds));
    }

    /**
     * 快照一次、选项一次：仅使用 Question.latestSnapshotId，不回退历史快照。
     *
     * @param requireCompleteSnapshots 显式模式为 true，缺失快照整批失败；自动模式为 false，缺失则过滤
     */
    private Map<Long, List<QuestionOption>> loadLatestSnapshotsAndOptions(
            List<Question> questions, boolean requireCompleteSnapshots) {
        List<Long> snapshotIds = questions.stream()
                .map(Question::getLatestSnapshotId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (snapshotIds.isEmpty()) {
            return Map.of();
        }

        List<QuestionSnapshot> snapshots = snapshotMapper.selectSnapshotByIds(snapshotIds);
        Map<Long, QuestionSnapshot> snapshotById = (snapshots == null ? List.<QuestionSnapshot>of() : snapshots)
                .stream()
                .filter(snapshot -> snapshot.getId() != null)
                .collect(Collectors.toMap(QuestionSnapshot::getId, Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));

        List<Question> withExistingSnapshots = new ArrayList<>(questions.size());
        List<Long> missingSnapshotQuestionIds = new ArrayList<>();
        for (Question question : questions) {
            if (snapshotBelongsToQuestion(snapshotById.get(question.getLatestSnapshotId()), question)) {
                withExistingSnapshots.add(question);
            } else {
                missingSnapshotQuestionIds.add(question.getId());
            }
        }
        if (!missingSnapshotQuestionIds.isEmpty()) {
            if (requireCompleteSnapshots) {
                log.warn("显式预测题目最新快照缺失: questionIds={}", missingSnapshotQuestionIds);
                throw new InvalidRequestException(
                        "Question missing latest snapshot: " + joinIds(missingSnapshotQuestionIds));
            }
            // 自动模式过滤缺失快照，不改调用方传入的题目列表。
            questions = withExistingSnapshots;
        }

        List<Long> existingSnapshotIds = questions.stream()
                .map(Question::getLatestSnapshotId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (existingSnapshotIds.isEmpty()) {
            return Map.of();
        }

        List<QuestionOption> options = optionMapper.selectBySnapshotIds(existingSnapshotIds);
        return groupOptions(options == null ? List.of() : options);
    }

    /**
     * 确认快照行存在且归属当前题目，避免误用其他题的同号快照。
     */
    private static boolean snapshotBelongsToQuestion(QuestionSnapshot snapshot, Question question) {
        return snapshot != null
                && Objects.equals(snapshot.getId(), question.getLatestSnapshotId())
                && Objects.equals(snapshot.getQuestionId(), question.getId());
    }

    /**
     * 将已校验题目组装为不可变视图；snapshotId 严格等于 latestSnapshotId。
     */
    private static List<PredictionQuestionView> toViews(
            List<Question> questions, Map<Long, List<QuestionOption>> optionsBySnapshotId) {
        List<PredictionQuestionView> views = new ArrayList<>(questions.size());
        for (Question question : questions) {
            List<QuestionOption> options = optionsBySnapshotId.getOrDefault(
                    question.getLatestSnapshotId(), List.of());
            List<PredictionQuestionOptionView> optionViews = new ArrayList<>(options.size());
            for (QuestionOption option : options) {
                optionViews.add(new PredictionQuestionOptionView(
                        option.getOptionId(),
                        option.getOptionNo(),
                        option.getOptionText(),
                        option.getPoints(),
                        option.getChance()));
            }
            views.add(new PredictionQuestionView(
                    question.getId(),
                    question.getQuestionNo(),
                    question.getLatestSnapshotId(),
                    optionViews));
        }
        return views;
    }

    /**
     * 按 snapshotId 分组并保留 Mapper 的 option_no 原序，不去重 optionId。
     */
    private static Map<Long, List<QuestionOption>> groupOptions(List<QuestionOption> options) {
        Map<Long, List<QuestionOption>> grouped = new LinkedHashMap<>();
        for (QuestionOption option : options) {
            grouped.computeIfAbsent(option.getSnapshotId(), ignored -> new ArrayList<>()).add(option);
        }
        return grouped;
    }

    private static Map<Long, Question> indexById(List<Question> questions) {
        return questions.stream()
                .filter(question -> question.getId() != null)
                .collect(Collectors.toMap(Question::getId, Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));
    }

    private static String joinIds(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}
