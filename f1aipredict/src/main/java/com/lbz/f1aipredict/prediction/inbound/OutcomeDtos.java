package com.lbz.f1aipredict.prediction.inbound;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** Python Worker v2 终态消息的严格接收 DTO。 */
public final class OutcomeDtos {

    private OutcomeDtos() { }

    @Getter
    @NoArgsConstructor
    public static class SelectedOption {
        @JsonProperty(value = "optionId", required = true)
        private Integer optionId;
        @JsonProperty(value = "position", required = true)
        private Integer position;
    }

    @Getter
    @NoArgsConstructor
    public static class Evidence {
        @JsonProperty(value = "sourceType", required = true)
        private String sourceType;
        @JsonProperty(value = "sourceName", required = true)
        private String sourceName;
        @JsonProperty(value = "sourceUrl", required = true)
        private String sourceUrl;
        @JsonProperty(value = "firstSeenAt", required = true)
        private Instant firstSeenAt;
        @JsonProperty(value = "eventTime", required = true)
        private Instant eventTime;
        @JsonProperty(value = "documentId", required = true)
        private String documentId;
        @JsonProperty(value = "chunkId", required = true)
        private String chunkId;
    }

    @Getter
    @NoArgsConstructor
    public static class Result {
        @JsonProperty(value = "schemaVersion", required = true)
        private String schemaVersion;
        @JsonProperty(value = "messageId", required = true)
        private String messageId;
        @JsonProperty(value = "predictionJobId", required = true)
        private String predictionJobId;
        @JsonProperty(value = "batchId", required = true)
        private Long batchId;
        @JsonProperty(value = "questionId", required = true)
        private Long questionId;
        @JsonProperty(value = "questionSnapshotId", required = true)
        private Long questionSnapshotId;
        @JsonProperty(value = "traceId", required = true)
        private String traceId;
        @JsonProperty(value = "selectedOptions", required = true)
        private List<SelectedOption> selectedOptions;
        @JsonProperty(value = "confidence", required = true)
        private Double confidence;
        @JsonProperty(value = "reasoningSummary", required = true)
        private String reasoningSummary;
        @JsonProperty(value = "evidence", required = true)
        private List<Evidence> evidence;
        @JsonProperty(value = "sourceDataCutoff", required = true)
        private Instant sourceDataCutoff;
        @JsonProperty(value = "generatedAt", required = true)
        private Instant generatedAt;
        @JsonProperty(value = "modelVersion", required = true)
        private String modelVersion;
        @JsonProperty(value = "promptVersion", required = true)
        private String promptVersion;
        @JsonProperty(value = "featureVersion", required = true)
        private String featureVersion;
        @JsonProperty(value = "embeddingVersion", required = true)
        private String embeddingVersion;
        @JsonProperty(value = "retrieverVersion", required = true)
        private String retrieverVersion;
    }

    @Getter
    @NoArgsConstructor
    public static class Failure {
        @JsonProperty(value = "schemaVersion", required = true)
        private String schemaVersion;
        @JsonProperty(value = "messageId", required = true)
        private String messageId;
        @JsonProperty(value = "predictionJobId", required = true)
        private String predictionJobId;
        @JsonProperty(value = "batchId", required = true)
        private Long batchId;
        @JsonProperty(value = "questionId", required = true)
        private Long questionId;
        @JsonProperty(value = "questionSnapshotId", required = true)
        private Long questionSnapshotId;
        @JsonProperty(value = "traceId", required = true)
        private String traceId;
        @JsonProperty(value = "failureCode", required = true)
        private String failureCode;
        @JsonProperty(value = "summary", required = true)
        private String summary;
        @JsonProperty(value = "attempt", required = true)
        private Integer attempt;
        @JsonProperty(value = "sourceDataCutoff", required = true)
        private Instant sourceDataCutoff;
        @JsonProperty(value = "generatedAt", required = true)
        private Instant generatedAt;
        @JsonProperty(value = "modelVersion", required = true)
        private String modelVersion;
        @JsonProperty(value = "promptVersion", required = true)
        private String promptVersion;
        @JsonProperty(value = "featureVersion", required = true)
        private String featureVersion;
        @JsonProperty(value = "embeddingVersion", required = true)
        private String embeddingVersion;
        @JsonProperty(value = "retrieverVersion", required = true)
        private String retrieverVersion;
    }
}
