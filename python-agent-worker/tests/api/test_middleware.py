"""中间件与错误体对齐：X-Request-Id 回写、访问日志无 query、404/422/500 安全体。

500/422 走一次性测试应用，不向生产 ``app`` 挂持久路由。
Todo 4 的 ``test_errors.py`` 契约保持独立，本文件只覆盖计划指定的对齐场景。
"""

from __future__ import annotations

import uuid
from collections.abc import Iterator

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from structlog.testing import capture_logs

from f1_predict.api.app import app
from f1_predict.api.errors import register_exception_handlers
from f1_predict.api.middleware import RequestContextMiddleware
from f1_predict.common.request_id import HEADER, get_request_id


def _assert_safe_error_body(payload: object, *, code: str) -> None:
    """断言对外错误体只有 code/message，且文案不含堆栈、类名或 URL。"""
    assert isinstance(payload, dict)
    assert set(payload) == {"code", "message"}
    assert payload["code"] == code
    message = payload["message"]
    assert isinstance(message, str)
    assert message
    lowered = message.lower()
    assert "traceback" not in lowered
    assert "exception" not in lowered
    assert "starlette" not in lowered
    assert "fastapi" not in lowered
    assert "runtimeerror" not in lowered
    assert "http://" not in lowered
    assert "https://" not in lowered


def _throwaway_error_app() -> FastAPI:
    """一次性测试应用：复用生产中间件与异常处理器，不含业务路由。"""
    test_app = FastAPI(title="middleware-contract-test")
    test_app.add_middleware(RequestContextMiddleware)
    register_exception_handlers(test_app)

    @test_app.get("/__test/validate")
    async def validate(count: int) -> dict[str, int]:
        return {"count": count}

    @test_app.get("/__test/server-error")
    async def boom() -> None:
        raise RuntimeError("internal class RuntimeError at https://example.invalid/secret")

    return test_app


@pytest.fixture
def throwaway_client() -> Iterator[TestClient]:
    with TestClient(_throwaway_error_app(), raise_server_exceptions=False) as client:
        yield client


def test_request_id_header_echo() -> None:
    """Given 合法 X-Request-Id, When GET 存活检查, Then 响应回写同一头."""
    client = TestClient(app)

    response = client.get("/health/live", headers={HEADER: "safe-id-middleware"})

    assert response.status_code == 200
    assert response.headers[HEADER] == "safe-id-middleware"
    production_paths = [getattr(route, "path", "") for route in app.routes]
    assert all("__test" not in path for path in production_paths)


def test_malformed_request_id_replaced_with_uuid() -> None:
    """Given 非法 X-Request-Id, When 访问, Then 响应头是新 UUID 而非原值."""
    client = TestClient(app)

    response = client.get("/health/live", headers={HEADER: "id;drop"})

    echoed = response.headers[HEADER]
    assert echoed != "id;drop"
    parsed = uuid.UUID(echoed)
    assert str(parsed) == echoed


def test_access_log_omits_query_string() -> None:
    """Given 带 query 的请求, When 记访问日志, Then 只有 path 且含 method/status/durationMs."""
    client = TestClient(app)

    with capture_logs() as captured:
        response = client.get("/health/live?token=should-not-appear")

    assert response.status_code == 200
    access_events = [event for event in captured if event.get("event") == "HTTP 请求完成"]
    assert len(access_events) == 1
    event = access_events[0]
    assert event["method"] == "GET"
    assert event["path"] == "/health/live"
    assert event["status"] == 200
    assert isinstance(event["durationMs"], int)
    assert event["durationMs"] >= 0
    assert "query" not in event
    assert "token" not in event["path"]
    assert "?" not in event["path"]
    dumped = str(event)
    assert "should-not-appear" not in dumped
    assert "token=should-not-appear" not in dumped


def test_not_found_safe_response() -> None:
    """Given 未定义路径, When GET, Then 404 RESOURCE_NOT_FOUND 且无堆栈/query."""
    client = TestClient(app)

    response = client.get("/no-such-middleware-route?token=secret")

    assert response.status_code == 404
    _assert_safe_error_body(response.json(), code="RESOURCE_NOT_FOUND")
    dumped = response.text.lower()
    assert "traceback" not in dumped
    assert "token=secret" not in dumped
    assert "secret" not in dumped
    assert response.headers[HEADER]


def test_validation_error_safe_response(throwaway_client: TestClient) -> None:
    """Given 非法查询参数, When GET 一次性校验路由, Then 422 VALIDATION_ERROR."""
    response = throwaway_client.get("/__test/validate?count=not-an-int")

    assert response.status_code == 422
    _assert_safe_error_body(response.json(), code="VALIDATION_ERROR")
    dumped = response.text.lower()
    assert "traceback" not in dumped
    assert "pydantic" not in dumped
    assert "loc" not in dumped
    production_paths = [getattr(route, "path", "") for route in app.routes]
    assert all("__test" not in path for path in production_paths)


def test_internal_error_safe_response(throwaway_client: TestClient) -> None:
    """Given 处理器抛未捕获异常, When GET 一次性路由, Then 500 INTERNAL_ERROR 且无泄漏."""
    response = throwaway_client.get("/__test/server-error")

    assert response.status_code == 500
    _assert_safe_error_body(response.json(), code="INTERNAL_ERROR")
    dumped = response.text
    assert "traceback" not in dumped.lower()
    assert "RuntimeError" not in dumped
    assert "example.invalid" not in dumped
    assert "https://" not in dumped
    assert response.headers[HEADER]
    assert get_request_id() is None
    production_paths = [getattr(route, "path", "") for route in app.routes]
    assert all("__test" not in path for path in production_paths)
