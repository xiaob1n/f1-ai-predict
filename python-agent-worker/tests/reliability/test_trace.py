"""TraceSink 契约：严格 TraceRecord、可关闭 span、只写 structlog。"""

from __future__ import annotations

import ast
import inspect
from abc import ABC
from datetime import UTC, datetime
from io import StringIO
from pathlib import Path

import pytest
import structlog
from f1_predict.reliability.trace import (
    ConsoleTraceSink,
    TraceRecord,
    TraceSink,
    TraceSpan,
)
from pydantic import ValidationError
from structlog.testing import capture_logs


def _sample_occurred_at() -> datetime:
    return datetime(2026, 9, 7, 8, 0, tzinfo=UTC)


def _sample_record() -> TraceRecord:
    return TraceRecord(
        trace_id="trace-1",
        message_id="msg-1",
        prediction_job_id="job-42",
        phase="infer",
        occurred_at=_sample_occurred_at(),
    )


def test_span_records_prediction_job_id() -> None:
    """Given 开启的阶段跨度, When close, Then 结构化日志含 prediction_job_id 等关联键."""
    sink = ConsoleTraceSink()

    with capture_logs() as captured:
        span = sink.start_span(
            trace_id="trace-1",
            message_id="msg-1",
            prediction_job_id="job-42",
            phase="infer",
        )
        span.close()

    assert len(captured) == 1
    event = captured[0]
    assert event["trace_id"] == "trace-1"
    assert event["message_id"] == "msg-1"
    assert event["prediction_job_id"] == "job-42"
    assert event["phase"] == "infer"


def test_invalid_trace_record_field() -> None:
    """Given phase 不是字符串, When 构造 TraceRecord, Then 抛 ValidationError 且不把 int 转成 str."""
    with pytest.raises(ValidationError) as raised:
        TraceRecord.model_validate(
            {
                "trace_id": "trace-1",
                "message_id": "msg-1",
                "prediction_job_id": "job-42",
                "phase": 123,
                "occurred_at": _sample_occurred_at(),
            }
        )

    errors = raised.value.errors()
    phase_errors = [item for item in errors if item["loc"] == ("phase",)]
    assert phase_errors
    assert phase_errors[0]["type"] == "string_type"
    assert phase_errors[0]["input"] == 123


def test_record_emits_structlog_event() -> None:
    """Given 合法 TraceRecord, When record, Then 只向 structlog 写入约定字段."""
    sink = ConsoleTraceSink()
    record = _sample_record()

    with capture_logs() as captured:
        sink.record(record)

    assert len(captured) == 1
    event = captured[0]
    assert event["trace_id"] == "trace-1"
    assert event["message_id"] == "msg-1"
    assert event["prediction_job_id"] == "job-42"
    assert event["phase"] == "infer"
    assert event["occurred_at"] == _sample_occurred_at()


def test_start_span_returns_context_manager() -> None:
    """Given start_span 句柄, When 进入 with, Then 退出时写出一条追踪日志."""
    sink = ConsoleTraceSink()

    with (
        capture_logs() as captured,
        sink.start_span(
            trace_id="trace-ctx",
            message_id="msg-ctx",
            prediction_job_id="job-ctx",
            phase="embed",
        ) as span,
    ):
        assert isinstance(span, TraceSpan)

    assert len(captured) == 1
    assert captured[0]["prediction_job_id"] == "job-ctx"
    assert captured[0]["phase"] == "embed"


def test_span_close_is_idempotent() -> None:
    """Given 已 close 的 span, When 再次 close, Then 不会重复写日志."""
    sink = ConsoleTraceSink()

    with capture_logs() as captured:
        span = sink.start_span(
            trace_id="trace-dup",
            message_id="msg-dup",
            prediction_job_id="job-dup",
            phase="score",
        )
        span.close()
        span.close()

    assert len(captured) == 1
    assert captured[0]["prediction_job_id"] == "job-dup"


def test_span_context_manager_close_is_idempotent() -> None:
    """Given with 块内已手动 close, When 退出 with, Then 仍只写一条日志."""
    sink = ConsoleTraceSink()

    with (
        capture_logs() as captured,
        sink.start_span(
            trace_id="trace-mix",
            message_id="msg-mix",
            prediction_job_id="job-mix",
            phase="load",
        ) as span,
    ):
        span.close()

    assert len(captured) == 1


def test_span_records_when_body_raises() -> None:
    """Given with 块抛异常, When 退出, Then 仍写出一条追踪日志且异常继续抛出."""
    sink = ConsoleTraceSink()

    with (
        capture_logs() as captured,
        pytest.raises(RuntimeError, match="boom"),
        sink.start_span(
            trace_id="trace-exc",
            message_id="msg-exc",
            prediction_job_id="job-exc",
            phase="fail",
        ),
    ):
        raise RuntimeError("boom")

    assert len(captured) == 1
    assert captured[0]["trace_id"] == "trace-exc"
    assert captured[0]["message_id"] == "msg-exc"
    assert captured[0]["prediction_job_id"] == "job-exc"
    assert captured[0]["phase"] == "fail"


def test_trace_sink_is_abstract_base() -> None:
    """Given TraceSink 抽象基类, When 直接实例化, Then TypeError."""
    assert issubclass(TraceSink, ABC)
    assert issubclass(ConsoleTraceSink, TraceSink)
    with pytest.raises(TypeError):
        TraceSink()  # type: ignore[abstract]


def test_trace_record_is_frozen_strict_pydantic_model() -> None:
    """Given 合法 TraceRecord, When 改字段或塞额外键, Then 拒绝."""
    record = _sample_record()
    with pytest.raises(ValidationError):
        record.phase = "other"  # type: ignore[misc]
    with pytest.raises(ValidationError):
        TraceRecord.model_validate(
            {
                "trace_id": "trace-1",
                "message_id": "msg-1",
                "prediction_job_id": "job-42",
                "phase": "infer",
                "occurred_at": _sample_occurred_at(),
                "extra_field": "nope",
            }
        )
    assert isinstance(record.occurred_at, datetime)


def test_console_trace_sink_writes_json_via_structlog() -> None:
    """Given 配置 JSON 渲染, When close span, Then stdout 是含关联键的 JSON 行."""
    import json

    buffer = StringIO()
    try:
        from f1_predict.common.logging import configure_logging

        configure_logging(log_level="INFO", stream=buffer)
        sink = ConsoleTraceSink()
        sink.start_span(
            trace_id="trace-json",
            message_id="msg-json",
            prediction_job_id="job-json",
            phase="persist",
        ).close()
    finally:
        structlog.reset_defaults()

    payload = json.loads(buffer.getvalue())
    assert payload["trace_id"] == "trace-json"
    assert payload["message_id"] == "msg-json"
    assert payload["prediction_job_id"] == "job-json"
    assert payload["phase"] == "persist"


def test_trace_module_does_not_use_thread_local_or_io() -> None:
    """Given trace 源码, When AST 扫描, Then 无 threading.local / 网络 / 数据库调用."""
    source_path = Path(inspect.getfile(TraceSink))
    tree = ast.parse(source_path.read_text(encoding="utf-8"))
    imported: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name.split(".", maxsplit=1)[0] for alias in node.names)
        if isinstance(node, ast.ImportFrom) and node.module is not None:
            imported.add(node.module.split(".", maxsplit=1)[0])
        if isinstance(node, ast.Attribute) and node.attr == "local":
            pytest.fail("trace 模块不得使用 threading.local")

    forbidden = {
        "socket",
        "httpx",
        "httpx2",
        "aiohttp",
        "requests",
        "pymongo",
        "motor",
        "sqlalchemy",
        "psycopg",
        "mysql",
        "pika",
        "qdrant_client",
        "threading",
    }
    assert imported.isdisjoint(forbidden)
