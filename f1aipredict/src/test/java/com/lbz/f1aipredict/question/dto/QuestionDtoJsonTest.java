package com.lbz.f1aipredict.question.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lbz.f1aipredict.common.dto.ApiErrorResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Todo 3：REST DTO 与查询契约测试。
 * <p>
 * 覆盖以下契约（详见 .omo/plans/question-option-interface.md Todo 3）：
 * 1) REST DTO 字段通过显式 {@link JsonProperty} 映射为 snake_case，查询 DTO 保持 camelCase；
 * 2) 任何 DTO 都不得出现 rawJson 字段；
 * 3) options 一律为 List，未附带选项时序列化为 [] 而非 null；
 * 4) questionType 默认 UNKNOWN、includeOptions 默认 true；
 * 5) 序列化 JSON 与 java-api-design.md 2.1 节形状一致（仅 questionType 调整为 UNKNOWN）。
 * <p>
 * 全部通过公有行为（JSON 序列化结果）与反射策略断言完成，不依赖数据库与 Spring 上下文。
 */
class QuestionDtoJsonTest {

    /** 受检的全部 6 个 DTO 类（question.dto 5 个 + common.dto 1 个） */
    private static final List<Class<?>> DTO_CLASSES = List.of(
            QuestionQuery.class,
            QuestionOptionDto.class,
            QuestionDto.class,
            QuestionDetailDto.class,
            QuestionSnapshotDto.class,
            ApiErrorResponse.class
    );

    /**
     * 测试用 ObjectMapper。
     * <p>
     * Spring Boot 3.5.16 使用 Jackson 2。纯单元测试直接 {@code new ObjectMapper()}
     * 不会自动注册 {@link JavaTimeModule}，必须显式注册并关闭时间戳序列化，
     * Instant 才会输出 ISO-8601 文本（"2024-03-01T10:00:00Z"）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 反射扫描：返回该 DTO 上「缺失 @JsonProperty 或注解值与字段名不一致」的字段清单。
     * 供本类真实断言与 red-probe 测试共用，保证策略逻辑唯一。
     *
     * @param dtoClass 目标 DTO 类
     * @return 违规字段名列表（空表示完全合规）
     */
    static List<String> jsonPropertyViolations(Class<?> dtoClass) {
        List<String> violations = new ArrayList<>();
        for (Field field : dtoClass.getDeclaredFields()) {
            JsonProperty ann = field.getAnnotation(JsonProperty.class);
            if (ann == null) {
                violations.add(field.getName() + " (missing @JsonProperty)");
            } else if (!ann.value().equals(expectedJsonName(dtoClass, field.getName()))) {
                violations.add(field.getName() + " -> @" + ann.value() + " (expected @" + expectedJsonName(dtoClass, field.getName()) + ")");
            }
        }
        return violations;
    }

    /** 反射扫描：返回该 DTO 中「JsonProperty 值或字段名恰好为 rawJson」的违规字段清单。 */
    private static List<String> rawJsonViolations(Class<?> dtoClass) {
        List<String> violations = new ArrayList<>();
        for (Field field : dtoClass.getDeclaredFields()) {
            String jsonName = field.getName();
            JsonProperty ann = field.getAnnotation(JsonProperty.class);
            if (ann != null) {
                jsonName = ann.value();
            }
            if (jsonName.equals("rawJson") || jsonName.equals("raw_json")) {
                violations.add(field.getName());
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

    // ---------------------------------------------------------------------
    // 1. @JsonProperty 策略断言（接受标准：反射证明无字段缺失注解、无字段名不一致）
    // ---------------------------------------------------------------------

    @Test
    void everyDtoFieldHasExplicitMatchingJsonProperty() {
        // 逐个 DTO 检查，聚合所有违规项，避免一次只暴露一个问题
        Map<Class<?>, List<String>> violationsByName = new LinkedHashMap<>();
        for (Class<?> dtoClass : DTO_CLASSES) {
            List<String> violations = jsonPropertyViolations(dtoClass);
            if (!violations.isEmpty()) {
                violationsByName.put(dtoClass, violations);
            }
        }
        assertThat(violationsByName)
                .as("REST DTO 使用 snake_case 映射，QuestionQuery 保持 camelCase")
                .isEmpty();
    }

    /** 接受标准：任何 DTO 都不得包含 rawJson 字段（快照 DTO 仅允许 hasRawJson 布尔标记）。 */
    @Test
    void noDtoContainsRawJsonField() {
        Map<Class<?>, List<String>> violations = new LinkedHashMap<>();
        for (Class<?> dtoClass : DTO_CLASSES) {
            List<String> found = rawJsonViolations(dtoClass);
            if (!found.isEmpty()) {
                violations.put(dtoClass, found);
            }
        }
        assertThat(violations).as("DTO 集合中不得出现 rawJson JsonProperty 或字段名").isEmpty();
    }

    /** 序列化层面进一步证明：完整快照序列化输出不含 rawJson 键。 */
    @Test
    void serializedSnapshotJsonNeverContainsRawJsonKey() throws Exception {
        QuestionSnapshotDto snapshot = QuestionSnapshotDto.builder()
                .snapshotId(12L)
                .questionId(1L)
                .snapshotNo(3)
                .contentHash("abc123")
                .snapshotReason("initial")
                .createdAt(Instant.parse("2024-03-01T10:00:00Z"))
                .hasRawJson(true)
                .options(List.of())
                .build();
        String json = MAPPER.writeValueAsString(snapshot);
        JsonNode tree = MAPPER.readTree(json);
        // has_raw_json 本身存在于 JSON 中，但绝不能出现独立的 raw_json 键
        assertThat(tree.has("schema")).isFalse();
        assertThat(tree.has("has_raw_json")).isTrue();
        assertThat(tree.has("raw_json")).isFalse();
        assertThat(json).doesNotContain("\"raw_json\"");
    }

    // ---------------------------------------------------------------------
    // 2. 精确字段集合与类型（接受标准：类型/命名与计划契约一一对应）
    // ---------------------------------------------------------------------

    /** 断言某 DTO 声明的字段名集合与契约完全一致（不允许多字段、漏字段或改名）。 */
    private static void assertExactFieldNames(Class<?> dtoClass, String... expectedNames) {
        List<String> actualNames = new ArrayList<>();
        for (Field f : dtoClass.getDeclaredFields()) {
            actualNames.add(f.getName());
        }
        assertThat(actualNames)
                .as("%s 字段集合", dtoClass.getSimpleName())
                .containsExactlyInAnyOrder(expectedNames);
    }

    /** 断言某 DTO 指定字段的 Java 类型与契约一致。 */
    private static void assertFieldType(Class<?> dtoClass, String fieldName, Class<?> expectedType) throws Exception {
        Field field = dtoClass.getDeclaredField(fieldName);
        assertThat(field.getType())
                .as("%s.%s 的类型", dtoClass.getSimpleName(), fieldName)
                .isEqualTo(expectedType);
    }

    @Test
    void queryDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(QuestionQuery.class, "status", "gamedayId", "includeOptions", "snapshotId");
        assertFieldType(QuestionQuery.class, "status", String.class);
        assertFieldType(QuestionQuery.class, "gamedayId", Integer.class);
        assertFieldType(QuestionQuery.class, "includeOptions", Boolean.class);
        assertFieldType(QuestionQuery.class, "snapshotId", Long.class);
    }

    @Test
    void optionDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(QuestionOptionDto.class, "optionId", "optionNo", "optionText", "points", "chance");
        assertFieldType(QuestionOptionDto.class, "optionId", Integer.class);
        assertFieldType(QuestionOptionDto.class, "optionNo", Integer.class);
        assertFieldType(QuestionOptionDto.class, "optionText", String.class);
        assertFieldType(QuestionOptionDto.class, "points", Integer.class);
        assertFieldType(QuestionOptionDto.class, "chance", BigDecimal.class);
    }

    @Test
    void questionDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(QuestionDto.class,
                "questionId", "gamedayId", "sourceQuestionId", "questionNo", "questionText",
                "subText", "questionType", "optionTemplateId", "choiceLimit", "status",
                "latestSnapshotId", "options");
        assertFieldType(QuestionDto.class, "questionId", Long.class);
        assertFieldType(QuestionDto.class, "questionType", String.class);
        assertFieldType(QuestionDto.class, "options", List.class);
    }

    @Test
    void detailDtoFieldNamesMatchQuestionDto() throws Exception {
        assertExactFieldNames(QuestionDetailDto.class,
                "questionId", "gamedayId", "sourceQuestionId", "questionNo", "questionText",
                "subText", "questionType", "optionTemplateId", "choiceLimit", "status",
                "latestSnapshotId", "options");
        assertFieldType(QuestionDetailDto.class, "options", List.class);
    }

    @Test
    void snapshotDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(QuestionSnapshotDto.class,
                "snapshotId", "questionId", "snapshotNo", "contentHash", "snapshotReason",
                "createdAt", "hasRawJson", "options");
        assertFieldType(QuestionSnapshotDto.class, "snapshotId", Long.class);
        assertFieldType(QuestionSnapshotDto.class, "createdAt", Instant.class);
        assertFieldType(QuestionSnapshotDto.class, "hasRawJson", Boolean.class);
        assertFieldType(QuestionSnapshotDto.class, "options", List.class);
    }

    @Test
    void errorResponseDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(ApiErrorResponse.class, "code", "message");
        assertFieldType(ApiErrorResponse.class, "code", String.class);
        assertFieldType(ApiErrorResponse.class, "message", String.class);
    }

    // ---------------------------------------------------------------------
    // 3. 序列化形状：与 java-api-design.md 2.1 节一致，仅 questionType 调整为 UNKNOWN
    // ---------------------------------------------------------------------

    /** 构建与 2.1 示例完全相同的 QuestionDto（questionType 契约值为 UNKNOWN）。 */
    private static QuestionDto sampleQuestion() {
        return QuestionDto.builder()
                .questionId(1L)
                .gamedayId(100)
                .sourceQuestionId(476)
                .questionNo(1)
                .questionText("Who will qualify on pole?")
                .subText(null)
                .optionTemplateId(1)
                .choiceLimit(1)
                .status("OPEN")
                .latestSnapshotId(12L)
                .options(List.of(QuestionOptionDto.builder()
                        .optionId(117)
                        .optionNo(0)
                        .optionText("Driver A")
                        .points(10)
                        .chance(new BigDecimal("0.73"))
                        .build()))
                .build();
    }

    /** 序列化 QuestionDto 并断言与 2.1 期望 JSON 树完全一致（忽略键顺序）。 */
    @Test
    void questionDtoSerializesTo2_1ShapeWithUnknownType() throws Exception {
        JsonNode expected = MAPPER.readTree("""
                {
                  "question_id": 1,
                  "gameday_id": 100,
                  "source_question_id": 476,
                  "question_no": 1,
                  "question_text": "Who will qualify on pole?",
                  "sub_text": null,
                  "question_type": "UNKNOWN",
                  "option_template_id": 1,
                  "choice_limit": 1,
                  "status": "OPEN",
                  "latest_snapshot_id": 12,
                  "options": [
                    {
                      "option_id": 117,
                      "option_no": 0,
                      "option_text": "Driver A",
                      "points": 10,
                      "chance": 0.73
                    }
                  ]
                }
                """);
        JsonNode actual = MAPPER.readTree(MAPPER.writeValueAsString(sampleQuestion()));
        assertThat(actual).as("QuestionDto 序列化形状（2.1 节 + UNKNOWN）").isEqualTo(expected);
    }

    /** 序列化选项 DTO：验证 optionId/optionNo/points 为数值、chance 为小数、JSON 键严格 snake_case。 */
    @Test
    void optionDtoSerializesWithNumericScalars() throws Exception {
        QuestionOptionDto option = QuestionOptionDto.builder()
                .optionId(117)
                .optionNo(0)
                .optionText("Driver A")
                .points(10)
                .chance(new BigDecimal("0.730"))
                .build();
        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(option));
        assertThat(node.size()).isEqualTo(5);
        assertThat(node.get("option_id").isInt()).isTrue();
        assertThat(node.get("option_no").isInt()).isTrue();
        assertThat(node.get("points").isInt()).isTrue();
        assertThat(node.get("chance").isNumber()).isTrue();
        assertThat(node.get("chance").decimalValue()).isEqualByComparingTo(new BigDecimal("0.73"));
        assertThat(node.get("option_text").asText()).isEqualTo("Driver A");
    }

    /** 快照 DTO 序列化：createdAt 输出 ISO-8601 文本，键集合与契约一致，不含 rawJson。 */
    @Test
    void snapshotDtoSerializesIsoInstantAndExactKeys() throws Exception {
        QuestionSnapshotDto snapshot = QuestionSnapshotDto.builder()
                .snapshotId(12L)
                .questionId(1L)
                .snapshotNo(3)
                .contentHash("hash-1")
                .snapshotReason("initial")
                .createdAt(Instant.parse("2024-03-01T10:00:00Z"))
                .hasRawJson(true)
                .options(sampleQuestion().getOptions())
                .build();
        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(snapshot));
        // Jackson 2 没有 propertyNames()，用 fieldNames() 迭代器收集 JSON 键集合
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder(
                "snapshot_id", "question_id", "snapshot_no", "content_hash",
                "snapshot_reason", "created_at", "has_raw_json", "options");
        assertThat(node.get("created_at").asText()).isEqualTo("2024-03-01T10:00:00Z");
        assertThat(node.get("has_raw_json").asBoolean()).isTrue();
        assertThat(node.has("raw_json")).isFalse();
    }

    /** API 错误 DTO 序列化契约：精确 {"code","message"}。 */
    @Test
    void errorResponseSerializesCodeAndMessageOnly() throws Exception {
        ApiErrorResponse error = ApiErrorResponse.builder()
                .code("RESOURCE_NOT_FOUND")
                .message("Question not found: 42")
                .build();
        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(error));
        assertThat(node.size()).isEqualTo(2);
        assertThat(node.get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(node.get("message").asText()).isEqualTo("Question not found: 42");
    }

    // ---------------------------------------------------------------------
    // 4. options 列表语义：始终为 List，未附带选项时序列化为 [] 而非 null
    // ---------------------------------------------------------------------

    @Test
    void emptyOptionsSerializeAsEmptyArrayNotNull() throws Exception {
        // 无参构造 + 不显式设置 options + Builder 各走一遍，均须输出 []
        List<Object> samples = List.of(
                new QuestionDto(),
                QuestionDto.builder().build(),
                new QuestionDetailDto(),
                QuestionDetailDto.builder().build(),
                new QuestionSnapshotDto(),
                QuestionSnapshotDto.builder().build()
        );
        for (Object dto : samples) {
            JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(dto));
            JsonNode options = node.get("options");
            String label = dto.getClass().getSimpleName() + ".options";
            // 未附带选项时必须输出 ["options": []] 而非 null
            assertNotNull(options, label + " 不应为 null");
            assertTrue(options.isArray(), label + " 必须是数组");
            assertEquals(0, options.size(), label + " 应为空数组");
        }
    }

    @Test
    void optionsFieldIsListTypeWithAttachedOptions() throws Exception {
        QuestionDto question = QuestionDto.builder()
                .options(List.of(QuestionOptionDto.builder().optionId(1).build()))
                .build();
        assertThat(question.getOptions()).isInstanceOf(List.class);
        JsonNode options = MAPPER.readTree(MAPPER.writeValueAsString(question)).get("options");
        assertThat(options.isArray()).isTrue();
        assertThat(options.size()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // 5. 默认值契约：includeOptions 默认 true（显式 false 不被隐藏）、questionType 默认 UNKNOWN
    // ---------------------------------------------------------------------

    @Test
    void includeOptionsDefaultsTrueAcrossConstructionPaths() throws Exception {
        // 无参构造
        assertThat(new QuestionQuery().getIncludeOptions()).isTrue();
        // Builder 默认
        assertThat(QuestionQuery.builder().build().getIncludeOptions()).isTrue();
        // 显式 false 必须保留
        assertThat(QuestionQuery.builder().includeOptions(false).build().getIncludeOptions()).isFalse();
        // 显式 true/false 经 JSON 往返后必须原样保留（Jackson 2 对缺失字段采用 null-defaulting，
        // 「缺省即 true」属于控制层绑定职责而非 DTO 自身，见 Todo 6 的显式布尔默认处理）
        QuestionQuery roundTripTrue = MAPPER.readValue("{\"includeOptions\":true}", QuestionQuery.class);
        assertThat(roundTripTrue.getIncludeOptions()).isTrue();
        QuestionQuery roundTripFalse = MAPPER.readValue("{\"includeOptions\":false}", QuestionQuery.class);
        assertThat(roundTripFalse.getIncludeOptions()).isFalse();
    }

    @Test
    void includeOptionsTrueSerializesAndExplicitFalseIsSerialized() throws Exception {
        // 默认 true 出现在序列化结果中
        JsonNode defaultNode = MAPPER.readTree(
                MAPPER.writeValueAsString(QuestionQuery.builder().build()));
        assertThat(defaultNode.get("includeOptions").asBoolean()).isTrue();
        // 显式 false 不会被「默认 true 逻辑」覆盖掉
        JsonNode falseNode = MAPPER.readTree(
                MAPPER.writeValueAsString(QuestionQuery.builder().includeOptions(false).build()));
        assertThat(falseNode.get("includeOptions").asBoolean()).isFalse();
    }

    @Test
    void questionTypeDefaultsToUnknown() throws Exception {
        assertThat(new QuestionDto().getQuestionType()).isEqualTo("UNKNOWN");
        assertThat(QuestionDto.builder().build().getQuestionType()).isEqualTo("UNKNOWN");
        assertThat(new QuestionDetailDto().getQuestionType()).isEqualTo("UNKNOWN");
        assertThat(QuestionDetailDto.builder().build().getQuestionType()).isEqualTo("UNKNOWN");
        // 序列化输出同样为 UNKNOWN（与 2.1 示例的唯一差异点）
        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(sampleQuestion()))
                .get("question_type").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void questionTypeExplicitSingleSerializesWithoutChangingDefault() throws Exception {
        QuestionDto question = sampleQuestion();
        question.setQuestionType("SINGLE");
        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(question))
                .get("question_type").asText()).isEqualTo("SINGLE");
        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(
                QuestionDetailDto.builder().questionType("SINGLE").build()))
                .get("question_type").asText()).isEqualTo("SINGLE");
        assertThat(QuestionDto.builder().build().getQuestionType()).isEqualTo("UNKNOWN");
    }

    // ---------------------------------------------------------------------
    // 6. 空值安全性：所有可空字段（subText/snapshotReason 等）在 JSON 中显式 null，不抛异常
    // ---------------------------------------------------------------------

    @Test
    void nullableFieldsSerializeAsNullWithoutFailure() throws Exception {
        QuestionDto bare = new QuestionDto();
        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(bare));
        assertThat(node.has("sub_text")).isTrue();
        assertThat(node.get("sub_text").isNull()).isTrue();
        assertThat(node.has("status")).isTrue();
        assertThat(node.get("status").isNull()).isTrue();

        QuestionSnapshotDto snapshot = new QuestionSnapshotDto();
        JsonNode snapshotNode = MAPPER.readTree(MAPPER.writeValueAsString(snapshot));
        assertThat(snapshotNode.get("snapshot_reason").isNull()).isTrue();
        assertThat(snapshotNode.get("created_at").isNull()).isTrue();
    }
}