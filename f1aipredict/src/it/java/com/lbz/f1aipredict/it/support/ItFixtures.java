package com.lbz.f1aipredict.it.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 集成测试数据夹具：每次调用插入一套互不相干的赛季/分站/Session/题目/快照/选项，
 * 唯一键（年份、比赛日、session_key）按库内当前最大值递增分配，因此测试只读写自己的行，
 * 从不 TRUNCATE 或删除共享表。时间统一以 UTC Instant 经 JDBC Timestamp 读写，
 * 与 MyBatis InstantTypeHandler 的转换路径一致。
 */
public final class ItFixtures {

    /** 每道题冻结快照中的选项 Id；单选题只会命中其中之一。 */
    public static final List<Integer> OPTION_IDS = List.of(101, 102, 103);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ItFixtures(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** 一套已插入的分站上下文。 */
    public record SeededRound(long seasonId, long roundId, int gamedayId, List<SeededQuestion> questions) {

        /** 题目主键列表，按插入顺序。 */
        public List<Long> questionIds() {
            return questions.stream().map(SeededQuestion::questionId).toList();
        }
    }

    /** 一道已插入的 OPEN 单选题及其最新快照。 */
    public record SeededQuestion(long questionId, int sourceQuestionId, long snapshotId) {
    }

    /** 批次内一条任务及其不可变请求载荷。 */
    public record OutboxRow(long jobId, String predictionJobId, long questionId, String payloadJson) {
    }

    /**
     * 插入一个可创建预测批次的分站：赛季、SCHEDULED 分站、单个 Session，以及若干 OPEN 单选题。
     * 每题快照 Config.ChoiceLimit=1，选项 Id 为 {@link #OPTION_IDS}。
     */
    public SeededRound seedRound(int questionCount) {
        if (questionCount < 1) {
            throw new IllegalArgumentException("questionCount must be positive");
        }
        long seasonId = insert("""
                INSERT INTO season (year, name, status)
                SELECT COALESCE(MAX(year), 2999) + 1, 'D-stage IT season', 'IN_PROGRESS' FROM season
                """);
        long roundId = insert("""
                INSERT INTO round (season_id, round_number, grand_prix_name, circuit_name, status)
                VALUES (?, 1, 'D-stage IT Grand Prix', 'D-stage IT Circuit', 'SCHEDULED')
                """, seasonId);
        long sessionId = insert("""
                INSERT INTO meeting_session (round_id, meeting_key, session_key, session_name, session_type,
                                             gameday_id, status)
                SELECT ?, COALESCE(MAX(session_key), 7000000) + 1, COALESCE(MAX(session_key), 7000000) + 1,
                       'Race', 'Race', COALESCE(MAX(gameday_id), 700000) + 1, 'SCHEDULED'
                FROM meeting_session
                """, roundId);
        Integer gamedayId = jdbc.queryForObject(
                "SELECT gameday_id FROM meeting_session WHERE id = ?", Integer.class, sessionId);
        List<SeededQuestion> questions = new ArrayList<>(questionCount);
        for (int index = 0; index < questionCount; index++) {
            questions.add(seedQuestion(roundId, gamedayId, index + 1));
        }
        return new SeededRound(seasonId, roundId, gamedayId, List.copyOf(questions));
    }

    private SeededQuestion seedQuestion(long roundId, int gamedayId, int sourceQuestionId) {
        String text = "D-stage IT question " + sourceQuestionId;
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("Id", sourceQuestionId);
        raw.put("No", sourceQuestionId);
        raw.put("Text", text);
        raw.putNull("SubText");
        raw.put("OptionTemplateId", 1);
        raw.putObject("Config").put("ChoiceLimit", 1);
        ArrayNode rawOptions = raw.putArray("Options");
        for (Integer optionId : OPTION_IDS) {
            rawOptions.addObject().put("Id", optionId).put("Text", "Option " + optionId);
        }
        String rawJson = raw.toString();
        String hash = sha256(rawJson);
        long questionId = insert("""
                INSERT INTO question (round_id, gameday_id, source_question_id, question_no, question_text,
                                      option_template_id, choice_limit, status, content_hash)
                VALUES (?, ?, ?, ?, ?, 1, 1, 'OPEN', ?)
                """, roundId, gamedayId, sourceQuestionId, sourceQuestionId, text, hash);
        long snapshotId = insert("""
                INSERT INTO question_snapshot (question_id, snapshot_no, content_hash, raw_json, snapshot_reason)
                VALUES (?, 1, ?, ?, 'INITIAL')
                """, questionId, hash, rawJson);
        for (int optionNo = 0; optionNo < OPTION_IDS.size(); optionNo++) {
            Integer optionId = OPTION_IDS.get(optionNo);
            jdbc.update("""
                    INSERT INTO question_option (snapshot_id, option_no, option_id, option_text, points)
                    VALUES (?, ?, ?, ?, 10)
                    """, snapshotId, optionNo, optionId, "Option " + optionId);
        }
        jdbc.update("UPDATE question SET latest_snapshot_id = ? WHERE id = ?", snapshotId, questionId);
        return new SeededQuestion(questionId, sourceQuestionId, snapshotId);
    }

    /**
     * 构造题目 Feed 中的答案载荷：值为选项 Id 时写 {@code [{"Id":n}]}，值为 null 时写 {@code "Answer": null}（撤销）。
     *
     * @param answersBySourceQuestionId 键为 Feed 题目 Id，保持插入顺序
     */
    public String answerFeedJson(Map<Integer, Integer> answersBySourceQuestionId) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode questions = root.putObject("Data").putObject("Value").putArray("Questions");
        answersBySourceQuestionId.forEach((sourceId, optionId) -> {
            ObjectNode question = questions.addObject();
            question.put("Id", sourceId);
            if (optionId == null) {
                question.putNull("Answer");
            } else {
                question.putArray("Answer").addObject().put("Id", optionId);
            }
        });
        return root.toString();
    }

    /** 题目顺序对应的答案映射，便于按 seedRound 的题目顺序给出正确选项。 */
    public static Map<Integer, Integer> answersInOrder(SeededRound round, Integer... optionIds) {
        if (optionIds.length != round.questions().size()) {
            throw new IllegalArgumentException("answer count must equal question count");
        }
        Map<Integer, Integer> answers = new LinkedHashMap<>();
        for (int index = 0; index < optionIds.length; index++) {
            answers.put(round.questions().get(index).sourceQuestionId(), optionIds[index]);
        }
        return answers;
    }

    /** 读取批次全部任务及其 outbox 请求载荷，按任务主键升序。 */
    public List<OutboxRow> outboxRows(long batchId) {
        return jdbc.query("""
                SELECT j.id, j.prediction_job_id, j.question_id, o.payload_json
                FROM prediction_job j
                JOIN prediction_request_outbox o ON o.prediction_job_id = j.prediction_job_id
                WHERE j.batch_id = ?
                ORDER BY j.id ASC
                """, (rs, rowNum) -> new OutboxRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)),
                batchId);
    }

    /**
     * 按冻结请求载荷构造与 Python Worker v2 RESULT 契约一致的消息字节，只选择一个选项。
     * 版本、截止与追踪字段逐字取自请求载荷，消息 ID 为新的随机值。
     */
    public byte[] resultMessage(String requestPayloadJson, int optionId) {
        try {
            JsonNode frozen = objectMapper.readTree(requestPayloadJson);
            ObjectNode message = objectMapper.createObjectNode();
            message.put("schemaVersion", "2");
            message.put("messageId", UUID.randomUUID().toString());
            message.put("predictionJobId", frozen.get("predictionJobId").asText());
            message.put("batchId", frozen.get("batchId").asLong());
            message.put("questionId", frozen.get("questionId").asLong());
            message.put("questionSnapshotId", frozen.get("questionSnapshotId").asLong());
            message.put("traceId", frozen.get("traceId").asText());
            message.putArray("selectedOptions").addObject().put("optionId", optionId).put("position", 1);
            message.put("confidence", 0.75);
            message.put("reasoningSummary", "D-stage IT stand-in result");
            message.putArray("evidence");
            message.put("sourceDataCutoff", frozen.get("dataCutoff").asText());
            message.put("generatedAt", Instant.now().toString());
            for (String field : List.of("modelVersion", "promptVersion", "featureVersion",
                    "embeddingVersion", "retrieverVersion")) {
                message.set(field, frozen.get(field));
            }
            return objectMapper.writeValueAsBytes(message);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法构造替身结果消息", error);
        }
    }

    /**
     * 时间快进夹具：把尚未锁定批次的业务截止设为本批次已受理结果中最晚接收时间（向上取整到毫秒）。
     * <p>
     * 受理逻辑不读取业务截止，因此效果等同于“批次创建时就以该时刻为截止”：已受理结果均不晚于截止，
     * 之后接收的结果严格晚于截止。这样无需依赖固定秒数的墙钟竞态。
     *
     * @return 新的业务截止
     */
    public Instant fastForwardDeadlineToLatestReceipt(long batchId) {
        Instant latest = jdbc.queryForObject("""
                SELECT MAX(r.received_at) FROM prediction_outcome_receipt r
                JOIN prediction_job j ON j.id = r.job_id WHERE j.batch_id = ?
                """, (rs, rowNum) -> {
                    Timestamp value = rs.getTimestamp(1);
                    return value == null ? null : value.toInstant();
                }, batchId);
        if (latest == null) {
            throw new IllegalStateException("批次尚无已受理结果，无法快进截止: " + batchId);
        }
        Instant truncated = latest.truncatedTo(ChronoUnit.MILLIS);
        Instant deadline = truncated.equals(latest) ? truncated : truncated.plusMillis(1);
        int updated = jdbc.update(
                "UPDATE prediction_batch SET prediction_deadline = ? WHERE id = ? AND locked_at IS NULL",
                Timestamp.from(deadline), batchId);
        if (updated != 1) {
            throw new IllegalStateException("快进截止失败（批次不存在或已锁定）: " + batchId);
        }
        return deadline;
    }

    /**
     * 有界等待服务端时钟（毫秒精度）严格越过给定时刻，返回可用作锁定时间的毫秒时刻。
     * 超过上限即失败，避免无限等待。
     */
    public static Instant awaitClockStrictlyAfter(Instant instant, Duration maxWait) {
        long deadlineNanos = System.nanoTime() + maxWait.toNanos();
        while (true) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            if (now.isAfter(instant)) {
                return now;
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("时钟在 " + maxWait + " 内未越过 " + instant);
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待时钟时被中断", interrupted);
            }
        }
    }

    /** 执行计数查询。 */
    public long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    /** 读取单个时间列，NULL 返回 null。 */
    public Instant instant(String sql, Object... args) {
        return jdbc.queryForObject(sql, (rs, rowNum) -> {
            Timestamp value = rs.getTimestamp(1);
            return value == null ? null : value.toInstant();
        }, args);
    }

    /**
     * 把 {@link #rows} 读出的时间列转换为 Instant。Connector/J 对 DATETIME 的 getObject 返回 LocalDateTime，
     * 其值是库内 UTC 墙钟（连接时区固定为 UTC），因此按 UTC 解释；NULL 返回 null。
     */
    public static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw new IllegalArgumentException("不支持的时间列类型: " + value.getClass().getName());
    }

    /** 以列名为键读取多行，供整行快照对比。 */
    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入未返回主键");
        }
        return key.longValue();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
