"""预测失败 v2 契约：对外仅发送枚举代码和安全摘要。"""

from __future__ import annotations

from enum import StrEnum

from pydantic import Field, field_validator

from f1_predict.messaging.dto.base import F1PredictBaseModel, UtcDateTime
from f1_predict.messaging.dto.request_v2 import PredictionRequestV2


class PredictionFailureCode(StrEnum):
    """可安全暴露的失败类别；不包含底层异常详情。"""

    INVALID_REQUEST = "INVALID_REQUEST"
    UNSUPPORTED_QUESTION = "UNSUPPORTED_QUESTION"
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"
    VERSION_UNAVAILABLE = "VERSION_UNAVAILABLE"
    FEATURE_UNAVAILABLE = "FEATURE_UNAVAILABLE"
    RETRIEVAL_FAILED = "RETRIEVAL_FAILED"
    MODEL_TIMEOUT = "MODEL_TIMEOUT"
    MODEL_FAILED = "MODEL_FAILED"
    INVALID_MODEL_OUTPUT = "INVALID_MODEL_OUTPUT"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class PredictionFailureV2(F1PredictBaseModel):
    """Python 无法完成预测时发布的失败消息。"""

    schema_version: str = Field(alias="schemaVersion", pattern="^2$")
    message_id: str = Field(alias="messageId", min_length=1, max_length=128)
    prediction_job_id: str = Field(alias="predictionJobId", min_length=1, max_length=128)
    batch_id: int = Field(alias="batchId", gt=0)
    question_id: int = Field(alias="questionId", gt=0)
    question_snapshot_id: int = Field(alias="questionSnapshotId", gt=0)
    trace_id: str = Field(alias="traceId", min_length=1, max_length=128)
    failure_code: PredictionFailureCode = Field(alias="failureCode")
    summary: str = Field(alias="summary", min_length=1, max_length=256)
    attempt: int = Field(alias="attempt", ge=1)
    source_data_cutoff: UtcDateTime = Field(alias="sourceDataCutoff")
    generated_at: UtcDateTime = Field(alias="generatedAt")
    model_version: str = Field(alias="modelVersion", min_length=1, max_length=128)
    prompt_version: str = Field(alias="promptVersion", min_length=1, max_length=128)
    feature_version: str = Field(alias="featureVersion", min_length=1, max_length=128)
    embedding_version: str | None = Field(alias="embeddingVersion", max_length=128)
    retriever_version: str | None = Field(alias="retrieverVersion", max_length=128)

    @classmethod
    def from_request(
        cls,
        request: PredictionRequestV2,
        *,
        message_id: str,
        failure_code: PredictionFailureCode,
        summary: str,
        attempt: int,
        generated_at: UtcDateTime,
    ) -> PredictionFailureV2:
        """从请求复制稳定任务标识、数据截止点和版本信息。"""
        return cls(
            schemaVersion="2",
            messageId=message_id,
            predictionJobId=request.prediction_job_id,
            batchId=request.batch_id,
            questionId=request.question_id,
            questionSnapshotId=request.question_snapshot_id,
            traceId=request.trace_id,
            failureCode=failure_code,
            summary=summary,
            attempt=attempt,
            sourceDataCutoff=request.data_cutoff,
            generatedAt=generated_at,
            modelVersion=request.model_version,
            promptVersion=request.prompt_version,
            featureVersion=request.feature_version,
            embeddingVersion=request.embedding_version,
            retrieverVersion=request.retriever_version,
        )

    @field_validator("summary")
    @classmethod
    def validate_safe_summary(cls, value: str) -> str:
        """拒绝多行内容、URL 和疑似堆栈，避免泄露异常或凭据。"""
        lowered = value.lower()
        forbidden = ("http://", "https://", "traceback", " at ", "password", "token", "secret")
        if "\n" in value or "\r" in value or any(part in lowered for part in forbidden):
            raise ValueError("summary must be a safe single-line message")
        return value
