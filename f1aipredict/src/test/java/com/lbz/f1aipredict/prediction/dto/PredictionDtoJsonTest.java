package com.lbz.f1aipredict.prediction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预测批次/任务对外 DTO 的 Jackson 反射契约。
 * <p>
 * 约定缝：公有 JSON 序列化结果、声明字段反射、{@link PredictionJobQuery} 的 getter 与 normalize。
 * 不启动 Spring、不连库。响应不得出现内部 job id、messageId、lastError。
 */
class PredictionDtoJsonTest {

    /** 本任务锁定的 7 个 prediction DTO / 查询对象。 */
    private static final List<Class<?>> DTO_CLASSES = List.of(
            CreatePredictionBatchRequest.class,
            PredictionBatchDto.class,
            PredictionBatchDetailDto.class,
            PredictionJobDto.class,
            PredictionJobQuery.class,
            PredictionJobPageDto.class,
            PredictionJobStatusCountsDto.class,
            PredictionJobOutcomeDto.class,
            PredictionJobOutcomeDto.Result.class,
            PredictionJobOutcomeDto.SelectedOption.class,
            PredictionJobOutcomeDto.Failure.class
    );

    /**
     * Boot 3.5.16 / Jackson 2：纯单测必须显式注册 {@link JavaTimeModule}
     * 并关闭时间戳序列化，Instant 才输出带 Z 的 ISO-8601。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 反射扫描：返回缺失 {@link JsonProperty} 或注解值与字段名不一致的实例字段。
     * 跳过 static/synthetic，避免把查询对象常量误判为 JSON 字段。
     */
    static List<String> jsonPropertyViolations(Class<?> dtoClass) {
        List<String> violations = new ArrayList<>();
        for (Field field : dtoClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            JsonProperty ann = field.getAnnotation(JsonProperty.class);
            if (ann == null) {
                violations.add(field.getName() + " (missing @JsonProperty)");
            } else if (!ann.value().equals(expectedJsonName(dtoClass, field.getName()))) {
                violations.add(field.getName() + " -> @" + ann.value() + " (expected @" + expectedJsonName(dtoClass, field.getName()) + ")");
            }
        }
        return violations;
    }

    private static String expectedJsonName(Class<?> dtoClass, String fieldName) {
        return dtoClass.getSimpleName().endsWith("Query") ? fieldName : toSnakeCase(fieldName);
    }

    private static String toSnakeCase(String fieldName) {
        return fieldName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> instanceFieldNames(Class<?> dtoClass) {
        List<String> names = new ArrayList<>();
        for (Field field : dtoClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            names.add(field.getName());
        }
        return names;
    }

    private static void assertExactFieldNames(Class<?> dtoClass, String... expectedNames) {
        assertThat(instanceFieldNames(dtoClass))
                .as("%s 字段集合", dtoClass.getSimpleName())
                .containsExactlyInAnyOrder(expectedNames);
    }

    private static void assertFieldType(Class<?> dtoClass, String fieldName, Class<?> expectedType)
            throws Exception {
        Field field = dtoClass.getDeclaredField(fieldName);
        assertThat(field.getType())
                .as("%s.%s 的类型", dtoClass.getSimpleName(), fieldName)
                .isEqualTo(expectedType);
    }

    private static boolean hasForbiddenJsonOrFieldName(Class<?> dtoClass, String forbidden) {
        for (Field field : dtoClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            if (field.getName().equals(forbidden)) {
                return true;
            }
            JsonProperty ann = field.getAnnotation(JsonProperty.class);
            if (ann != null && forbidden.equals(ann.value())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("每个 DTO 都具备 Lombok @Data @Builder @NoArgsConstructor @AllArgsConstructor 生成的公开 API")
    void everyDtoHasRequiredLombokGeneratedApi() {
        // Lombok 注解保留策略为 CLASS，运行时 getAnnotation 不可见；
        // 约定缝改为公开构造器 / builder / getter-setter，证明四个注解均已生效。
        Map<Class<?>, List<String>> missing = new LinkedHashMap<>();
        for (Class<?> dtoClass : DTO_CLASSES) {
            List<String> absent = new ArrayList<>();
            if (!hasNoArgsConstructor(dtoClass)) {
                absent.add("NoArgsConstructor");
            }
            if (!hasAllArgsConstructor(dtoClass)) {
                absent.add("AllArgsConstructor");
            }
            if (!hasPublicStaticBuilder(dtoClass)) {
                absent.add("Builder");
            }
            if (!hasDataAccessors(dtoClass)) {
                absent.add("Data");
            }
            if (!absent.isEmpty()) {
                missing.put(dtoClass, absent);
            }
        }
        assertThat(missing).as("全部 prediction DTO 必须具备四个 Lombok 注解生成的公开 API").isEmpty();
    }

    /** 无参构造存在即对应 @NoArgsConstructor。 */
    private static boolean hasNoArgsConstructor(Class<?> dtoClass) {
        try {
            Constructor<?> constructor = dtoClass.getDeclaredConstructor();
            return Modifier.isPublic(constructor.getModifiers());
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    /** 全参构造参数个数等于实例字段数，对应 @AllArgsConstructor。 */
    private static boolean hasAllArgsConstructor(Class<?> dtoClass) {
        int fieldCount = instanceFieldNames(dtoClass).size();
        for (Constructor<?> constructor : dtoClass.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers())
                    && constructor.getParameterCount() == fieldCount
                    && fieldCount > 0) {
                return true;
            }
        }
        return false;
    }

    /** 静态 builder() 对应 @Builder。 */
    private static boolean hasPublicStaticBuilder(Class<?> dtoClass) {
        try {
            Method builder = dtoClass.getMethod("builder");
            return Modifier.isStatic(builder.getModifiers()) && Modifier.isPublic(builder.getModifiers());
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    /** 每个实例字段都有 getter/setter，对应 @Data。 */
    private static boolean hasDataAccessors(Class<?> dtoClass) {
        for (String fieldName : instanceFieldNames(dtoClass)) {
            String suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            try {
                dtoClass.getMethod("get" + suffix);
                dtoClass.getMethod("set" + suffix, dtoClass.getDeclaredField(fieldName).getType());
            } catch (NoSuchMethodException | NoSuchFieldException ex) {
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("REST DTO 字段使用 snake_case，查询 DTO 字段保留 camelCase @JsonProperty")
    void everyDtoFieldHasExplicitMatchingJsonProperty() {
        Map<Class<?>, List<String>> violationsByName = new LinkedHashMap<>();
        for (Class<?> dtoClass : DTO_CLASSES) {
            List<String> violations = jsonPropertyViolations(dtoClass);
            if (!violations.isEmpty()) {
                violationsByName.put(dtoClass, violations);
            }
        }
        assertThat(violationsByName)
                .as("REST DTO 使用 snake_case 映射，PredictionJobQuery 保持 camelCase")
                .isEmpty();
    }

    @Test
    @DisplayName("响应 DTO 不得暴露内部 job id、messageId、lastError、rawJson")
    void responseDtosMustNotExposeInternalJobFields() {
        String[] forbidden = {"id", "messageId", "lastError", "rawJson", "entity"};
        List<Class<?>> responseClasses = List.of(
                PredictionBatchDto.class,
                PredictionBatchDetailDto.class,
                PredictionJobDto.class,
                PredictionJobPageDto.class,
                PredictionJobStatusCountsDto.class,
                PredictionJobOutcomeDto.class,
                PredictionJobOutcomeDto.Result.class,
                PredictionJobOutcomeDto.SelectedOption.class,
                PredictionJobOutcomeDto.Failure.class
        );
        Map<Class<?>, List<String>> violations = new LinkedHashMap<>();
        for (Class<?> dtoClass : responseClasses) {
            List<String> found = new ArrayList<>();
            for (String name : forbidden) {
                if (hasForbiddenJsonOrFieldName(dtoClass, name)) {
                    found.add(name);
                }
            }
            if (!found.isEmpty()) {
                violations.put(dtoClass, found);
            }
        }
        assertThat(violations).as("响应不得出现内部 id / messageId / lastError").isEmpty();
    }

    @Test
    @DisplayName("CreatePredictionBatchRequest 字段集合与类型精确匹配计划契约")
    void createRequestFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(CreatePredictionBatchRequest.class,
                "questionIds", "allOpenQuestions", "dataCutoff", "predictionDeadline",
                "featureVersion", "modelVersion", "promptVersion");
        assertFieldType(CreatePredictionBatchRequest.class, "questionIds", List.class);
        assertFieldType(CreatePredictionBatchRequest.class, "allOpenQuestions", Boolean.class);
        assertFieldType(CreatePredictionBatchRequest.class, "dataCutoff", Instant.class);
        assertFieldType(CreatePredictionBatchRequest.class, "predictionDeadline", Instant.class);
        assertFieldType(CreatePredictionBatchRequest.class, "featureVersion", String.class);
        assertFieldType(CreatePredictionBatchRequest.class, "modelVersion", String.class);
        assertFieldType(CreatePredictionBatchRequest.class, "promptVersion", String.class);
    }

    @Test
    @DisplayName("PredictionBatchDto 字段集合与类型精确匹配创建响应契约")
    void batchDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(PredictionBatchDto.class,
                "batchId", "roundId", "status", "questionCount", "jobIds");
        assertFieldType(PredictionBatchDto.class, "batchId", Long.class);
        assertFieldType(PredictionBatchDto.class, "roundId", Long.class);
        assertFieldType(PredictionBatchDto.class, "status", String.class);
        assertFieldType(PredictionBatchDto.class, "questionCount", Integer.class);
        assertFieldType(PredictionBatchDto.class, "jobIds", List.class);
    }

    @Test
    @DisplayName("PredictionBatchDetailDto 字段集合与类型精确匹配详情契约")
    void batchDetailDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(PredictionBatchDetailDto.class,
                "batchId", "roundId", "batchNo", "status", "questionCount",
                "dataCutoff", "createdAt", "updatedAt", "statusCounts");
        assertFieldType(PredictionBatchDetailDto.class, "batchId", Long.class);
        assertFieldType(PredictionBatchDetailDto.class, "roundId", Long.class);
        assertFieldType(PredictionBatchDetailDto.class, "batchNo", Integer.class);
        assertFieldType(PredictionBatchDetailDto.class, "status", String.class);
        assertFieldType(PredictionBatchDetailDto.class, "questionCount", Integer.class);
        assertFieldType(PredictionBatchDetailDto.class, "dataCutoff", Instant.class);
        assertFieldType(PredictionBatchDetailDto.class, "createdAt", Instant.class);
        assertFieldType(PredictionBatchDetailDto.class, "updatedAt", Instant.class);
        assertFieldType(PredictionBatchDetailDto.class, "statusCounts", PredictionJobStatusCountsDto.class);
    }

    @Test
    @DisplayName("PredictionJobStatusCountsDto 覆盖全部任务状态计数口径")
    void statusCountsDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(PredictionJobStatusCountsDto.class,
                "pendingCount", "runningCount", "retryingCount", "succeededCount",
                "failedCount", "deadLetterCount", "unknownCount");
        assertFieldType(PredictionJobStatusCountsDto.class, "pendingCount", Integer.class);
        assertFieldType(PredictionJobStatusCountsDto.class, "runningCount", Integer.class);
        assertFieldType(PredictionJobStatusCountsDto.class, "retryingCount", Integer.class);
        assertFieldType(PredictionJobStatusCountsDto.class, "succeededCount", Integer.class);
        assertFieldType(PredictionJobStatusCountsDto.class, "failedCount", Integer.class);
        assertFieldType(PredictionJobStatusCountsDto.class, "deadLetterCount", Integer.class);
        assertFieldType(PredictionJobStatusCountsDto.class, "unknownCount", Integer.class);
    }

    @Test
    @DisplayName("PredictionJobDto 字段集合与类型精确匹配任务详情契约")
    void jobDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(PredictionJobDto.class,
                "predictionJobId", "batchId", "questionId", "questionSnapshotId",
                "status", "retryCount", "maxRetries", "workerNode", "dataCutoff",
                "featureVersion", "modelVersion", "promptVersion",
                "lockedAt", "completedAt", "createdAt", "updatedAt");
        assertFieldType(PredictionJobDto.class, "predictionJobId", String.class);
        assertFieldType(PredictionJobDto.class, "batchId", Long.class);
        assertFieldType(PredictionJobDto.class, "questionId", Long.class);
        assertFieldType(PredictionJobDto.class, "questionSnapshotId", Long.class);
        assertFieldType(PredictionJobDto.class, "status", String.class);
        assertFieldType(PredictionJobDto.class, "retryCount", Integer.class);
        assertFieldType(PredictionJobDto.class, "maxRetries", Integer.class);
        assertFieldType(PredictionJobDto.class, "workerNode", String.class);
        assertFieldType(PredictionJobDto.class, "dataCutoff", Instant.class);
        assertFieldType(PredictionJobDto.class, "featureVersion", String.class);
        assertFieldType(PredictionJobDto.class, "modelVersion", String.class);
        assertFieldType(PredictionJobDto.class, "promptVersion", String.class);
        assertFieldType(PredictionJobDto.class, "lockedAt", Instant.class);
        assertFieldType(PredictionJobDto.class, "completedAt", Instant.class);
        assertFieldType(PredictionJobDto.class, "createdAt", Instant.class);
        assertFieldType(PredictionJobDto.class, "updatedAt", Instant.class);
    }

    @Test
    @DisplayName("PredictionJobQuery 字段集合与类型精确匹配分页查询契约")
    void jobQueryFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(PredictionJobQuery.class, "status", "page", "size");
        assertFieldType(PredictionJobQuery.class, "status", String.class);
        assertFieldType(PredictionJobQuery.class, "page", Integer.class);
        assertFieldType(PredictionJobQuery.class, "size", Integer.class);
    }

    @Test
    @DisplayName("PredictionJobPageDto 字段集合与类型精确匹配分页响应契约")
    void jobPageDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(PredictionJobPageDto.class, "items", "page", "size", "total");
        assertFieldType(PredictionJobPageDto.class, "items", List.class);
        assertFieldType(PredictionJobPageDto.class, "page", Integer.class);
        assertFieldType(PredictionJobPageDto.class, "size", Integer.class);
        assertFieldType(PredictionJobPageDto.class, "total", Long.class);
    }

    @Test
    @DisplayName("PredictionJobQuery 默认 page=0 size=20，normalize 双重裁剪 size 上限 100")
    void jobQueryDefaultsAndClampPaging() {
        assertThat(new PredictionJobQuery().getPage()).isEqualTo(0);
        assertThat(new PredictionJobQuery().getSize()).isEqualTo(20);
        assertThat(PredictionJobQuery.builder().build().getPage()).isEqualTo(0);
        assertThat(PredictionJobQuery.builder().build().getSize()).isEqualTo(20);

        PredictionJobQuery negative = PredictionJobQuery.builder().page(-3).size(0).build();
        negative.clampPaging();
        assertThat(negative.getPage()).isEqualTo(0);
        assertThat(negative.getSize()).isEqualTo(20);

        PredictionJobQuery oversized = PredictionJobQuery.builder().page(2).size(101).build();
        oversized.clampPaging();
        assertThat(oversized.getPage()).isEqualTo(2);
        assertThat(oversized.getSize()).isEqualTo(100);

        PredictionJobQuery nulls = new PredictionJobQuery();
        nulls.setPage(null);
        nulls.setSize(null);
        nulls.clampPaging();
        assertThat(nulls.getPage()).isEqualTo(0);
        assertThat(nulls.getSize()).isEqualTo(20);

        assertThat(PredictionJobQuery.clampPage(null)).isEqualTo(0);
        assertThat(PredictionJobQuery.clampPage(-1)).isEqualTo(0);
        assertThat(PredictionJobQuery.clampSize(null)).isEqualTo(20);
        assertThat(PredictionJobQuery.clampSize(0)).isEqualTo(20);
        assertThat(PredictionJobQuery.clampSize(1)).isEqualTo(1);
        assertThat(PredictionJobQuery.clampSize(100)).isEqualTo(100);
        assertThat(PredictionJobQuery.clampSize(101)).isEqualTo(100);
    }

    @Test
    @DisplayName("创建响应序列化：PENDING 状态、job_ids 数组、snake_case")
    void batchDtoSerializesCreateResponseShape() throws Exception {
        PredictionBatchDto dto = PredictionBatchDto.builder()
                .batchId(1L)
                .roundId(10L)
                .status("PENDING")
                .questionCount(2)
                .jobIds(List.of("a7c1", "b8d2"))
                .build();
        JsonNode expected = MAPPER.readTree("""
                {
                  "batch_id": 1,
                  "round_id": 10,
                  "status": "PENDING",
                  "question_count": 2,
                  "job_ids": ["a7c1", "b8d2"]
                }
                """);
        JsonNode actual = MAPPER.readTree(MAPPER.writeValueAsString(dto));
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.has("job_ids")).isTrue();
        assertThat(actual.has("jobIds")).isFalse();
    }

    @Test
    @DisplayName("批次详情与任务详情时间字段序列化为带 Z 的 Instant")
    void detailAndJobDtosSerializeInstantsWithZ() throws Exception {
        Instant cutoff = Instant.parse("2026-08-28T10:00:00Z");
        Instant created = Instant.parse("2026-08-28T10:01:00Z");
        Instant updated = Instant.parse("2026-08-28T10:02:00Z");
        Instant completed = Instant.parse("2026-08-28T10:03:20Z");

        PredictionBatchDetailDto detail = PredictionBatchDetailDto.builder()
                .batchId(1L)
                .roundId(10L)
                .batchNo(1)
                .status("PENDING")
                .questionCount(1)
                .dataCutoff(cutoff)
                .createdAt(created)
                .updatedAt(updated)
                .statusCounts(PredictionJobStatusCountsDto.builder()
                        .pendingCount(1)
                        .runningCount(0)
                        .retryingCount(0)
                        .succeededCount(0)
                        .failedCount(0)
                        .deadLetterCount(0)
                        .unknownCount(0)
                        .build())
                .build();
        JsonNode detailNode = MAPPER.readTree(MAPPER.writeValueAsString(detail));
        assertThat(detailNode.get("data_cutoff").asText()).isEqualTo("2026-08-28T10:00:00Z");
        assertThat(detailNode.get("created_at").asText()).isEqualTo("2026-08-28T10:01:00Z");
        assertThat(detailNode.get("updated_at").asText()).isEqualTo("2026-08-28T10:02:00Z");
        assertThat(detailNode.get("status_counts").get("pending_count").asInt()).isEqualTo(1);
        assertThat(detailNode.get("status_counts").get("failed_count").asInt()).isEqualTo(0);
        assertThat(detailNode.has("message_id")).isFalse();
        assertThat(detailNode.has("id")).isFalse();

        PredictionJobDto job = PredictionJobDto.builder()
                .predictionJobId("a7c1")
                .batchId(1L)
                .questionId(1L)
                .questionSnapshotId(12L)
                .status("SUCCEEDED")
                .retryCount(0)
                .maxRetries(3)
                .workerNode("home-gpu-01")
                .dataCutoff(cutoff)
                .featureVersion("feature-v1")
                .modelVersion("qwen3-8b-lora-v1")
                .promptVersion("f1-race-v1")
                .lockedAt(null)
                .completedAt(completed)
                .createdAt(created)
                .updatedAt(updated)
                .build();
        JsonNode jobNode = MAPPER.readTree(MAPPER.writeValueAsString(job));
        assertThat(jobNode.get("prediction_job_id").asText()).isEqualTo("a7c1");
        assertThat(jobNode.get("data_cutoff").asText()).isEqualTo("2026-08-28T10:00:00Z");
        assertThat(jobNode.get("completed_at").asText()).isEqualTo("2026-08-28T10:03:20Z");
        assertThat(jobNode.get("locked_at").isNull()).isTrue();
        assertThat(jobNode.has("message_id")).isFalse();
        assertThat(jobNode.has("last_error")).isFalse();
        assertThat(jobNode.has("id")).isFalse();
    }

    @Test
    @DisplayName("任务 outcome 仅序列化白名单字段并保留空详情")
    void outcomeDtoSerializesOnlyPublicFields() throws Exception {
        PredictionJobOutcomeDto pending = PredictionJobOutcomeDto.builder()
                .predictionJobId("job-1").status("RUNNING").build();
        JsonNode pendingNode = MAPPER.readTree(MAPPER.writeValueAsString(pending));
        assertThat(pendingNode.get("status").asText()).isEqualTo("RUNNING");
        assertThat(pendingNode.get("result").isNull()).isTrue();
        assertThat(pendingNode.get("failure").isNull()).isTrue();
        assertThat(pendingNode.has("predictionJobId")).isFalse();
        assertThat(pendingNode.has("id")).isFalse();

        PredictionJobOutcomeDto succeeded = PredictionJobOutcomeDto.builder()
                .predictionJobId("job-2").status("SUCCEEDED")
                .result(PredictionJobOutcomeDto.Result.builder()
                        .selectedOptions(List.of(PredictionJobOutcomeDto.SelectedOption.builder()
                                .optionId(4).position(1).build()))
                        .confidence(0.82).build())
                .build();
        JsonNode resultNode = MAPPER.readTree(MAPPER.writeValueAsString(succeeded));
        assertThat(resultNode.get("result").get("confidence").asDouble()).isEqualTo(0.82);
        assertThat(resultNode.get("result").get("selected_options").get(0).get("option_id").asInt())
                .isEqualTo(4);
        for (String sensitive : List.of("reasoning_summary", "evidence", "source_url", "source_type",
                "source_name", "document_id", "chunk_id", "model_version", "prompt_version",
                "feature_version", "embedding_version", "retriever_version", "source_data_cutoff", "generated_at")) {
            assertThat(resultNode.get("result").has(sensitive)).as(sensitive).isFalse();
        }

        PredictionJobOutcomeDto failed = PredictionJobOutcomeDto.builder()
                .predictionJobId("job-3").status("FAILED")
                .failure(PredictionJobOutcomeDto.Failure.builder()
                        .failureCode("MODEL_TIMEOUT").summary("Model timed out").build())
                .build();
        JsonNode failureNode = MAPPER.readTree(MAPPER.writeValueAsString(failed));
        assertThat(failureNode.get("failure").get("failure_code").asText()).isEqualTo("MODEL_TIMEOUT");
        assertThat(failureNode.get("failure").get("summary").asText()).isEqualTo("Model timed out");
        for (String sensitive : List.of("attempt", "generated_at", "model_version", "prompt_version",
                "feature_version", "embedding_version", "retriever_version", "source_data_cutoff",
                "message_id", "trace_id", "stack_trace")) {
            assertThat(failureNode.get("failure").has(sensitive)).as(sensitive).isFalse();
        }
    }

    @Test
    @DisplayName("默认列表在无参构造和 builder 路径下序列化为 [] 而不是 null")
    void defaultListsSerializeAsEmptyArrayNotNull() throws Exception {
        List<Object> samples = List.of(
                new PredictionBatchDto(),
                PredictionBatchDto.builder().build(),
                new PredictionJobPageDto(),
                PredictionJobPageDto.builder().build()
        );
        for (Object dto : samples) {
            JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(dto));
            String listField = dto instanceof PredictionBatchDto ? "job_ids" : "items";
            JsonNode listNode = node.get(listField);
            String label = dto.getClass().getSimpleName() + "." + listField;
            assertNotNull(listNode, label + " 不应为 null");
            assertTrue(listNode.isArray(), label + " 必须是数组");
            assertEquals(0, listNode.size(), label + " 应为空数组");
        }
    }
}
