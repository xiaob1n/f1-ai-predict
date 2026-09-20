"""预测结果消息：选中选项、证据引用与模型原始输出留档。"""

from __future__ import annotations

from typing import Any

from pydantic import Field

from f1_predict.messaging.dto.base import (
    F1PredictBaseModel,
    PredictionMessageEnvelope,
    UtcDateTime,
)


class SelectedOption(F1PredictBaseModel):
    """模型选中的选项。``position`` 表示排序位置，单选恒为 1。"""

    option_id: int = Field(alias="optionId")
    position: int = Field(alias="position")


class EvidenceRef(F1PredictBaseModel):
    """检索证据引用。本阶段不做 publishedAt/eventTime 与 cutoff 的先后校验。"""

    source_type: str = Field(alias="sourceType")
    source_name: str = Field(alias="sourceName")
    source_url: str = Field(alias="sourceUrl")
    event_time: UtcDateTime = Field(alias="eventTime")
    published_at: UtcDateTime = Field(alias="publishedAt")
    document_id: str = Field(alias="documentId")
    chunk_id: str = Field(alias="chunkId")


class PredictionResultMessage(PredictionMessageEnvelope):
    """Python 推理成功后发布、Java 落库的预测结果。"""

    question_snapshot_id: int = Field(alias="questionSnapshotId")
    selected_options: list[SelectedOption] = Field(alias="selectedOptions")
    confidence: float = Field(alias="confidence", ge=0.0, le=1.0)
    reasoning_summary: str = Field(alias="reasoningSummary")
    evidence: list[EvidenceRef] = Field(alias="evidence")
    retrieved_chunk_ids: list[str] = Field(alias="retrievedChunkIds")
    source_data_cutoff: UtcDateTime = Field(alias="sourceDataCutoff")
    model_version: str = Field(alias="modelVersion")
    agent_version: str = Field(alias="agentVersion")
    prompt_version: str = Field(alias="promptVersion")
    feature_version: str = Field(alias="featureVersion")
    embedding_version: str = Field(alias="embeddingVersion")
    retriever_version: str = Field(alias="retrieverVersion")
    raw_agent_response: dict[str, Any] | None = Field(
        alias="rawAgentResponse",
        default=None,
    )
    generated_at: UtcDateTime = Field(alias="generatedAt")
