"""v2 结果与失败 DTO 的字段、序列化及安全边界测试。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from f1_predict.messaging.dto.failure_v2 import (
    PredictionFailureCode,
    PredictionFailureV2,
)
from f1_predict.messaging.dto.request_v2 import PredictionRequestV2
from f1_predict.messaging.dto.result_v2 import PredictionResultV2


@pytest.mark.parametrize(
    ("fixture_name", "model_type"),
    [
        ("prediction_result_v2.json", PredictionResultV2),
        ("prediction_failure_v2.json", PredictionFailureV2),
    ],
)
def test_shared_java_consumer_v2_fixture_roundtrip(
    fixture_name: str, model_type: type[PredictionResultV2 | PredictionFailureV2]
) -> None:
    """双端直接读取同一夹具，守护终态字段、UTC 时间及显式空值。"""
    fixture = (
        Path(__file__).resolve().parents[4]
        / "f1aipredict/src/test/resources"
        / fixture_name
    )
    payload = json.loads(fixture.read_text(encoding="utf-8"))
    model = model_type.model_validate(payload)

    assert model.model_dump(mode="json", by_alias=True) == payload
    assert json.loads(model.model_dump_json()) == payload
    assert model.source_data_cutoff.isoformat() == "2026-09-11T04:00:00+00:00"
    assert model.question_snapshot_id == 501
    assert model.embedding_version is None
    assert model.retriever_version is None
    if isinstance(model, PredictionResultV2):
        assert model.evidence[0].first_seen_at.isoformat() == "2026-09-11T03:59:59.123456+00:00"
        assert model.evidence[0].event_time is None
    else:
        assert model.failure_code is PredictionFailureCode.INSUFFICIENT_DATA
        assert model.attempt == 3


def test_result_v2_roundtrip_preserves_ids_evidence_and_nullable_rag_versions() -> None:
    payload = {
        "schemaVersion": "2",
        "messageId": "result-1",
        "predictionJobId": "job-1",
        "batchId": 2,
        "questionId": 9,
        "questionSnapshotId": 12,
        "traceId": "trace-1",
        "selectedOptions": [{"optionId": 4, "position": 1}],
        "confidence": 0.82,
        "reasoningSummary": "近期数据支持所选预测",
        "evidence": [
            {
                "sourceType": "OPENF1",
                "sourceName": "session result",
                "sourceUrl": None,
                "firstSeenAt": "2026-09-27T08:00:00Z",
                "eventTime": "2026-09-27T07:30:00Z",
                "documentId": "doc-1",
                "chunkId": None,
            }
        ],
        "sourceDataCutoff": "2026-09-27T08:00:00Z",
        "generatedAt": "2026-09-27T08:05:00Z",
        "modelVersion": "model-v2",
        "promptVersion": "prompt-v2",
        "featureVersion": "feature-v2",
        "embeddingVersion": None,
        "retrieverVersion": None,
    }
    model = PredictionResultV2.model_validate(payload)

    assert model.model_dump(mode="json") == payload
    assert model.evidence[0].first_seen_at.isoformat() == "2026-09-27T08:00:00+00:00"

    invalid = dict(payload)
    invalid["evidence"] = [
        {**payload["evidence"][0], "firstSeenAt": "2026-09-27T08:01:00Z"}
    ]
    with pytest.raises(ValidationError, match="firstSeenAt exceeds"):
        PredictionResultV2.model_validate(invalid)


def test_result_v2_rejects_wrong_version_and_invalid_confidence() -> None:
    payload = {
        "schemaVersion": "1",
        "messageId": "result-1",
        "predictionJobId": "job-1",
        "batchId": 2,
        "questionId": 9,
        "questionSnapshotId": 12,
        "traceId": "trace-1",
        "selectedOptions": [{"optionId": 4, "position": 1}],
        "confidence": 1.2,
        "evidence": [],
        "sourceDataCutoff": "2026-09-27T08:00:00Z",
        "generatedAt": "2026-09-27T08:05:00Z",
        "modelVersion": "model-v2",
        "promptVersion": "prompt-v2",
        "featureVersion": "feature-v2",
        "embeddingVersion": None,
        "retrieverVersion": None,
    }
    with pytest.raises(ValidationError):
        PredictionResultV2.model_validate(payload)


def test_failure_v2_exposes_only_enum_safe_summary_and_attempt() -> None:
    payload = {
        "schemaVersion": "2",
        "messageId": "failure-1",
        "predictionJobId": "job-1",
        "batchId": 2,
        "questionId": 9,
        "questionSnapshotId": 12,
        "traceId": "trace-1",
        "failureCode": "MODEL_TIMEOUT",
        "summary": "模型推理超时",
        "attempt": 2,
        "sourceDataCutoff": "2026-09-27T08:00:00Z",
        "generatedAt": "2026-09-27T08:05:00Z",
        "modelVersion": "model-v2",
        "promptVersion": "prompt-v2",
        "featureVersion": "feature-v2",
        "embeddingVersion": None,
        "retrieverVersion": None,
    }
    failure = PredictionFailureV2.model_validate(payload)

    assert failure.failure_code is PredictionFailureCode.MODEL_TIMEOUT
    assert failure.model_dump(mode="json") == payload
    assert "stackTrace" not in failure.model_dump(mode="json")


@pytest.mark.parametrize(
    "summary",
    ["https://internal.example", "Traceback: sensitive", "password=secret", "first\nsecond"],
)
def test_failure_v2_rejects_unsafe_summary(summary: str) -> None:
    payload = {
        "schemaVersion": "2",
        "messageId": "failure-1",
        "predictionJobId": "job-1",
        "batchId": 2,
        "questionId": 9,
        "questionSnapshotId": 12,
        "traceId": "trace-1",
        "failureCode": "MODEL_TIMEOUT",
        "summary": summary,
        "attempt": 1,
        "sourceDataCutoff": "2026-09-27T08:00:00Z",
        "generatedAt": "2026-09-27T08:05:00Z",
        "modelVersion": "model-v2",
        "promptVersion": "prompt-v2",
        "featureVersion": "feature-v2",
        "embeddingVersion": None,
        "retrieverVersion": None,
    }
    with pytest.raises(ValidationError):
        PredictionFailureV2.model_validate(payload)


def test_failure_v2_from_request_copies_stable_ids_cutoff_and_versions(
    request_v2_payload: dict[str, object],
) -> None:
    request = PredictionRequestV2.model_validate(request_v2_payload)
    failure = PredictionFailureV2.from_request(
        request,
        message_id="failure-from-request",
        failure_code=PredictionFailureCode.VERSION_UNAVAILABLE,
        summary="配置版本不可用",
        attempt=1,
        generated_at="2026-09-27T08:05:00Z",
    )

    assert failure.prediction_job_id == request.prediction_job_id
    assert failure.question_snapshot_id == request.question_snapshot_id
    assert failure.source_data_cutoff == request.data_cutoff
    assert failure.model_version == request.model_version
    assert failure.embedding_version == request.embedding_version
    assert failure.failure_code is PredictionFailureCode.VERSION_UNAVAILABLE


def test_failure_v2_rejects_unknown_failure_code() -> None:
    payload = {
        "schemaVersion": "2",
        "messageId": "failure-1",
        "predictionJobId": "job-1",
        "batchId": 2,
        "questionId": 9,
        "questionSnapshotId": 12,
        "traceId": "trace-1",
        "failureCode": "DATABASE_PASSWORD_LEAK",
        "summary": "处理失败",
        "attempt": 1,
        "sourceDataCutoff": "2026-09-27T08:00:00Z",
        "generatedAt": "2026-09-27T08:05:00Z",
        "modelVersion": "model-v2",
        "promptVersion": "prompt-v2",
        "featureVersion": "feature-v2",
        "embeddingVersion": None,
        "retrieverVersion": None,
    }
    with pytest.raises(ValidationError):
        PredictionFailureV2.model_validate(payload)
