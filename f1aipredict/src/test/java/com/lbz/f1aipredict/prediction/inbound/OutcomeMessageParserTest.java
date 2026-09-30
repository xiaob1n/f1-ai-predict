package com.lbz.f1aipredict.prediction.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.Failure;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutcomeMessageParserTest {

    private OutcomeMessageParser parser;
    private byte[] resultFixture;
    private byte[] failureFixture;

    @BeforeEach
    void setUp() throws IOException {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        parser = new OutcomeMessageParser(mapper);
        resultFixture = getClass().getResourceAsStream("/prediction_result_v2.json").readAllBytes();
        failureFixture = getClass().getResourceAsStream("/prediction_failure_v2.json").readAllBytes();
    }

    @Test
    void parsesPythonResultFixtureWithoutLosingUtcPrecisionOrNullRagVersions() {
        Result result = parser.parseResult(resultFixture, 262144);

        assertEquals("outcome-result-1", result.getMessageId());
        assertEquals(0.12345678901234568d, result.getConfidence());
        assertEquals(Instant.parse("2026-09-11T03:59:59.123456Z"),
                result.getEvidence().getFirst().getFirstSeenAt());
        assertEquals(null, result.getEmbeddingVersion());
        assertEquals(null, result.getRetrieverVersion());
    }

    @Test
    void parsesPythonFailureFixtureAndKeepsIndependentAttempt() {
        Failure failure = parser.parseFailure(failureFixture, 16384);

        assertEquals("outcome-failure-1", failure.getMessageId());
        assertEquals("INSUFFICIENT_DATA", failure.getFailureCode());
        assertEquals(3, failure.getAttempt());
        assertEquals(null, failure.getEmbeddingVersion());
    }

    @Test
    void sharedResultFixtureRoundTripsThroughJavaV2Dto() throws IOException {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Result result = parser.parseResult(resultFixture, 262144);

        // 按结构比较共享夹具，确保 Java 接收 DTO 反序列化后不会漏掉终态字段。
        assertEquals(mapper.readTree(resultFixture), mapper.readTree(mapper.writeValueAsBytes(result)));
        assertEquals(Instant.parse("2026-09-11T04:00:00Z"), result.getSourceDataCutoff());
        assertEquals(501L, result.getQuestionSnapshotId());
        assertTrue(result.getEvidence().getFirst().getEventTime() == null);
    }

    @Test
    void sharedFailureFixtureRoundTripsThroughJavaV2Dto() throws IOException {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Failure failure = parser.parseFailure(failureFixture, 16384);

        // 精确比较线上键名及显式 null，避免失败消息在 Java 边界被悄然改写。
        assertEquals(mapper.readTree(failureFixture), mapper.readTree(mapper.writeValueAsBytes(failure)));
        assertEquals(Instant.parse("2026-09-11T04:00:00Z"), failure.getSourceDataCutoff());
        assertEquals(501L, failure.getQuestionSnapshotId());
        assertEquals(null, failure.getRetrieverVersion());
    }

    @Test
    void rejectsEvidenceOneMicrosecondAfterFrozenCutoff() {
        byte[] cutoff = replace(resultFixture, "2026-09-11T04:00:00Z", "2026-09-11T04:00:00.123456Z");
        byte[] lateEvidence = replace(cutoff, "2026-09-11T03:59:59.123456Z", "2026-09-11T04:00:00.123457Z");
        assertThrows(OutcomePoisonMessageException.class, () -> parser.parseResult(lateEvidence, 262144));
    }

    @Test
    void rejectsUnsupportedSchemaUnknownAndMissingFields() {
        assertThrows(OutcomePoisonMessageException.class, () -> parser.parseResult(
                replace(resultFixture, "\"schemaVersion\": \"2\"", "\"schemaVersion\": \"1\""), 262144));
        assertThrows(OutcomePoisonMessageException.class, () -> parser.parseResult(
                replace(resultFixture, "\"schemaVersion\": \"2\"", "\"schemaVersion\": \"2\", \"extra\": 1"), 262144));
        assertThrows(OutcomePoisonMessageException.class, () -> parser.parseFailure(
                replace(failureFixture, "  \"attempt\": 3,\n", ""), 16384));
    }

    @Test
    void rejectsInvalidFailureCodeAndUnsafeSummary() {
        assertThrows(OutcomePoisonMessageException.class, () -> parser.parseFailure(
                replace(failureFixture, "INSUFFICIENT_DATA", "UNRECOGNIZED"), 16384));
        assertThrows(OutcomePoisonMessageException.class, () -> parser.parseFailure(
                replace(failureFixture, "Insufficient data for prediction", "https://private.example/path"), 16384));
    }

    @Test
    void rejectsOversizedPayloadAndNonFiniteConfidence() {
        assertThrows(OutcomePoisonMessageException.class, () -> parser.parseResult(resultFixture, 64));
        assertThrows(OutcomePoisonMessageException.class, () -> parser.parseResult(
                replace(resultFixture, "0.12345678901234568", "NaN"), 262144));
    }

    @Test
    void canonicalDigestIsStableAndTracksPayloadChanges() {
        assertEquals(OutcomeMessageParser.sha256(resultFixture), OutcomeMessageParser.sha256(resultFixture.clone()));
        byte[] changed = replace(resultFixture, "outcome-result-1", "outcome-result-2");
        assertNotEquals(OutcomeMessageParser.sha256(resultFixture), OutcomeMessageParser.sha256(changed));
    }

    private static byte[] replace(byte[] source, String oldText, String newText) {
        return new String(source, StandardCharsets.UTF_8).replace(oldText, newText).getBytes(StandardCharsets.UTF_8);
    }
}
