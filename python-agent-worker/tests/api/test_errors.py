"""统一错误体契约：404/422/500 只返回 {code,message}，不泄漏内部信息。"""

from __future__ import annotations

import uuid
from collections.abc import Iterator

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from structlog.testing import capture_logs

from f1_predict.api.app import app
from f1_predict.api.errors import ApiErrorResponse, register_exception_handlers
from f1_predict.api.middleware import RequestContextMiddleware
from f1_predict.common.request_id import HEADER, get_request_id


def _assert_error_body(payload: object, *, code: str) -> None:
    assert isinstance(payload, dict)
    assert set(payload) == {"code", "message"}
    assert payload["code"] == code
    assert isinstance(payload["message"], str)
    assert payload["message"]
    message = payload["message"]
    lowered = message.lower()
    assert "traceback" not in lowered
    assert "exception" not in lowered
    assert "starlette" not in lowered
    assert "fastapi" not in lowered
    assert "runtimeerror" not in lowered
    assert "http://" not in lowered
    assert "https://" not in lowered


def test_undefined_route_returns_resource_not_found() -> None:
    """Given 未定义路径, When GET, Then 404 RESOURCE_NOT_FOUND 且无堆栈/URL."""
    client = TestClient(app)

    response = client.get("/no-such-route?token=secret")

    assert response.status_code == 404
    _assert_error_body(response.json(), code="RESOURCE_NOT_FOUND")
    dumped = response.text.lower()
    assert "traceback" not in dumped
    assert "token=secret" not in dumped
    assert "secret" not in dumped
    assert response.headers[HEADER]


def test_predict_and_models_current_are_absent() -> None:
    """Given 阶段一范围, When 访问未实现路径, Then 均为 404 而非业务实现."""
    client = TestClient(app)
    predict = client.post("/predict")
    models = client.get("/api/v1/models/current")
    assert predict.status_code == 404
    assert models.status_code == 404
    _assert_error_body(predict.json(), code="RESOURCE_NOT_FOUND")
    _assert_error_body(models.json(), code="RESOURCE_NOT_FOUND")


def test_api_error_response_fields_have_explicit_aliases() -> None:
    """Given ApiErrorResponse, When 内省字段, Then 每个字段都有与属性同名的显式 alias."""
    for name, field in ApiErrorResponse.model_fields.items():
        assert field.alias == name
        assert field.alias in {"code", "message"}


def _temporary_error_app() -> FastAPI:
    """单独测试应用：挂载与生产相同的中间件与异常处理器，不含持久业务路由。"""
    test_app = FastAPI(title="error-contract-test")
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
def error_client() -> Iterator[TestClient]:
    with TestClient(_temporary_error_app(), raise_server_exceptions=False) as client:
        yield client


def test_validation_error_returns_safe_body(error_client: TestClient) -> None:
    """Given 非法查询参数, When GET 校验路由, Then 422 VALIDATION_ERROR 且无内部细节."""
    response = error_client.get("/__test/validate?count=not-an-int")

    assert response.status_code == 422
    _assert_error_body(response.json(), code="VALIDATION_ERROR")
    dumped = response.text.lower()
    assert "traceback" not in dumped
    assert "pydantic" not in dumped
    assert "loc" not in dumped


def test_internal_error_returns_safe_body(error_client: TestClient) -> None:
    """Given 处理器抛出未捕获异常, When GET, Then 500 INTERNAL_ERROR 且无堆栈/类名/URL."""
    response = error_client.get("/__test/server-error")

    assert response.status_code == 500
    payload = response.json()
    _assert_error_body(payload, code="INTERNAL_ERROR")
    dumped = response.text
    assert "traceback" not in dumped.lower()
    assert "RuntimeError" not in dumped
    assert "example.invalid" not in dumped
    assert "https://" not in dumped
    assert response.headers[HEADER]


def test_request_id_header_echoed_on_success() -> None:
    """Given 合法 X-Request-Id, When 访问存活检查, Then 响应回写同一头."""
    client = TestClient(app)
    response = client.get("/health/live", headers={HEADER: "safe-id-live"})
    assert response.status_code == 200
    assert response.headers[HEADER] == "safe-id-live"


def test_malformed_request_id_replaced_with_uuid() -> None:
    """Given 非法 X-Request-Id, When 访问, Then 响应头为新 UUID 而非原值."""
    client = TestClient(app)
    response = client.get("/health/live", headers={HEADER: "id;drop"})
    echoed = response.headers[HEADER]
    assert echoed != "id;drop"
    parsed = uuid.UUID(echoed)
    assert str(parsed) == echoed


def test_exception_path_resets_request_id(error_client: TestClient) -> None:
    """Given 500 路径, When 请求结束, Then 上下文 request_id 已复位."""
    error_client.get("/__test/server-error", headers={HEADER: "err-id-1"})
    assert get_request_id() is None


def test_access_log_uses_path_without_query() -> None:
    """Given 带 query 的请求, When 记访问日志, Then path 不含查询串."""
    client = TestClient(app)
    with capture_logs() as captured:
        client.get("/health/live?token=should-not-appear")

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
