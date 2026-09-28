"""RabbitMQ 消息 DTO 基础契约：camelCase round-trip、未知字段拒绝、时间输出 Z。"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta, timezone
from typing import Any

import pytest
from pydantic import ValidationError

from f1_predict.messaging.dto import (
    PredictionFailureMessage,
    PredictionProgressMessage,
    PredictionRequestMessage,
    PredictionResultMessage,
    QuestionPayload,
)

_REQUEST_PAYLOAD: dict[str, Any] = {
    "schemaVersion": "1",
    "messageId": "msg-7f3a9c2e",
    "predictionJobId": "job-4c2e8b1a",
    "batchId": 1,
    "questionId": 1,
    "questionSnapshotId": 12,
    "question": {
        "questionText": "Who will qualify on pole?",
        "subText": None,
        "questionType": "UNKNOWN",
        "optionTemplateId": 1,
        "choiceLimit": 1,
        "options": [
            {
                "optionId": 117,
                "optionNo": 0,
                "optionText": "Driver A",
                "points": 10,
                "chance": 0.73,
            },
            {
                "optionId": 118,
                "optionNo": 1,
                "optionText": "Driver B",
                "points": 8,
                "chance": 0.27,
            },
        ],
    },
    "raceContext": {
        "seasonId": 1,
        "year": 2026,
        "roundId": 10,
        "roundNumber": 1,
        "meetingKey": 1219,
        "sessionKey": 9161,
        "gamedayId": 100,
        "trackName": "Melbourne Grand Prix Circuit",
    },
    "dataCutoff": "2026-08-28T10:00:00Z",
    "modelVersion": "qwen3-8b-lora-v1",
    "promptVersion": "f1-race-v1",
    "featureVersion": "feature-v1",
    "embeddingVersion": "embedding-v1",
    "retrieverVersion": "retriever-v1",
    "traceId": "trace-9b1c7d3e",
}

_PROGRESS_PAYLOAD: dict[str, Any] = {
    "schemaVersion": "1",
    "messageId": "msg-progress-01",
    "predictionJobId": "job-4c2e8b1a",
    "batchId": 1,
    "questionId": 1,
    "phase": "RAG_RETRIEVAL",
    "progress": 0.4,
    "workerNode": "home-gpu-01",
    "message": "特征加载完成，开始检索",
    "startedAt": "2026-08-28T10:01:00Z",
    "traceId": "trace-9b1c7d3e",
}

_RESULT_PAYLOAD: dict[str, Any] = {
    "schemaVersion": "1",
    "messageId": "msg-result-01",
    "predictionJobId": "job-4c2e8b1a",
    "batchId": 1,
    "questionId": 1,
    "questionSnapshotId": 12,
    "selectedOptions": [{"optionId": 117, "position": 1}],
    "confidence": 0.73,
    "reasoningSummary": "基于近期排位速度和赛道适配性。",
    "evidence": [
        {
            "sourceType": "OPENF1_SESSION_RESULT",
            "sourceName": "OpenF1 session_result",
            "sourceUrl": "https://example.com/source",
            "eventTime": "2026-08-20T12:00:00Z",
            "publishedAt": "2026-08-20T12:05:00Z",
            "documentId": "doc-001",
            "chunkId": "chunk-001",
        }
    ],
    "retrievedChunkIds": ["chunk-001", "chunk-002"],
    "sourceDataCutoff": "2026-08-28T10:00:00Z",
    "modelVersion": "qwen3-8b-lora-v1",
    "agentVersion": "agent-0.1.0",
    "promptVersion": "f1-race-v1",
    "featureVersion": "feature-v1",
    "embeddingVersion": "embedding-v1",
    "retrieverVersion": "retriever-v1",
    "rawAgentResponse": {
        "parsed": {
            "selectedOptions": [{"optionId": 117, "position": 1}],
            "confidence": 0.73,
        },
        "raw": '{"selectedOptions":[{"optionId":117,"position":1}],"confidence":0.73}',
    },
    "generatedAt": "2026-08-28T10:03:20Z",
    "traceId": "trace-9b1c7d3e",
}

_FAILURE_PAYLOAD: dict[str, Any] = {
    "schemaVersion": "1",
    "messageId": "msg-failure-01",
    "predictionJobId": "job-4c2e8b1a",
    "batchId": 1,
    "questionId": 1,
    "phase": "MODEL_INFERENCE",
    "errorCode": "MODEL_TIMEOUT",
    "errorMessage": "模型推理超时，请稍后重试",
    "retryable": True,
    "attempt": 2,
    "maxRetries": 3,
    "workerNode": "home-gpu-01",
    "traceId": "trace-9b1c7d3e",
}


def _assert_no_snake_case_keys(value: object) -> None:
    """线上键不得出现下划线；rawAgentResponse 内部视为不透明载荷。

    Python ``model_dump()`` 可保留 datetime 对象；JSON 路径只允许标量。
    """
    match value:
        case dict() as mapping:
            for key, item in mapping.items():
                assert isinstance(key, str)
                assert "_" not in key, f"snake_case key leaked: {key}"
                if key == "rawAgentResponse":
                    continue
                _assert_no_snake_case_keys(item)
        case list() as items:
            for item in items:
                _assert_no_snake_case_keys(item)
        case str() | int() | float() | bool() | None | datetime():
            return
        case _:
            pytest.fail(f"unexpected dump value type: {type(value)!r}")


def _json_roundtrip(model_cls: type[Any], payload: dict[str, Any]) -> dict[str, Any]:
    model = model_cls.model_validate(payload)
    raw = model.model_dump_json()
    assert "+00:00" not in raw
    loaded = json.loads(raw)
    _assert_no_snake_case_keys(loaded)
    dumped = model.model_dump()
    _assert_no_snake_case_keys(dumped)
    again = model_cls.model_validate_json(raw)
    assert json.loads(again.model_dump_json()) == loaded
    return loaded


def test_result_message_roundtrip() -> None:
    """Given 设计文档结果示例, When JSON round-trip, Then camelCase 字段值保持一致."""
    loaded = _json_roundtrip(PredictionResultMessage, _RESULT_PAYLOAD)

    assert loaded["schemaVersion"] == "1"
    assert loaded["messageId"] == "msg-result-01"
    assert loaded["predictionJobId"] == "job-4c2e8b1a"
    assert loaded["batchId"] == 1
    assert loaded["questionId"] == 1
    assert loaded["questionSnapshotId"] == 12
    assert loaded["selectedOptions"] == [{"optionId": 117, "position": 1}]
    assert loaded["confidence"] == 0.73
    assert loaded["reasoningSummary"] == "基于近期排位速度和赛道适配性。"
    assert loaded["evidence"][0]["sourceType"] == "OPENF1_SESSION_RESULT"
    assert loaded["evidence"][0]["eventTime"] == "2026-08-20T12:00:00Z"
    assert loaded["evidence"][0]["publishedAt"] == "2026-08-20T12:05:00Z"
    assert loaded["retrievedChunkIds"] == ["chunk-001", "chunk-002"]
    assert loaded["sourceDataCutoff"] == "2026-08-28T10:00:00Z"
    assert loaded["generatedAt"] == "2026-08-28T10:03:20Z"
    assert loaded["rawAgentResponse"]["parsed"]["confidence"] == 0.73
    assert loaded["traceId"] == "trace-9b1c7d3e"


def test_request_message_roundtrip() -> None:
    """Given 设计文档请求示例, When JSON round-trip, Then 题目与赛事上下文键为 camelCase."""
    loaded = _json_roundtrip(PredictionRequestMessage, _REQUEST_PAYLOAD)

    assert loaded["schemaVersion"] == "1"
    assert loaded["questionSnapshotId"] == 12
    assert loaded["question"]["questionType"] == "UNKNOWN"
    assert loaded["question"]["questionText"] == "Who will qualify on pole?"
    assert loaded["question"]["subText"] is None
    assert loaded["question"]["options"][0]["optionId"] == 117
    assert loaded["question"]["options"][0]["optionNo"] == 0
    assert loaded["raceContext"]["seasonId"] == 1
    assert loaded["raceContext"]["meetingKey"] == 1219
    assert loaded["raceContext"]["trackName"] == "Melbourne Grand Prix Circuit"
    assert loaded["dataCutoff"] == "2026-08-28T10:00:00Z"


def test_progress_message_roundtrip() -> None:
    """Given 设计文档进度示例, When JSON round-trip, Then phase/progress/startedAt 保持一致."""
    loaded = _json_roundtrip(PredictionProgressMessage, _PROGRESS_PAYLOAD)

    assert loaded["phase"] == "RAG_RETRIEVAL"
    assert loaded["progress"] == 0.4
    assert loaded["workerNode"] == "home-gpu-01"
    assert loaded["message"] == "特征加载完成，开始检索"
    assert loaded["startedAt"] == "2026-08-28T10:01:00Z"


def test_failure_message_roundtrip() -> None:
    """Given 设计文档失败示例, When JSON round-trip, Then 错误摘要字段保持一致."""
    loaded = _json_roundtrip(PredictionFailureMessage, _FAILURE_PAYLOAD)

    assert loaded["errorCode"] == "MODEL_TIMEOUT"
    assert loaded["errorMessage"] == "模型推理超时，请稍后重试"
    assert loaded["retryable"] is True
    assert loaded["attempt"] == 2
    assert loaded["maxRetries"] == 3


def test_unknown_field_rejected() -> None:
    """Given 未知字段, When 反序列化, Then extra=forbid 触发 ValidationError."""
    payload = dict(_RESULT_PAYLOAD)
    payload["unexpectedField"] = True

    with pytest.raises(ValidationError) as exc_info:
        PredictionResultMessage.model_validate(payload)

    assert exc_info.value.errors()[0]["type"] == "extra_forbidden"


def test_nested_unknown_field_rejected() -> None:
    """Given 嵌套选项上的未知字段, When 反序列化, Then 同样拒绝."""
    payload = json.loads(json.dumps(_REQUEST_PAYLOAD))
    payload["question"]["options"][0]["extraFlag"] = 1

    with pytest.raises(ValidationError) as exc_info:
        PredictionRequestMessage.model_validate(payload)

    assert exc_info.value.errors()[0]["type"] == "extra_forbidden"


def test_confidence_out_of_range_rejected() -> None:
    """Given confidence=1.5, When 反序列化, Then 边界校验失败."""
    payload = dict(_RESULT_PAYLOAD)
    payload["confidence"] = 1.5

    with pytest.raises(ValidationError) as exc_info:
        PredictionResultMessage.model_validate(payload)

    assert exc_info.value.errors()[0]["type"] == "less_than_equal"


def test_naive_datetime_rejected() -> None:
    """Given naive datetime, When 反序列化, Then 因缺少时区被拒绝."""
    payload = dict(_RESULT_PAYLOAD)
    payload["generatedAt"] = datetime.fromisoformat("2026-08-28T10:03:20")

    with pytest.raises(ValidationError) as exc_info:
        PredictionResultMessage.model_validate(payload)

    assert exc_info.value.errors()[0]["type"] == "timezone_aware"

    payload["generatedAt"] = "2026-08-28T10:03:20"
    with pytest.raises(ValidationError) as exc_info:
        PredictionResultMessage.model_validate(payload)

    assert exc_info.value.errors()[0]["type"] == "timezone_aware"


def test_datetime_json_uses_z_suffix() -> None:
    """Given UTC aware 时间, When model_dump_json, Then 输出 Z 且不含 +00:00."""
    payload = dict(_RESULT_PAYLOAD)
    payload["generatedAt"] = datetime(2026, 8, 28, 10, 3, 20, tzinfo=UTC)

    raw = PredictionResultMessage.model_validate(payload).model_dump_json()
    loaded = json.loads(raw)

    assert loaded["generatedAt"] == "2026-08-28T10:03:20Z"
    assert "+00:00" not in raw


def test_non_utc_offset_normalized_to_utc_z() -> None:
    """Given 非 UTC 偏移时间, When 序列化, Then 归一到 UTC 并以 Z 输出."""
    payload = dict(_REQUEST_PAYLOAD)
    payload["dataCutoff"] = "2026-08-28T21:00:00+11:00"

    loaded = _json_roundtrip(PredictionRequestMessage, payload)
    assert loaded["dataCutoff"] == "2026-08-28T10:00:00Z"

    plus_eleven = timezone(timedelta(hours=11))
    payload["dataCutoff"] = datetime(2026, 8, 28, 21, 0, 0, tzinfo=plus_eleven)
    loaded = _json_roundtrip(PredictionRequestMessage, payload)
    assert loaded["dataCutoff"] == "2026-08-28T10:00:00Z"


def test_options_default_is_empty_and_not_shared() -> None:
    """Given 省略 options, When 构造两个 QuestionPayload, Then 默认 [] 且互不共享."""
    first = QuestionPayload.model_validate(
        {
            "questionText": "A",
            "subText": None,
            "questionType": "UNKNOWN",
            "optionTemplateId": 1,
            "choiceLimit": 1,
        }
    )
    second = QuestionPayload.model_validate(
        {
            "questionText": "B",
            "subText": None,
            "questionType": "UNKNOWN",
            "optionTemplateId": 2,
            "choiceLimit": 1,
        }
    )

    assert first.options == []
    assert second.options == []
    assert first.options is not second.options

    dumped = json.loads(first.model_dump_json())
    assert dumped["options"] == []
    assert "optionText" not in dumped


def test_schema_version_must_be_1() -> None:
    """Given schemaVersion 非 1, When 反序列化, Then Literal 校验失败."""
    payload = dict(_PROGRESS_PAYLOAD)
    payload["schemaVersion"] = "2"

    with pytest.raises(ValidationError):
        PredictionProgressMessage.model_validate(payload)


def test_question_type_must_be_unknown() -> None:
    """Given questionType 非 UNKNOWN, When 反序列化, Then Literal 校验失败."""
    payload = json.loads(json.dumps(_REQUEST_PAYLOAD))
    payload["question"]["questionType"] = "POLE"

    with pytest.raises(ValidationError):
        PredictionRequestMessage.model_validate(payload)


def test_populate_by_name_accepts_python_field_names() -> None:
    """Given snake_case 属性名, When populate_by_name, Then 仍能构造并按 alias 输出."""
    model = PredictionFailureMessage.model_validate(
        {
            "schema_version": "1",
            "message_id": "msg-failure-02",
            "prediction_job_id": "job-4c2e8b1a",
            "batch_id": 1,
            "question_id": 1,
            "phase": "FAILED",
            "error_code": "JSON_INVALID",
            "error_message": "消息无法解析",
            "retryable": False,
            "attempt": 1,
            "max_retries": 3,
            "worker_node": "home-gpu-01",
            "trace_id": "trace-9b1c7d3e",
        }
    )
    loaded = json.loads(model.model_dump_json())

    assert loaded["schemaVersion"] == "1"
    assert loaded["errorCode"] == "JSON_INVALID"
    assert loaded["errorMessage"] == "消息无法解析"
    assert loaded["workerNode"] == "home-gpu-01"
    _assert_no_snake_case_keys(loaded)
