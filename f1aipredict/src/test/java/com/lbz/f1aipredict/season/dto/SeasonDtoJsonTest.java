package com.lbz.f1aipredict.season.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Todo 1：season 查询 DTO / 分页 DTO 的 JSON 与反射契约。
 * <p>
 * 约定缝：公有 JSON 序列化结果 + 声明字段反射。不启动 Spring、不连库。
 * 覆盖：REST DTO snake_case 与查询对象 camelCase {@link JsonProperty}、精确字段集合/类型、
 * {@link LocalDate}/{@link Instant} 序列化形状、列表默认空数组、禁止审计/预测/rawJson 字段。
 */
class SeasonDtoJsonTest {

    /** 本任务锁定的 6 个 season 查询 DTO / 查询对象。 */
    private static final List<Class<?>> DTO_CLASSES = List.of(
            SeasonDto.class,
            RoundDto.class,
            MeetingSessionDto.class,
            CurrentRoundDto.class,
            SeasonQuery.class,
            SeasonPageDto.class
    );

    /**
     * Boot 3.5.16 / Jackson 2：纯单元测试必须显式注册 {@link JavaTimeModule}
     * 并关闭时间戳序列化，LocalDate 才是 {@code 2026-03-06}，Instant 才带 Z。
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
    @DisplayName("每个 DTO 实例字段都有与字段名一致的显式 @JsonProperty")
    void everyDtoFieldHasExplicitMatchingJsonProperty() {
        Map<Class<?>, List<String>> violationsByName = new LinkedHashMap<>();
        for (Class<?> dtoClass : DTO_CLASSES) {
            List<String> violations = jsonPropertyViolations(dtoClass);
            if (!violations.isEmpty()) {
                violationsByName.put(dtoClass, violations);
            }
        }
        assertThat(violationsByName)
                .as("所有 season DTO 字段都必须有与字段名一致的显式 @JsonProperty")
                .isEmpty();
    }

    @Test
    @DisplayName("DTO 不得暴露 rawJson、审计时间或预测/评分字段")
    void dtoMustNotExposeAuditPredictionOrRawJsonFields() {
        String[] forbidden = {
                "rawJson", "createdAt", "updatedAt",
                "predictionDeadline", "canCreatePrediction",
                "predictionLocked", "scoringCompleted"
        };
        Map<Class<?>, List<String>> violations = new LinkedHashMap<>();
        for (Class<?> dtoClass : DTO_CLASSES) {
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
        assertThat(violations).as("season DTO 不得暴露审计/预测/rawJson 字段").isEmpty();
    }

    @Test
    @DisplayName("SeasonDto 字段集合与类型精确匹配计划契约")
    void seasonDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(SeasonDto.class, "id", "year", "name", "status", "startDate", "endDate");
        assertFieldType(SeasonDto.class, "id", Long.class);
        assertFieldType(SeasonDto.class, "year", Integer.class);
        assertFieldType(SeasonDto.class, "name", String.class);
        assertFieldType(SeasonDto.class, "status", String.class);
        assertFieldType(SeasonDto.class, "startDate", LocalDate.class);
        assertFieldType(SeasonDto.class, "endDate", LocalDate.class);
    }

    @Test
    @DisplayName("RoundDto 字段集合与类型精确匹配计划契约")
    void roundDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(RoundDto.class,
                "id", "seasonId", "roundNumber", "grandPrixName", "officialName",
                "circuitName", "country", "locality", "startDate", "endDate", "status");
        assertFieldType(RoundDto.class, "id", Long.class);
        assertFieldType(RoundDto.class, "seasonId", Long.class);
        assertFieldType(RoundDto.class, "roundNumber", Integer.class);
        assertFieldType(RoundDto.class, "grandPrixName", String.class);
        assertFieldType(RoundDto.class, "startDate", LocalDate.class);
        assertFieldType(RoundDto.class, "endDate", LocalDate.class);
        assertFieldType(RoundDto.class, "status", String.class);
    }

    @Test
    @DisplayName("MeetingSessionDto 字段集合与类型精确匹配计划契约")
    void meetingSessionDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(MeetingSessionDto.class,
                "id", "roundId", "meetingKey", "sessionKey", "sessionName",
                "sessionType", "gamedayId", "startDateUtc", "endDateUtc", "status");
        assertFieldType(MeetingSessionDto.class, "id", Long.class);
        assertFieldType(MeetingSessionDto.class, "roundId", Long.class);
        assertFieldType(MeetingSessionDto.class, "meetingKey", Integer.class);
        assertFieldType(MeetingSessionDto.class, "sessionKey", Integer.class);
        assertFieldType(MeetingSessionDto.class, "gamedayId", Integer.class);
        assertFieldType(MeetingSessionDto.class, "startDateUtc", Instant.class);
        assertFieldType(MeetingSessionDto.class, "endDateUtc", Instant.class);
        assertFieldType(MeetingSessionDto.class, "status", String.class);
    }

    @Test
    @DisplayName("CurrentRoundDto 字段集合与类型精确匹配计划契约")
    void currentRoundDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(CurrentRoundDto.class, "round", "season", "sessions", "gamedayId");
        assertFieldType(CurrentRoundDto.class, "round", RoundDto.class);
        assertFieldType(CurrentRoundDto.class, "season", SeasonDto.class);
        assertFieldType(CurrentRoundDto.class, "sessions", List.class);
        assertFieldType(CurrentRoundDto.class, "gamedayId", Integer.class);
    }

    @Test
    @DisplayName("SeasonQuery 字段集合与类型精确匹配计划契约")
    void seasonQueryFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(SeasonQuery.class, "status", "page", "size");
        assertFieldType(SeasonQuery.class, "status", String.class);
        assertFieldType(SeasonQuery.class, "page", Integer.class);
        assertFieldType(SeasonQuery.class, "size", Integer.class);
    }

    @Test
    @DisplayName("SeasonPageDto 字段集合与类型精确匹配计划契约")
    void seasonPageDtoFieldNamesAndTypes() throws Exception {
        assertExactFieldNames(SeasonPageDto.class, "items", "page", "size", "total");
        assertFieldType(SeasonPageDto.class, "items", List.class);
        assertFieldType(SeasonPageDto.class, "page", Integer.class);
        assertFieldType(SeasonPageDto.class, "size", Integer.class);
        assertFieldType(SeasonPageDto.class, "total", Long.class);
    }

    @Test
    @DisplayName("LocalDate 序列化为 2026-03-06 且 JSON 键为 snake_case")
    void seasonDtoSerializesLocalDateAsIsoDate() throws Exception {
        SeasonDto dto = SeasonDto.builder()
                .id(1L)
                .year(2026)
                .name("2026 FIA Formula One World Championship")
                .status("IN_PROGRESS")
                .startDate(LocalDate.of(2026, 3, 6))
                .endDate(LocalDate.of(2026, 12, 6))
                .build();
        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(dto));
        assertThat(node.get("start_date").asText()).isEqualTo("2026-03-06");
        assertThat(node.get("end_date").asText()).isEqualTo("2026-12-06");
        assertThat(node.has("startDate")).isFalse();
        assertThat(node.has("endDate")).isFalse();
    }

    @Test
    @DisplayName("Instant 序列化为带 Z 的 ISO-8601 字符串")
    void meetingSessionDtoSerializesInstantWithZ() throws Exception {
        MeetingSessionDto dto = MeetingSessionDto.builder()
                .id(9L)
                .roundId(3L)
                .meetingKey(1254)
                .sessionKey(9876)
                .sessionName("Race")
                .sessionType("Race")
                .gamedayId(13)
                .startDateUtc(Instant.parse("2026-03-06T05:00:00Z"))
                .endDateUtc(Instant.parse("2026-03-06T07:00:00Z"))
                .status("SCHEDULED")
                .build();
        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(dto));
        assertThat(node.get("start_date_utc").asText()).isEqualTo("2026-03-06T05:00:00Z");
        assertThat(node.get("end_date_utc").asText()).isEqualTo("2026-03-06T07:00:00Z");
        assertThat(node.has("startDateUtc")).isFalse();
    }

    @Test
    @DisplayName("CurrentRoundDto.sessions 在无参构造和 builder 默认路径下为非 null 空列表")
    void currentRoundSessionsDefaultToEmptyListOnNoArgsAndBuilder() {
        assertThat(new CurrentRoundDto().getSessions()).isNotNull().isEmpty();
        assertThat(CurrentRoundDto.builder().build().getSessions()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("SeasonPageDto.items 在无参构造和 builder 默认路径下为非 null 空列表")
    void seasonPageItemsDefaultToEmptyListOnNoArgsAndBuilder() {
        assertThat(new SeasonPageDto().getItems()).isNotNull().isEmpty();
        assertThat(SeasonPageDto.builder().build().getItems()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("默认列表序列化为 [] 而不是 null")
    void defaultListsSerializeAsEmptyArrayNotNull() throws Exception {
        List<Object> samples = List.of(
                new CurrentRoundDto(),
                CurrentRoundDto.builder().build(),
                new SeasonPageDto(),
                SeasonPageDto.builder().build()
        );
        for (Object dto : samples) {
            JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(dto));
            String listField = dto instanceof CurrentRoundDto ? "sessions" : "items";
            JsonNode listNode = node.get(listField);
            String label = dto.getClass().getSimpleName() + "." + listField;
            assertNotNull(listNode, label + " 不应为 null");
            assertTrue(listNode.isArray(), label + " 必须是数组");
            assertEquals(0, listNode.size(), label + " 应为空数组");
        }
    }
}
