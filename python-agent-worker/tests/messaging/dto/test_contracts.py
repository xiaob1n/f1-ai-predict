"""DTO 与 Java 接口契约：第五章示例 round-trip、显式 camelCase alias、无 rawJson。

以 python-api-design.md 5.3-5.6 示例为准（非 5.2 通用信封正文）。
5.2 把 questionSnapshotId 写成四类消息必含字段，但 5.4/5.6 示例省略；
本契约钉住示例，进度/失败消息不得出现该键。
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from types import UnionType
from typing import Annotated, Literal, assert_never, get_args, get_origin

from pydantic import BaseModel
from pydantic.fields import FieldInfo

from f1_predict.messaging.dto import (
    EvidenceRef,
    PredictionFailureMessage,
    PredictionProgressMessage,
    PredictionRequestMessage,
    PredictionResultMessage,
    QuestionOptionPayload,
    QuestionPayload,
    RaceContext,
    SelectedOption,
)
from f1_predict.messaging.dto.base import PredictionMessageEnvelope

type JsonScalar = str | int | float | bool | None
type JsonValue = JsonScalar | list["JsonValue"] | dict[str, "JsonValue"]

# 与设计文档 5.3 请求示例逐键一致；questionType 保持 UNKNOWN。
REQUEST_EXAMPLE: dict[str, JsonValue] = {
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

# 与设计文档 5.4 进度示例逐键一致；不含 questionSnapshotId。
PROGRESS_EXAMPLE: dict[str, JsonValue] = {
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

# 与设计文档 5.5 结果示例逐键一致。
RESULT_EXAMPLE: dict[str, JsonValue] = {
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
        "raw": (
            '{"selectedOptions":[{"optionId":117,"position":1}],'
            '"confidence":0.73}'
        ),
    },
    "generatedAt": "2026-08-28T10:03:20Z",
    "traceId": "trace-9b1c7d3e",
}

# 与设计文档 5.6 失败示例逐键一致；不含 questionSnapshotId。
FAILURE_EXAMPLE: dict[str, JsonValue] = {
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

_EXAMPLE_CASES: tuple[tuple[str, type[BaseModel], dict[str, JsonValue]], ...] = (
    ("request", PredictionRequestMessage, REQUEST_EXAMPLE),
    ("progress", PredictionProgressMessage, PROGRESS_EXAMPLE),
    ("result", PredictionResultMessage, RESULT_EXAMPLE),
    ("failure", PredictionFailureMessage, FAILURE_EXAMPLE),
)

# 顶层与嵌套 DTO；信封单独列出以便反射继承字段。
_DTO_MODELS: tuple[type[BaseModel], ...] = (
    PredictionMessageEnvelope,
    PredictionRequestMessage,
    PredictionProgressMessage,
    PredictionResultMessage,
    PredictionFailureMessage,
    QuestionPayload,
    QuestionOptionPayload,
    RaceContext,
    SelectedOption,
    EvidenceRef,
)

_TIME_KEYS: frozenset[str] = frozenset(
    {
        "dataCutoff",
        "startedAt",
        "eventTime",
        "publishedAt",
        "sourceDataCutoff",
        "generatedAt",
    }
)

_OPAQUE_JSON_KEYS: frozenset[str] = frozenset({"rawAgentResponse"})


def _is_camel_case(alias: str) -> bool:
    """允许 phase/status/message 等同名小写单词；拒绝下划线与首字母大写。"""
    if "_" in alias or alias == "":
        return False
    return alias[0].islower() and alias.isalnum()


def _field_alias(field: FieldInfo) -> str | None:
    """读取 Pydantic FieldInfo 的显式 serialization/validation alias。"""
    serialization = field.serialization_alias
    if isinstance(serialization, str) and serialization:
        return serialization
    alias = field.alias
    if isinstance(alias, str) and alias:
        return alias
    return None


def _models_in_annotation(annotation: object) -> tuple[type[BaseModel], ...]:
    """从字段注解中收集嵌套 BaseModel，供递归反射。"""
    origin = get_origin(annotation)
    if origin is Annotated:
        return _models_in_annotation(get_args(annotation)[0])
    if origin in {list, set, tuple, dict, UnionType}:
        found: list[type[BaseModel]] = []
        for arg in get_args(annotation):
            found.extend(_models_in_annotation(arg))
        return tuple(found)
    if isinstance(annotation, type) and issubclass(annotation, BaseModel):
        return (annotation,)
    return ()


def _walk_dto_models(root: type[BaseModel]) -> tuple[type[BaseModel], ...]:
    """从顶层消息递归收集所有嵌套 DTO（含自身）。"""
    ordered: list[type[BaseModel]] = []
    seen: set[type[BaseModel]] = set()

    def visit(model_cls: type[BaseModel]) -> None:
        if model_cls in seen:
            return
        seen.add(model_cls)
        ordered.append(model_cls)
        for field in model_cls.model_fields.values():
            for nested in _models_in_annotation(field.annotation):
                visit(nested)

    visit(root)
    return tuple(ordered)


def _alias_violations(model_cls: type[BaseModel]) -> list[str]:
    """返回缺失显式 alias 或 alias 非 camelCase 的字段。"""
    violations: list[str] = []
    for name, field in model_cls.model_fields.items():
        alias = _field_alias(field)
        if alias is None:
            violations.append(f"{model_cls.__name__}.{name}: missing explicit alias")
            continue
        if not _is_camel_case(alias):
            violations.append(
                f"{model_cls.__name__}.{name}: alias {alias!r} is not camelCase"
            )
    return violations


def _aliases(model_cls: type[BaseModel]) -> frozenset[str]:
    """当前模型（含继承）的线上 JSON 键集合。"""
    names: list[str] = []
    for name, field in model_cls.model_fields.items():
        alias = _field_alias(field)
        names.append(alias if alias is not None else name)
    return frozenset(names)


def _normalize(value: JsonValue, key: str | None = None) -> JsonValue:
    """时间键归一到 UTC Z 后再比较；其余 JSON 值保持原样。"""
    match value:
        case dict() as mapping:
            return {item_key: _normalize(item, item_key) for item_key, item in mapping.items()}
        case list() as items:
            return [_normalize(item, key) for item in items]
        case str() as text if key in _TIME_KEYS:
            parsed = datetime.fromisoformat(text)
            utc_value = parsed.astimezone(UTC)
            timespec = "microseconds" if utc_value.microsecond else "seconds"
            return utc_value.replace(tzinfo=None).isoformat(timespec=timespec) + "Z"
        case bool() | int() | float() | str() | None:
            return value
        case unreachable:
            assert_never(unreachable)


def _parse_json_value(value: object) -> JsonValue:
    """把 json.loads 结果解析为 JsonValue，边界只过一次。"""
    match value:
        case dict() as mapping:
            parsed: dict[str, JsonValue] = {}
            for key, item in mapping.items():
                assert isinstance(key, str)
                parsed[key] = _parse_json_value(item)
            return parsed
        case list() as items:
            return [_parse_json_value(item) for item in items]
        case bool() | int() | float() | str() | None:
            return value
        case _:
            raise AssertionError(f"unexpected json value type: {type(value)!r}")


def _assert_no_snake_case_keys(value: JsonValue, *, opaque: bool = False) -> None:
    """线上键不得出现 snake_case；rawAgentResponse 内部视为不透明载荷。"""
    match value:
        case dict() as mapping:
            for key, item in mapping.items():
                if not opaque:
                    assert "_" not in key, f"snake_case key leaked: {key}"
                    assert key != "rawJson"
                next_opaque = opaque or key in _OPAQUE_JSON_KEYS
                _assert_no_snake_case_keys(item, opaque=next_opaque)
        case list() as items:
            for item in items:
                _assert_no_snake_case_keys(item, opaque=opaque)
        case bool() | int() | float() | str() | None:
            return
        case unreachable:
            assert_never(unreachable)


def _assert_exact_tree(expected: JsonValue, actual: JsonValue) -> None:
    """递归比较精确键集合、JSON 类型与规范化后的值。"""
    match expected, actual:
        case dict() as exp_map, dict() as act_map:
            assert frozenset(act_map) == frozenset(exp_map), (
                f"key set mismatch: extra={frozenset(act_map) - frozenset(exp_map)} "
                f"missing={frozenset(exp_map) - frozenset(act_map)}"
            )
            for key, exp_val in exp_map.items():
                _assert_exact_tree(exp_val, act_map[key])
        case list() as exp_items, list() as act_items:
            assert len(exp_items) == len(act_items)
            for exp_item, act_item in zip(exp_items, act_items, strict=True):
                _assert_exact_tree(exp_item, act_item)
        case bool() as exp_bool, bool() as act_bool:
            assert act_bool is exp_bool
        case int() as exp_int, int() as act_int if not isinstance(expected, bool):
            assert act_int == exp_int
        case float() as exp_float, float() as act_float:
            assert act_float == exp_float
        case str() as exp_str, str() as act_str:
            assert act_str == exp_str
        case None, None:
            return
        case _:
            raise AssertionError(
                f"type/value mismatch: expected {expected!r} actual {actual!r}"
            )


def _json_roundtrip(
    model_cls: type[BaseModel], payload: dict[str, JsonValue]
) -> dict[str, JsonValue]:
    """校验 → dump_json → 再校验，返回 camelCase JSON 对象。"""
    model = model_cls.model_validate(payload)
    raw = model.model_dump_json()
    assert "+00:00" not in raw
    loaded = _parse_json_value(json.loads(raw))
    assert isinstance(loaded, dict)
    _assert_no_snake_case_keys(loaded)
    again = model_cls.model_validate_json(raw)
    again_loaded = _parse_json_value(json.loads(again.model_dump_json()))
    assert again_loaded == loaded
    return loaded


def test_example_json_roundtrip() -> None:
    """Given 5.3-5.6 示例, When JSON round-trip, Then 键集合/类型/规范化值一致."""
    for _name, model_cls, payload in _EXAMPLE_CASES:
        loaded = _json_roundtrip(model_cls, payload)
        _assert_exact_tree(_normalize(payload), _normalize(loaded))


def test_all_fields_have_camelcase_alias() -> None:
    """Given 全部顶层与嵌套 DTO, When 反射 model_fields, Then 每字段显式 camelCase alias."""
    seen: set[type[BaseModel]] = set()
    violations: list[str] = []
    for _name, model_cls, _payload in _EXAMPLE_CASES:
        for nested in _walk_dto_models(model_cls):
            if nested in seen:
                continue
            seen.add(nested)
            violations.extend(_alias_violations(nested))
    for model_cls in _DTO_MODELS:
        if model_cls in seen:
            continue
        violations.extend(_alias_violations(model_cls))
    assert violations == []


def test_example_key_sets_match_model_aliases() -> None:
    """Given 示例 JSON, When 对照 model_fields alias, Then 顶层与嵌套键集合精确相等."""
    question = REQUEST_EXAMPLE["question"]
    race = REQUEST_EXAMPLE["raceContext"]
    assert isinstance(question, dict)
    assert isinstance(race, dict)
    options = question["options"]
    selected = RESULT_EXAMPLE["selectedOptions"]
    evidence = RESULT_EXAMPLE["evidence"]
    assert isinstance(options, list)
    assert isinstance(selected, list)
    assert isinstance(evidence, list)
    option = options[0]
    selected_item = selected[0]
    evidence_item = evidence[0]
    assert isinstance(option, dict)
    assert isinstance(selected_item, dict)
    assert isinstance(evidence_item, dict)

    assert _aliases(PredictionRequestMessage) == frozenset(REQUEST_EXAMPLE)
    assert _aliases(PredictionProgressMessage) == frozenset(PROGRESS_EXAMPLE)
    assert _aliases(PredictionResultMessage) == frozenset(RESULT_EXAMPLE)
    assert _aliases(PredictionFailureMessage) == frozenset(FAILURE_EXAMPLE)
    assert _aliases(QuestionPayload) == frozenset(question)
    assert _aliases(QuestionOptionPayload) == frozenset(option)
    assert _aliases(RaceContext) == frozenset(race)
    assert _aliases(SelectedOption) == frozenset(selected_item)
    assert _aliases(EvidenceRef) == frozenset(evidence_item)


def test_progress_and_failure_omit_question_snapshot_id() -> None:
    """Given 5.4/5.6 示例, When 反射 alias, Then 不含 questionSnapshotId（钉住 5.2 分叉）."""
    assert "questionSnapshotId" not in _aliases(PredictionProgressMessage)
    assert "questionSnapshotId" not in _aliases(PredictionFailureMessage)
    assert "questionSnapshotId" not in PROGRESS_EXAMPLE
    assert "questionSnapshotId" not in FAILURE_EXAMPLE
    assert "questionSnapshotId" in REQUEST_EXAMPLE
    assert "questionSnapshotId" in RESULT_EXAMPLE


def test_no_raw_json_field() -> None:
    """Given 全部 DTO, When 反射字段名与 alias, Then 不存在 rawJson/raw_json."""
    forbidden = {"rawJson", "raw_json"}
    found: list[str] = []
    for model_cls in _DTO_MODELS:
        for name, field in model_cls.model_fields.items():
            alias = _field_alias(field)
            if name in forbidden or alias in forbidden:
                found.append(f"{model_cls.__name__}.{name}")
    assert found == []
    for _name, model_cls, payload in _EXAMPLE_CASES:
        loaded = _json_roundtrip(model_cls, payload)
        dumped = json.dumps(loaded)
        assert '"rawJson"' not in dumped


def test_question_payload_options_default_empty_array() -> None:
    """Given 省略 options, When 序列化 QuestionPayload, Then 为 [] 而非 null."""
    payload = QuestionPayload.model_validate(
        {
            "questionText": "Who will qualify on pole?",
            "subText": None,
            "questionType": "UNKNOWN",
            "optionTemplateId": 1,
            "choiceLimit": 1,
        }
    )
    dumped = _parse_json_value(json.loads(payload.model_dump_json()))
    assert isinstance(dumped, dict)
    assert dumped["options"] == []
    assert dumped["questionType"] == "UNKNOWN"


def test_question_type_stays_unknown() -> None:
    """Given 5.3 示例, When round-trip, Then questionType 仍为 UNKNOWN."""
    loaded = _json_roundtrip(PredictionRequestMessage, REQUEST_EXAMPLE)
    question = loaded["question"]
    assert isinstance(question, dict)
    assert question["questionType"] == "UNKNOWN"
    field = QuestionPayload.model_fields["question_type"]
    assert field.annotation == Literal["UNKNOWN"]


def test_serialized_json_has_no_snake_case_keys() -> None:
    """Given 四类示例 dump, When 递归扫键, Then 无 snake_case；opaque 边界可含内部下划线."""
    for _name, model_cls, payload in _EXAMPLE_CASES:
        loaded = _json_roundtrip(model_cls, payload)
        _assert_no_snake_case_keys(loaded)

    opaque_payload = dict(RESULT_EXAMPLE)
    opaque_payload["rawAgentResponse"] = {"internal_snake": 1, "parsed": {"ok": True}}
    model = PredictionResultMessage.model_validate(opaque_payload)
    opaque_loaded = _parse_json_value(json.loads(model.model_dump_json()))
    assert isinstance(opaque_loaded, dict)
    _assert_no_snake_case_keys(opaque_loaded)
    raw_agent = opaque_loaded["rawAgentResponse"]
    assert isinstance(raw_agent, dict)
    assert "internal_snake" in raw_agent
