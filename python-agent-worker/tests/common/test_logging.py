"""配置默认值与访问日志契约：字段齐全且不含 query。"""

from __future__ import annotations

import json
import os
from io import StringIO

import pytest
import structlog
from f1_predict.common.config import Settings
from f1_predict.common.logging import AccessLogEvent, configure_logging, log_access
from f1_predict.common.request_id import bind_request_id, reset_request_id
from structlog.testing import capture_logs

_ENV_PREFIX = "F1_PREDICT_"


@pytest.fixture(autouse=True)
def _clear_settings_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """Given 可能残留的进程环境, When 跑配置测试, Then 不让外部 F1_PREDICT_* 干扰默认值."""
    for name in list(os.environ):
        if name.startswith(_ENV_PREFIX):
            monkeypatch.delenv(name, raising=False)


def test_settings_safe_defaults() -> None:
    """Given 无覆盖环境变量, When 构造 Settings, Then 使用本机安全默认值且外部 URL 为空."""
    settings = Settings()

    assert settings.host == "127.0.0.1"
    assert settings.port == 8000
    assert settings.log_level == "INFO"
    assert settings.rabbitmq_url == ""
    assert settings.mongodb_url == ""
    assert settings.qdrant_url == ""


def test_settings_env_override_does_not_require_urls(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Given 仅覆盖端口与日志级别, When 构造 Settings, Then URL 仍可为空且端口不是默认值."""
    monkeypatch.setenv("F1_PREDICT_PORT", "8001")
    monkeypatch.setenv("F1_PREDICT_LOG_LEVEL", "DEBUG")

    settings = Settings()

    assert settings.port == 8001
    assert settings.log_level == "DEBUG"
    assert settings.host == "127.0.0.1"
    assert settings.rabbitmq_url == ""
    assert settings.mongodb_url == ""
    assert settings.qdrant_url == ""


def test_access_log_event_contains_required_fields_without_query() -> None:
    """Given 绑定 request_id 且 path 带 query, When 记访问日志, Then 事件含约定字段且无 query."""
    token = bind_request_id("safe-id-1")
    try:
        with capture_logs() as captured:
            log_access(
                AccessLogEvent(
                    method="GET",
                    path="/api/v1/questions/1?token=secret",
                    status=200,
                    duration_ms=12,
                )
            )
    finally:
        reset_request_id(token)

    assert len(captured) == 1
    event = captured[0]
    assert event["method"] == "GET"
    assert event["path"] == "/api/v1/questions/1"
    assert event["status"] == 200
    assert event["durationMs"] == 12
    assert event["requestId"] == "safe-id-1"
    assert "query" not in event
    assert "token=secret" not in event["path"]
    dumped = json.dumps(event, default=str)
    assert "token" not in dumped
    assert "secret" not in dumped
    assert "?" not in event["path"]
    assert "query" not in dumped


def test_access_log_json_omits_query_and_credentials() -> None:
    """Given 配置 JSON 渲染, When 记访问日志, Then stdout JSON 含字段且不含 query/凭据."""
    buffer = StringIO()
    token = bind_request_id("safe-id-1")
    try:
        configure_logging(log_level="INFO", stream=buffer)
        log_access(
            AccessLogEvent(
                method="POST",
                path="/api/v1/admin/sync/schedule?password=hunter2",
                status=204,
                duration_ms=3,
            )
        )
    finally:
        reset_request_id(token)
        structlog.reset_defaults()

    raw = buffer.getvalue()
    payload = json.loads(raw)
    assert payload["method"] == "POST"
    assert payload["path"] == "/api/v1/admin/sync/schedule"
    assert payload["status"] == 204
    assert payload["durationMs"] == 3
    assert payload["requestId"] == "safe-id-1"
    assert "query" not in payload
    assert "password" not in raw
    assert "hunter2" not in raw
    assert "?" not in payload["path"]
