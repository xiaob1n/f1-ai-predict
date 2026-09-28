"""预测结果 v2 契约：仅包含可审计的预测输出与来源证据。"""

from __future__ import annotations

from pydantic import Field, model_validator

from f1_predict.messaging.dto.base import F1PredictBaseModel, UtcDateTime
from f1_predict.messaging.dto.request_v2 import PredictionRequestV2


class SelectedOptionV2(F1PredictBaseModel):
    """模型选中的题目选项。"""

    option_id: int = Field(alias="optionId", gt=0)
    position: int = Field(alias="position", ge=1)


class EvidenceSourceV2(F1PredictBaseModel):
    """结果引用的来源及其采集、事件时间。"""

    source_type: str = Field(alias="sourceType", min_length=1, max_length=64)
    source_name: str = Field(alias="sourceName", min_length=1, max_length=256)
    source_url: str | None = Field(alias="sourceUrl", max_length=2048)
    first_seen_at: UtcDateTime = Field(alias="firstSeenAt")
    event_time: UtcDateTime | None = Field(alias="eventTime")
    document_id: str = Field(alias="documentId", min_length=1, max_length=256)
    chunk_id: str | None = Field(alias="chunkId", max_length=256)


class PredictionResultV2(F1PredictBaseModel):
    """Python 推理完成后发布的不可变结果。"""

    schema_version: str = Field(alias="schemaVersion", pattern="^2$")
    message_id: str = Field(alias="messageId", min_length=1, max_length=128)
    prediction_job_id: str = Field(alias="predictionJobId", min_length=1, max_length=128)
    batch_id: int = Field(alias="batchId", gt=0)
    question_id: int = Field(alias="questionId", gt=0)
    question_snapshot_id: int = Field(alias="questionSnapshotId", gt=0)
    trace_id: str = Field(alias="traceId", min_length=1, max_length=128)
    selected_options: list[SelectedOptionV2] = Field(
        alias="selectedOptions", min_length=1, max_length=100
    )
    confidence: float = Field(alias="confidence", ge=0.0, le=1.0)
    reasoning_summary: str = Field(alias="reasoningSummary", min_length=1, max_length=2048)
    evidence: list[EvidenceSourceV2] = Field(alias="evidence", max_length=100)
    source_data_cutoff: UtcDateTime = Field(alias="sourceDataCutoff")
    generated_at: UtcDateTime = Field(alias="generatedAt")
    model_version: str = Field(alias="modelVersion", min_length=1, max_length=128)
    prompt_version: str = Field(alias="promptVersion", min_length=1, max_length=128)
    feature_version: str = Field(alias="featureVersion", min_length=1, max_length=128)
    embedding_version: str | None = Field(alias="embeddingVersion", max_length=128)
    retriever_version: str | None = Field(alias="retrieverVersion", max_length=128)

    @model_validator(mode="after")
    def validate_evidence_cutoff(self) -> PredictionResultV2:
        """确保输出证据在请求的数据截止点前已可用。"""
        for source in self.evidence:
            if source.first_seen_at > self.source_data_cutoff:
                raise ValueError("evidence firstSeenAt exceeds sourceDataCutoff")
            if source.event_time is not None and source.event_time > self.source_data_cutoff:
                raise ValueError("evidence eventTime exceeds sourceDataCutoff")
        return self

    @classmethod
    def from_request(
        cls,
        request: PredictionRequestV2,
        *,
        message_id: str,
        selected_options: list[SelectedOptionV2],
        confidence: float,
        reasoning_summary: str,
        evidence: list[EvidenceSourceV2],
        generated_at: UtcDateTime,
    ) -> PredictionResultV2:
        """从请求复制不可变的任务标识、数据截止点与版本信息。"""
        return cls(
            schemaVersion="2",
            messageId=message_id,
            predictionJobId=request.prediction_job_id,
            batchId=request.batch_id,
            questionId=request.question_id,
            questionSnapshotId=request.question_snapshot_id,
            traceId=request.trace_id,
            selectedOptions=selected_options,
            confidence=confidence,
            reasoningSummary=reasoning_summary,
            evidence=evidence,
            sourceDataCutoff=request.data_cutoff,
            generatedAt=generated_at,
            modelVersion=request.model_version,
            promptVersion=request.prompt_version,
            featureVersion=request.feature_version,
            embeddingVersion=request.embedding_version,
            retrieverVersion=request.retriever_version,
        )
