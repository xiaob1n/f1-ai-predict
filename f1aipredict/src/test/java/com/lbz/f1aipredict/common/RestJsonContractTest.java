package com.lbz.f1aipredict.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lbz.f1aipredict.common.dto.ApiErrorResponse;
import com.lbz.f1aipredict.prediction.dto.CreatePredictionBatchRequest;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDetailDto;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDto;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchLockDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobOutcomeDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobPageDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobQuery;
import com.lbz.f1aipredict.prediction.dto.PredictionJobStatusCountsDto;
import com.lbz.f1aipredict.question.dto.QuestionDetailDto;
import com.lbz.f1aipredict.question.dto.QuestionDto;
import com.lbz.f1aipredict.question.dto.QuestionOptionDto;
import com.lbz.f1aipredict.question.dto.QuestionQuery;
import com.lbz.f1aipredict.question.dto.QuestionSnapshotDto;
import com.lbz.f1aipredict.season.dto.CurrentRoundDto;
import com.lbz.f1aipredict.season.dto.MeetingSessionDto;
import com.lbz.f1aipredict.season.dto.RoundDto;
import com.lbz.f1aipredict.season.dto.SeasonDto;
import com.lbz.f1aipredict.season.dto.SeasonPageDto;
import com.lbz.f1aipredict.season.dto.SeasonQuery;
import com.lbz.f1aipredict.sync.dto.RawPayloadDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordPageDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordQuery;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RestJsonContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void restDtosSerializeIndependentSnakeCaseKeyContracts() {
        Map<Object, Set<String>> contracts = new LinkedHashMap<>();
        PredictionBatchLockDto lock = new PredictionBatchLockDto(true,
                Instant.parse("2026-10-08T12:00:00Z"), Instant.parse("2026-10-08T11:59:00Z"));
        contracts.put(ApiErrorResponse.builder().code("ERROR").message("Message").build(), Set.of("code", "message"));
        contracts.put(SeasonDto.builder().build(), Set.of("id", "year", "name", "status", "start_date", "end_date"));
        contracts.put(RoundDto.builder().build(), Set.of("id", "season_id", "round_number", "grand_prix_name",
                "official_name", "circuit_name", "country", "locality", "start_date", "end_date", "status"));
        contracts.put(MeetingSessionDto.builder().build(), Set.of("id", "round_id", "meeting_key", "session_key",
                "session_name", "session_type", "gameday_id", "start_date_utc", "end_date_utc", "status"));
        contracts.put(CurrentRoundDto.builder().build(), Set.of("round", "season", "sessions", "gameday_id"));
        contracts.put(SeasonPageDto.builder().build(), Set.of("items", "page", "size", "total"));
        contracts.put(QuestionOptionDto.builder().build(), Set.of("option_id", "option_no", "option_text", "points", "chance"));
        contracts.put(QuestionDto.builder().build(), Set.of("question_id", "gameday_id", "source_question_id", "question_no",
                "question_text", "sub_text", "question_type", "option_template_id", "choice_limit", "status",
                "latest_snapshot_id", "options"));
        contracts.put(QuestionDetailDto.builder().build(), Set.of("question_id", "gameday_id", "source_question_id",
                "question_no", "question_text", "sub_text", "question_type", "option_template_id", "choice_limit",
                "status", "latest_snapshot_id", "options"));
        contracts.put(QuestionSnapshotDto.builder().build(), Set.of("snapshot_id", "question_id", "snapshot_no", "content_hash",
                "snapshot_reason", "created_at", "has_raw_json", "options"));
        contracts.put(CreatePredictionBatchRequest.builder().build(), Set.of("question_ids", "all_open_questions", "data_cutoff",
                "prediction_deadline", "feature_version", "model_version", "prompt_version"));
        contracts.put(PredictionBatchDto.builder().build(), Set.of("batch_id", "round_id", "status", "question_count", "job_ids"));
        contracts.put(PredictionBatchDetailDto.builder().build(), Set.of("batch_id", "round_id", "batch_no", "status",
                "question_count", "data_cutoff", "created_at", "updated_at", "status_counts"));
        contracts.put(PredictionJobDto.builder().build(), Set.of("prediction_job_id", "batch_id", "question_id",
                "question_snapshot_id", "status", "retry_count", "max_retries", "worker_node", "data_cutoff",
                "feature_version", "model_version", "prompt_version", "locked_at", "completed_at", "created_at", "updated_at"));
        contracts.put(PredictionJobPageDto.builder().build(), Set.of("items", "page", "size", "total"));
        contracts.put(PredictionJobStatusCountsDto.builder().build(), Set.of("pending_count", "running_count", "retrying_count",
                "succeeded_count", "failed_count", "dead_letter_count", "unknown_count"));
        contracts.put(PredictionJobOutcomeDto.builder().build(), Set.of("prediction_job_id", "status", "result", "failure"));
        contracts.put(RawPayloadDto.builder().build(), Set.of("payload_id", "source_type", "source_url", "gameday_id",
                "content_hash", "raw_json", "fetched_at"));
        contracts.put(SyncRecordDto.builder().build(), Set.of("sync_record_id", "source_type", "source_url", "gameday_id",
                "content_hash", "status", "http_status", "error_message", "duration_ms", "synced_at"));
        contracts.put(SyncRecordPageDto.builder().build(), Set.of("items", "page", "size", "total"));
        contracts.put(SyncResultDto.builder().build(), Set.of("source_type", "status", "content_hash", "sync_record_id",
                "payload_id", "error_message", "gameday_id", "schedule_result", "questions_result"));
        contracts.put(lock, Set.of("newly_locked", "locked_at", "prediction_deadline"));

        assertThat(contracts).hasSize(22);
        contracts.forEach((dto, expectedKeys) -> {
            JsonNode json = MAPPER.valueToTree(dto);
            assertThat(keys(json))
                    .as("%s 的 REST JSON 键", dto.getClass().getSimpleName())
                    .containsExactlyInAnyOrderElementsOf(expectedKeys);
        });

    }

    @Test
    void queryDtosAreExplicitCamelCaseExceptions() {
        Map<Object, Set<String>> camelCaseExceptions = new LinkedHashMap<>();
        camelCaseExceptions.put(SeasonQuery.builder().build(), Set.of("status", "page", "size"));
        camelCaseExceptions.put(QuestionQuery.builder().build(), Set.of("status", "gamedayId", "includeOptions", "snapshotId"));
        camelCaseExceptions.put(PredictionJobQuery.builder().build(), Set.of("status", "page", "size"));
        camelCaseExceptions.put(SyncRecordQuery.builder().build(), Set.of("sourceType", "gamedayId", "status", "page", "size"));

        camelCaseExceptions.forEach((query, expectedKeys) -> assertThat(keys(MAPPER.valueToTree(query)))
                .as("%s 查询参数键保持 camelCase", query.getClass().getSimpleName())
                .containsExactlyInAnyOrderElementsOf(expectedKeys));
    }

    @Test
    void requestBindsSnakeCaseKeysAndNormalizesInstantsToUtc() throws Exception {
        CreatePredictionBatchRequest request = MAPPER.readValue("""
                {
                  "question_ids": [1, 2],
                  "data_cutoff": "2026-08-28T21:00:00+11:00",
                  "prediction_deadline": "2026-08-29T09:00:00+11:00",
                  "feature_version": "feature-v1",
                  "model_version": "model-v1",
                  "prompt_version": "prompt-v1"
                }
                """, CreatePredictionBatchRequest.class);

        assertThat(request.getQuestionIds()).containsExactly(1L, 2L);
        assertThat(request.getDataCutoff()).isEqualTo(Instant.parse("2026-08-28T10:00:00Z"));
        assertThat(request.getPredictionDeadline()).isEqualTo(Instant.parse("2026-08-28T22:00:00Z"));

        JsonNode serialized = MAPPER.valueToTree(request);
        assertThat(keys(serialized)).containsExactlyInAnyOrder("question_ids", "all_open_questions", "data_cutoff",
                "prediction_deadline", "feature_version", "model_version", "prompt_version");
        assertThat(serialized.has("questionIds")).isFalse();
        assertThat(serialized.get("data_cutoff").asText()).isEqualTo("2026-08-28T10:00:00Z");
        assertThat(serialized.get("prediction_deadline").asText()).isEqualTo("2026-08-28T22:00:00Z");
    }

    @Test
    void nestedPredictionKeysUseSnakeCaseAndSnapshotDoesNotLeakRawJson() {
        QuestionDto question = QuestionDto.builder().questionId(8L)
                .options(List.of(QuestionOptionDto.builder().optionId(3).build())).build();
        JsonNode questionNode = MAPPER.valueToTree(question);
        assertThat(keys(questionNode)).containsExactlyInAnyOrder("question_id", "gameday_id", "source_question_id",
                "question_no", "question_text", "sub_text", "question_type", "option_template_id", "choice_limit",
                "status", "latest_snapshot_id", "options");
        assertThat(keys(questionNode.get("options").get(0)))
                .containsExactlyInAnyOrder("option_id", "option_no", "option_text", "points", "chance");

        QuestionSnapshotDto snapshot = QuestionSnapshotDto.builder().hasRawJson(true).build();
        JsonNode snapshotNode = MAPPER.valueToTree(snapshot);
        assertThat(snapshotNode.has("has_raw_json")).isTrue();
        assertThat(snapshotNode.has("raw_json")).isFalse();

        PredictionJobOutcomeDto outcome = PredictionJobOutcomeDto.builder().predictionJobId("job-1")
                .result(PredictionJobOutcomeDto.Result.builder().selectedOptions(List.of(
                        PredictionJobOutcomeDto.SelectedOption.builder().optionId(5).position(1).build())).build())
                .failure(PredictionJobOutcomeDto.Failure.builder().failureCode("BAD_RESULT").summary("Rejected").build())
                .build();
        JsonNode outcomeNode = MAPPER.valueToTree(outcome);
        assertThat(keys(outcomeNode)).containsExactlyInAnyOrder("prediction_job_id", "status", "result", "failure");
        assertThat(keys(outcomeNode.get("result"))).containsExactlyInAnyOrder("selected_options", "confidence");
        assertThat(keys(outcomeNode.get("result").get("selected_options").get(0)))
                .containsExactlyInAnyOrder("option_id", "position");
        assertThat(keys(outcomeNode.get("failure"))).containsExactlyInAnyOrder("failure_code", "summary");

        PredictionJobStatusCountsDto counts = PredictionJobStatusCountsDto.builder().build();
        assertThat(keys(MAPPER.valueToTree(counts))).containsExactlyInAnyOrder("pending_count", "running_count",
                "retrying_count", "succeeded_count", "failed_count", "dead_letter_count", "unknown_count");
    }

    private static Set<String> keys(JsonNode node) {
        return java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(node.fieldNames(), 0), false)
                .collect(Collectors.toSet());
    }
}
