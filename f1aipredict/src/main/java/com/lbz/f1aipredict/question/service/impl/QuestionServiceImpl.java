package com.lbz.f1aipredict.question.service.impl;

import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.question.dto.QuestionDetailDto;
import com.lbz.f1aipredict.question.dto.QuestionDto;
import com.lbz.f1aipredict.question.dto.QuestionOptionDto;
import com.lbz.f1aipredict.question.dto.QuestionQuery;
import com.lbz.f1aipredict.question.dto.QuestionSnapshotDto;
import com.lbz.f1aipredict.question.entity.Question;
import com.lbz.f1aipredict.question.entity.QuestionOption;
import com.lbz.f1aipredict.question.entity.QuestionSnapshot;
import com.lbz.f1aipredict.question.mapper.QuestionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionOptionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionSnapshotMapper;
import com.lbz.f1aipredict.question.service.QuestionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 题目只读查询服务实现。
 * <p>
 * 快照和选项统一批量加载，避免在 DTO 映射循环中访问 Mapper。
 */
@Service
public class QuestionServiceImpl implements QuestionService {

    private final QuestionMapper questionMapper;
    private final QuestionSnapshotMapper snapshotMapper;
    private final QuestionOptionMapper optionMapper;

    public QuestionServiceImpl(QuestionMapper questionMapper,
                               QuestionSnapshotMapper snapshotMapper,
                               QuestionOptionMapper optionMapper) {
        this.questionMapper = questionMapper;
        this.snapshotMapper = snapshotMapper;
        this.optionMapper = optionMapper;
    }

    /**
     * 按分站和可选条件查询题目，并按需批量附加快照选项。
     */
    @Override
    public List<QuestionDto> listByRoundId(Long roundId, QuestionQuery query) {
        QuestionQuery effectiveQuery = query == null ? new QuestionQuery() : query;
        List<Question> questions = questionMapper.selectByRound(
                roundId, effectiveQuery.getStatus(), effectiveQuery.getGamedayId());
        if (questions.isEmpty()) {
            return Collections.emptyList();
        }

        QuestionSnapshot selectedSnapshot = null;
        if (effectiveQuery.getSnapshotId() != null) {
            selectedSnapshot = snapshotMapper.selectById(effectiveQuery.getSnapshotId());
            if (selectedSnapshot == null) {
                return Collections.emptyList();
            }
            Long selectedQuestionId = selectedSnapshot.getQuestionId();
            questions = questions.stream()
                    .filter(question -> Objects.equals(question.getId(), selectedQuestionId))
                    .collect(Collectors.toList());
            if (questions.isEmpty()) {
                return Collections.emptyList();
            }
        }

        boolean includeOptions = !Boolean.FALSE.equals(effectiveQuery.getIncludeOptions());
        Map<Long, List<QuestionOptionDto>> optionsBySnapshotId = Collections.emptyMap();
        if (includeOptions) {
            List<Long> snapshotIds;
            if (selectedSnapshot != null) {
                snapshotIds = Collections.singletonList(selectedSnapshot.getId());
            } else {
                snapshotIds = questions.stream()
                        .map(Question::getLatestSnapshotId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());
                // 批量确认最新快照存在，缺失快照不会触发逐题查询。
                snapshotIds = snapshotMapper.selectSnapshotByIds(snapshotIds).stream()
                        .map(QuestionSnapshot::getId)
                        .collect(Collectors.toList());
            }
            optionsBySnapshotId = loadOptionsBySnapshotIds(snapshotIds);
        }

        List<QuestionDto> result = new ArrayList<>(questions.size());
        for (Question question : questions) {
            Long optionSnapshotId = selectedSnapshot == null
                    ? question.getLatestSnapshotId() : selectedSnapshot.getId();
            List<QuestionOptionDto> options = includeOptions
                    ? optionsBySnapshotId.getOrDefault(optionSnapshotId, Collections.emptyList())
                    : Collections.emptyList();
            result.add(toQuestionDto(question, options));
        }
        return result;
    }

    /**
     * 查询题目详情，并批量读取其最新快照下的选项。
     */
    @Override
    public QuestionDetailDto getDetail(Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new ResourceNotFoundException("Question not found: " + questionId);
        }

        Long latestSnapshotId = question.getLatestSnapshotId();
        // 数据库允许题目暂时没有最新快照，此时详情仍然可返回，但选项保持为空。
        if (latestSnapshotId == null) {
            return toQuestionDetailDto(question, Collections.emptyList());
        }
        List<QuestionSnapshot> snapshots = snapshotMapper.selectSnapshotByIds(
                Collections.singletonList(latestSnapshotId));
        if (snapshots.isEmpty()) {
            throw new ResourceNotFoundException("Snapshot not found: " + latestSnapshotId);
        }
        List<QuestionOptionDto> options = loadOptionsBySnapshotIds(Collections.singletonList(latestSnapshotId))
                .getOrDefault(latestSnapshotId, Collections.emptyList());
        return toQuestionDetailDto(question, options);
    }

    /**
     * 查询指定快照，并校验题目存在且快照确实归属于该题目。
     */
    @Override
    public QuestionSnapshotDto getSnapshot(Long questionId, Long snapshotId) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new ResourceNotFoundException("Question not found: " + questionId);
        }

        QuestionSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new ResourceNotFoundException("Snapshot not found: " + snapshotId);
        }
        if (!Objects.equals(snapshot.getQuestionId(), question.getId())) {
            throw new ResourceNotFoundException(
                    "Snapshot does not belong to question: " + snapshotId + "/" + questionId);
        }

        List<QuestionOptionDto> options = loadOptionsBySnapshotIds(Collections.singletonList(snapshotId))
                .getOrDefault(snapshotId, Collections.emptyList());
        return toSnapshotDto(snapshot, options);
    }

    /**
     * 查询题目全部快照，并一次性批量加载所有快照的选项。
     */
    @Override
    public List<QuestionSnapshotDto> listSnapshots(Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new ResourceNotFoundException("Question not found: " + questionId);
        }

        List<QuestionSnapshot> snapshots = snapshotMapper.selectByQuestionId(questionId);
        if (snapshots.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> snapshotIds = snapshots.stream()
                .map(QuestionSnapshot::getId)
                .collect(Collectors.toList());
        Map<Long, List<QuestionOptionDto>> optionsBySnapshotId = loadOptionsBySnapshotIds(snapshotIds);

        List<QuestionSnapshotDto> result = new ArrayList<>(snapshots.size());
        for (QuestionSnapshot snapshot : snapshots) {
            result.add(toSnapshotDto(snapshot,
                    optionsBySnapshotId.getOrDefault(snapshot.getId(), Collections.emptyList())));
        }
        return result;
    }

    /**
     * 一次查询多个快照的选项，并按快照 ID 分组供后续纯内存映射使用。
     */
    private Map<Long, List<QuestionOptionDto>> loadOptionsBySnapshotIds(List<Long> snapshotIds) {
        if (snapshotIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, List<QuestionOptionDto>> result = new LinkedHashMap<>();
        for (QuestionOption option : optionMapper.selectBySnapshotIds(snapshotIds)) {
            result.computeIfAbsent(option.getSnapshotId(), ignored -> new ArrayList<>())
                    .add(toOptionDto(option));
        }
        return result;
    }

    /**
     * 将题目实体显式映射为列表 DTO；questionType 保留 DTO 的默认 UNKNOWN。
     */
    private QuestionDto toQuestionDto(Question question, List<QuestionOptionDto> options) {
        QuestionDto dto = new QuestionDto();
        dto.setQuestionId(question.getId());
        dto.setGamedayId(question.getGamedayId());
        dto.setSourceQuestionId(question.getSourceQuestionId());
        dto.setQuestionNo(question.getQuestionNo());
        dto.setQuestionText(question.getQuestionText());
        dto.setSubText(question.getSubText());
        dto.setOptionTemplateId(question.getOptionTemplateId());
        dto.setChoiceLimit(question.getChoiceLimit());
        dto.setStatus(question.getStatus());
        dto.setLatestSnapshotId(question.getLatestSnapshotId());
        dto.setOptions(options);
        return dto;
    }

    /**
     * 将题目实体显式映射为详情 DTO；questionType 保留 DTO 的默认 UNKNOWN。
     */
    private QuestionDetailDto toQuestionDetailDto(Question question, List<QuestionOptionDto> options) {
        QuestionDetailDto dto = new QuestionDetailDto();
        dto.setQuestionId(question.getId());
        dto.setGamedayId(question.getGamedayId());
        dto.setSourceQuestionId(question.getSourceQuestionId());
        dto.setQuestionNo(question.getQuestionNo());
        dto.setQuestionText(question.getQuestionText());
        dto.setSubText(question.getSubText());
        dto.setOptionTemplateId(question.getOptionTemplateId());
        dto.setChoiceLimit(question.getChoiceLimit());
        dto.setStatus(question.getStatus());
        dto.setLatestSnapshotId(question.getLatestSnapshotId());
        dto.setOptions(options);
        return dto;
    }

    /**
     * 将快照实体显式映射为 DTO，仅暴露原始 JSON 是否存在。
     */
    private QuestionSnapshotDto toSnapshotDto(QuestionSnapshot snapshot, List<QuestionOptionDto> options) {
        QuestionSnapshotDto dto = new QuestionSnapshotDto();
        dto.setSnapshotId(snapshot.getId());
        dto.setQuestionId(snapshot.getQuestionId());
        dto.setSnapshotNo(snapshot.getSnapshotNo());
        dto.setContentHash(snapshot.getContentHash());
        dto.setSnapshotReason(snapshot.getSnapshotReason());
        dto.setCreatedAt(snapshot.getCreatedAt());
        dto.setHasRawJson(snapshot.getRawJson() != null);
        dto.setOptions(options);
        return dto;
    }

    /**
     * 将选项实体显式映射为公开 DTO，不暴露答案标记等内部字段。
     */
    private QuestionOptionDto toOptionDto(QuestionOption option) {
        QuestionOptionDto dto = new QuestionOptionDto();
        dto.setOptionId(option.getOptionId());
        dto.setOptionNo(option.getOptionNo());
        dto.setOptionText(option.getOptionText());
        dto.setPoints(option.getPoints());
        dto.setChance(option.getChance());
        return dto;
    }
}
