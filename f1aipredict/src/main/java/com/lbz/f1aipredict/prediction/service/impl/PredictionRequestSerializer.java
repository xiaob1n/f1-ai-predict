package com.lbz.f1aipredict.prediction.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionJob;
import com.lbz.f1aipredict.question.service.PredictionQuestionOptionView;
import com.lbz.f1aipredict.question.service.PredictionQuestionView;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将已冻结字段序列化成与 Python v2 DTO 严格一致的消息。 */
@Component
public class PredictionRequestSerializer {

    private static final int MAX_BYTES = 262_144;
    private final ObjectMapper mapper;

    public PredictionRequestSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 仅放入白名单字段，不包含快照原始 JSON 或答案。 */
    public String serialize(PredictionJob job, PredictionQuestionView view,
                            PredictionBatchCreateContext context) {
        PredictionRequestSnapshotResolver.FrozenBatch batch = context.frozenBatch();
        PredictionRequestSnapshotResolver.FrozenQuestion frozen = batch.questions().get(view.getQuestionId());
        if (frozen == null) {
            throw new IllegalStateException("Frozen prediction question is missing");
        }
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("questionText", frozen.questionText());
        question.put("subText", frozen.subText());
        question.put("questionType", "UNKNOWN");
        question.put("optionTemplateId", frozen.optionTemplateId());
        question.put("choiceLimit", frozen.choiceLimit());
        question.put("options", view.getOptions().stream().map(this::option).toList());
        Map<String, Object> race = new LinkedHashMap<>();
        race.put("seasonId", batch.seasonId());
        race.put("year", batch.year());
        race.put("roundId", context.roundId());
        race.put("roundNumber", batch.roundNumber());
        race.put("meetingKey", frozen.meetingKey());
        race.put("sessionKey", frozen.sessionKey());
        race.put("gamedayId", frozen.gamedayId());
        race.put("trackName", batch.trackName());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "2");
        payload.put("messageId", job.getMessageId());
        payload.put("predictionJobId", job.getPredictionJobId());
        payload.put("batchId", job.getBatchId());
        payload.put("questionId", view.getQuestionId());
        payload.put("traceId", context.traceId());
        payload.put("questionSnapshotId", view.getSnapshotId());
        payload.put("question", question);
        payload.put("raceContext", race);
        payload.put("dataCutoff", DateTimeFormatter.ISO_INSTANT.format(context.dataCutoff()));
        payload.put("modelVersion", context.modelVersion());
        payload.put("promptVersion", context.promptVersion());
        payload.put("featureVersion", context.featureVersion());
        payload.put("embeddingVersion", null);
        payload.put("retrieverVersion", null);
        try {
            String json = mapper.writeValueAsString(payload);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new IllegalStateException("Prediction request exceeds size limit");
            }
            return json;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Prediction request serialization failed", error);
        }
    }

    private Map<String, Object> option(PredictionQuestionOptionView view) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("optionId", view.getOptionId());
        option.put("optionNo", view.getOptionNo());
        option.put("optionText", view.getOptionText());
        option.put("points", view.getPoints());
        option.put("chance", view.getChance());
        return option;
    }
}
