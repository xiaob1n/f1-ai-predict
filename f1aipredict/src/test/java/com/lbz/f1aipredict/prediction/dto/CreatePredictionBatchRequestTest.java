package com.lbz.f1aipredict.prediction.dto;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 创建预测批次请求的 Jackson / Bean Validation 契约。
 * <p>
 * 约定缝：Jackson 2 反序列化（含时区 Instant）与 Jakarta Validator 公开校验结果。
 * 不启动 Spring、不连库。合法请求只有两种：仅非空 questionIds，或仅 allOpenQuestions=true。
 */
class CreatePredictionBatchRequestTest {

    /**
     * Boot 3.5.16 / Jackson 2：纯单测必须显式注册 {@link JavaTimeModule}
     * 并关闭时间戳，Instant 才按 ISO-8601 文本往返。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** SQL 004 {@code feature_version VARCHAR(32)} 的最大长度。 */
    private static final int FEATURE_VERSION_MAX = 32;

    /** SQL 004 {@code model_version VARCHAR(64)} / {@code prompt_version VARCHAR(64)}。 */
    private static final int MODEL_OR_PROMPT_VERSION_MAX = 64;

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    @Test
    @DisplayName("仅非空 questionIds 的合法 JSON 反序列化为 UTC Instant 且通过校验")
    void deserializesExplicitQuestionIdsAndPassesValidation() throws Exception {
        CreatePredictionBatchRequest request = MAPPER.readValue("""
                {
                  "question_ids": [1, 2, 3, 4],
                  "data_cutoff": "2026-08-28T10:00:00Z",
                  "feature_version": "feature-v1",
                  "model_version": "qwen3-8b-lora-v1",
                  "prompt_version": "f1-race-v1"
                }
                """, CreatePredictionBatchRequest.class);

        assertThat(request.getQuestionIds()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(request.getAllOpenQuestions()).isNull();
        assertThat(request.getDataCutoff()).isEqualTo(Instant.parse("2026-08-28T10:00:00Z"));
        assertThat(request.getFeatureVersion()).isEqualTo("feature-v1");
        assertThat(request.getModelVersion()).isEqualTo("qwen3-8b-lora-v1");
        assertThat(request.getPromptVersion()).isEqualTo("f1-race-v1");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("仅 allOpenQuestions=true 的合法 JSON 通过校验，带偏移的 dataCutoff 归一为 UTC")
    void deserializesAllOpenQuestionsAndNormalizesOffsetCutoffToUtc() throws Exception {
        CreatePredictionBatchRequest request = MAPPER.readValue("""
                {
                  "all_open_questions": true,
                  "data_cutoff": "2026-08-28T21:00:00+11:00",
                  "feature_version": "feature-v1",
                  "model_version": "qwen3-8b-lora-v1",
                  "prompt_version": "f1-race-v1"
                }
                """, CreatePredictionBatchRequest.class);

        assertThat(request.getAllOpenQuestions()).isTrue();
        assertThat(request.getQuestionIds()).isNull();
        assertThat(request.getDataCutoff()).isEqualTo(Instant.parse("2026-08-28T10:00:00Z"));
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("业务截止按时区转换为 UTC，且不借用数据可见性截止")
    void deserializesPredictionDeadlineIndependently() throws Exception {
        CreatePredictionBatchRequest request = MAPPER.readValue("""
                {
                  "question_ids": [1],
                  "data_cutoff": "2026-08-28T10:00:00Z",
                  "prediction_deadline": "2026-08-28T22:00:00+11:00",
                  "feature_version": "feature-v1",
                  "model_version": "model-v1",
                  "prompt_version": "prompt-v1"
                }
                """, CreatePredictionBatchRequest.class);
        assertThat(request.getDataCutoff()).isEqualTo(Instant.parse("2026-08-28T10:00:00Z"));
        assertThat(request.getPredictionDeadline()).isEqualTo(Instant.parse("2026-08-28T11:00:00Z"));
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("无时区的 dataCutoff 必须反序列化失败")
    void rejectsDataCutoffWithoutTimezone() {
        assertThatThrownBy(() -> MAPPER.readValue("""
                {
                  "question_ids": [1],
                  "data_cutoff": "2026-08-28T10:00:00",
                  "feature_version": "feature-v1",
                  "model_version": "qwen3-8b-lora-v1",
                  "prompt_version": "f1-race-v1"
                }
                """, CreatePredictionBatchRequest.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    @DisplayName("同时提供非空 questionIds 与 allOpenQuestions=true 校验失败")
    void rejectsQuestionIdsTogetherWithAllOpenQuestions() {
        CreatePredictionBatchRequest request = validExplicitIds();
        request.setAllOpenQuestions(true);

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("既无 questionIds 也未 allOpenQuestions=true 校验失败")
    void rejectsNeitherSelection() {
        CreatePredictionBatchRequest request = validVersionsAndCutoff();

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("allOpenQuestions=false 且无 questionIds 校验失败")
    void rejectsAllOpenQuestionsFalseWithoutIds() {
        CreatePredictionBatchRequest request = validVersionsAndCutoff();
        request.setAllOpenQuestions(false);

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("questionIds 空数组校验失败")
    void rejectsEmptyQuestionIds() {
        CreatePredictionBatchRequest request = validVersionsAndCutoff();
        request.setQuestionIds(List.of());

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("allOpenQuestions=true 同时带空 questionIds 数组仍失败")
    void rejectsAllOpenTrueWithEmptyQuestionIds() {
        CreatePredictionBatchRequest request = validVersionsAndCutoff();
        request.setAllOpenQuestions(true);
        request.setQuestionIds(List.of());

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("重复 questionIds 校验失败")
    void rejectsDuplicateQuestionIds() {
        CreatePredictionBatchRequest request = validVersionsAndCutoff();
        request.setQuestionIds(List.of(1L, 2L, 1L));

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("101 个 questionIds 超过上限校验失败")
    void rejectsOneHundredAndOneQuestionIds() {
        CreatePredictionBatchRequest request = validVersionsAndCutoff();
        request.setQuestionIds(LongStream.rangeClosed(1, 101).boxed().toList());

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("100 个不重复 questionIds 为数量上限内合法")
    void acceptsOneHundredDistinctQuestionIds() {
        CreatePredictionBatchRequest request = validVersionsAndCutoff();
        request.setQuestionIds(LongStream.rangeClosed(1, 100).boxed().toList());

        assertThat(validator.validate(request)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("featureVersion 拒绝 null、空白")
    void rejectsBlankFeatureVersion(String value) {
        CreatePredictionBatchRequest request = validExplicitIds();
        request.setFeatureVersion(value);

        assertThat(violatedProperties(request)).contains("featureVersion");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("modelVersion 拒绝 null、空白")
    void rejectsBlankModelVersion(String value) {
        CreatePredictionBatchRequest request = validExplicitIds();
        request.setModelVersion(value);

        assertThat(violatedProperties(request)).contains("modelVersion");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("promptVersion 拒绝 null、空白")
    void rejectsBlankPromptVersion(String value) {
        CreatePredictionBatchRequest request = validExplicitIds();
        request.setPromptVersion(value);

        assertThat(violatedProperties(request)).contains("promptVersion");
    }

    @Test
    @DisplayName("featureVersion 超过 SQL 004 VARCHAR(32) 校验失败，32 字符通过")
    void featureVersionLengthMatchesSql004() {
        CreatePredictionBatchRequest atLimit = validExplicitIds();
        atLimit.setFeatureVersion("f".repeat(FEATURE_VERSION_MAX));
        assertThat(validator.validate(atLimit)).isEmpty();

        CreatePredictionBatchRequest tooLong = validExplicitIds();
        tooLong.setFeatureVersion("f".repeat(FEATURE_VERSION_MAX + 1));
        assertThat(violatedProperties(tooLong)).contains("featureVersion");
    }

    @Test
    @DisplayName("modelVersion 超过 SQL 004 VARCHAR(64) 校验失败，64 字符通过")
    void modelVersionLengthMatchesSql004() {
        CreatePredictionBatchRequest atLimit = validExplicitIds();
        atLimit.setModelVersion("m".repeat(MODEL_OR_PROMPT_VERSION_MAX));
        assertThat(validator.validate(atLimit)).isEmpty();

        CreatePredictionBatchRequest tooLong = validExplicitIds();
        tooLong.setModelVersion("m".repeat(MODEL_OR_PROMPT_VERSION_MAX + 1));
        assertThat(violatedProperties(tooLong)).contains("modelVersion");
    }

    @Test
    @DisplayName("promptVersion 超过 SQL 004 VARCHAR(64) 校验失败，64 字符通过")
    void promptVersionLengthMatchesSql004() {
        CreatePredictionBatchRequest atLimit = validExplicitIds();
        atLimit.setPromptVersion("p".repeat(MODEL_OR_PROMPT_VERSION_MAX));
        assertThat(validator.validate(atLimit)).isEmpty();

        CreatePredictionBatchRequest tooLong = validExplicitIds();
        tooLong.setPromptVersion("p".repeat(MODEL_OR_PROMPT_VERSION_MAX + 1));
        assertThat(violatedProperties(tooLong)).contains("promptVersion");
    }

    @Test
    @DisplayName("缺失 dataCutoff 校验失败")
    void rejectsNullDataCutoff() {
        CreatePredictionBatchRequest request = validExplicitIds();
        request.setDataCutoff(null);

        assertThat(violatedProperties(request)).contains("dataCutoff");
    }

    @Test
    @DisplayName("allOpenQuestions=false 但提供非空 questionIds 合法")
    void acceptsExplicitIdsWhenAllOpenQuestionsIsFalse() {
        CreatePredictionBatchRequest request = validExplicitIds();
        request.setAllOpenQuestions(false);

        assertThat(validator.validate(request)).isEmpty();
    }

    /**
     * 合法的显式题目请求，版本与截止时间已填好。
     */
    private static CreatePredictionBatchRequest validExplicitIds() {
        CreatePredictionBatchRequest request = validVersionsAndCutoff();
        request.setQuestionIds(new ArrayList<>(List.of(1L, 2L, 3L, 4L)));
        return request;
    }

    /**
     * 只填版本与截止时间，用于构造非法选择组合。
     */
    private static CreatePredictionBatchRequest validVersionsAndCutoff() {
        return CreatePredictionBatchRequest.builder()
                .dataCutoff(Instant.parse("2026-08-28T10:00:00Z"))
                .featureVersion("feature-v1")
                .modelVersion("qwen3-8b-lora-v1")
                .promptVersion("f1-race-v1")
                .build();
    }

    private static Set<String> violatedProperties(CreatePredictionBatchRequest request) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (ConstraintViolation<CreatePredictionBatchRequest> violation : validator.validate(request)) {
            names.add(violation.getPropertyPath().toString());
        }
        return names;
    }
}
