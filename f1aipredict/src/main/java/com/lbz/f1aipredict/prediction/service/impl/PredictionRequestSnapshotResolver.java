package com.lbz.f1aipredict.prediction.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.question.entity.Question;
import com.lbz.f1aipredict.question.entity.QuestionSnapshot;
import com.lbz.f1aipredict.question.mapper.QuestionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionSnapshotMapper;
import com.lbz.f1aipredict.question.service.PredictionQuestionView;
import com.lbz.f1aipredict.season.dto.MeetingSessionDto;
import com.lbz.f1aipredict.season.dto.RoundDto;
import com.lbz.f1aipredict.season.entity.Season;
import com.lbz.f1aipredict.season.mapper.SeasonMapper;
import com.lbz.f1aipredict.season.service.MeetingSessionService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在写事务外批量读取快照，并冻结发布所需的可信上下文。 */
@Component
public class PredictionRequestSnapshotResolver {

    private final QuestionMapper questionMapper;
    private final QuestionSnapshotMapper snapshotMapper;
    private final SeasonMapper seasonMapper;
    private final MeetingSessionService meetingSessionService;
    private final ObjectMapper objectMapper;

    public PredictionRequestSnapshotResolver(QuestionMapper questionMapper,
                                             QuestionSnapshotMapper snapshotMapper,
                                             SeasonMapper seasonMapper,
                                             MeetingSessionService meetingSessionService,
                                             ObjectMapper objectMapper) {
        this.questionMapper = questionMapper;
        this.snapshotMapper = snapshotMapper;
        this.seasonMapper = seasonMapper;
        this.meetingSessionService = meetingSessionService;
        this.objectMapper = objectMapper;
    }

    /** 快照字段不完整或归属不符时拒绝创建，不借用当前表的正文代替。 */
    public FrozenBatch freeze(RoundDto round, List<PredictionQuestionView> views) {
        if (round.getSeasonId() == null || round.getRoundNumber() == null
                || round.getRoundNumber() <= 0 || round.getCircuitName() == null
                || round.getCircuitName().isBlank()) {
            throw new InvalidRequestException("Round context is incomplete");
        }
        Season season = seasonMapper.selectById(round.getSeasonId());
        if (season == null || season.getYear() == null || season.getYear() <= 0) {
            throw new InvalidRequestException("Season context is incomplete");
        }
        Map<Long, Question> questions = new HashMap<>();
        List<Question> foundQuestions = questionMapper.selectByIds(views.stream()
                .map(PredictionQuestionView::getQuestionId).toList());
        if (foundQuestions != null) {
            foundQuestions.forEach(question -> questions.put(question.getId(), question));
        }
        Map<Long, QuestionSnapshot> snapshots = new HashMap<>();
        List<QuestionSnapshot> foundSnapshots = snapshotMapper.selectSnapshotByIds(views.stream()
                .map(PredictionQuestionView::getSnapshotId).toList());
        if (foundSnapshots != null) {
            foundSnapshots.forEach(snapshot -> snapshots.put(snapshot.getId(), snapshot));
        }
        List<MeetingSessionDto> sessions = meetingSessionService.listByRoundId(round.getId());
        Map<Long, FrozenQuestion> frozen = new HashMap<>();
        for (PredictionQuestionView view : views) {
            Question question = questions.get(view.getQuestionId());
            QuestionSnapshot snapshot = snapshots.get(view.getSnapshotId());
            if (question == null || snapshot == null
                    || !Objects.equals(question.getRoundId(), round.getId())
                    || !Objects.equals(question.getLatestSnapshotId(), view.getSnapshotId())
                    || !Objects.equals(snapshot.getQuestionId(), view.getQuestionId())
                    || question.getGamedayId() == null || question.getGamedayId() <= 0) {
                throw new InvalidRequestException("Prediction snapshot context is incomplete");
            }
            JsonNode raw;
            try {
                raw = objectMapper.readTree(snapshot.getRawJson());
            } catch (JsonProcessingException | IllegalArgumentException error) {
                throw new InvalidRequestException("Prediction snapshot text is unavailable");
            }
            JsonNode textNode = raw == null ? null : raw.get("Text");
            String text = textNode == null || !textNode.isTextual() ? null : textNode.asText();
            if (text == null || text.isBlank()) {
                throw new InvalidRequestException("Prediction snapshot text is unavailable");
            }
            Integer gamedayId = question.getGamedayId();
            List<MeetingSessionDto> matching = sessions == null ? List.of() : sessions.stream()
                    .filter(session -> Objects.equals(session.getGamedayId(), gamedayId)).toList();
            Integer meetingKey = matching.size() == 1 ? matching.getFirst().getMeetingKey() : null;
            Integer sessionKey = matching.size() == 1 ? matching.getFirst().getSessionKey() : null;
            JsonNode config = raw.get("Config");
            frozen.put(view.getQuestionId(), new FrozenQuestion(text,
                    nullableText(raw.get("SubText")), nullableInteger(raw.get("OptionTemplateId")),
                    nullableInteger(config == null ? null : config.get("ChoiceLimit")),
                    gamedayId, meetingKey, sessionKey));
        }
        return new FrozenBatch(round.getSeasonId(), season.getYear(), round.getRoundNumber(),
                round.getCircuitName(), Map.copyOf(frozen));
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Integer nullableInteger(JsonNode node) {
        return node == null || !node.isIntegralNumber() ? null : node.intValue();
    }

    /** 只包含经快照和赛事表确认的字段。 */
    public record FrozenBatch(Long seasonId, Integer year, Integer roundNumber,
                              String trackName, Map<Long, FrozenQuestion> questions) { }

    /** 题目正文取自快照；比赛日用于唯一 Session 匹配。 */
    public record FrozenQuestion(String questionText, String subText, Integer optionTemplateId,
                                 Integer choiceLimit, Integer gamedayId,
                                 Integer meetingKey, Integer sessionKey) { }
}
