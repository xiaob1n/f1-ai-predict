"""request_id 契约：白名单保留、非法回退 UUID、contextvars token 复位。"""

from __future__ import annotations

import uuid

from f1_predict.common.request_id import (
    bind_request_id,
    get_request_id,
    reset_request_id,
    resolve_request_id,
)


def _assert_generated_uuid(value: str) -> None:
    parsed = uuid.UUID(value)
    assert str(parsed) == value


def test_safe_request_id_preserved() -> None:
    """Given 形态合法的入站 ID, When 解析, Then 原样保留."""
    assert resolve_request_id("trace-abc_1.2") == "trace-abc_1.2"
    bounded = "A" * 128
    assert resolve_request_id(bounded) == bounded


def test_unsafe_request_id_fallback() -> None:
    """Given 缺失或非法入站 ID, When 解析, Then 丢弃并生成 UUID."""
    _assert_generated_uuid(resolve_request_id(None))
    _assert_generated_uuid(resolve_request_id(""))
    _assert_generated_uuid(resolve_request_id("has space"))
    _assert_generated_uuid(resolve_request_id("id;drop"))
    _assert_generated_uuid(resolve_request_id("id\ninjected"))
    _assert_generated_uuid(resolve_request_id("A" * 129))
    _assert_generated_uuid(resolve_request_id("../escape"))
    _assert_generated_uuid(resolve_request_id("中文id"))
    first = resolve_request_id(None)
    second = resolve_request_id(None)
    assert first != second


def test_bind_request_id_resets_context_token() -> None:
    """Given 绑定的 context token, When reset, Then 恢复到绑定前的值."""
    assert get_request_id() is None

    outer = bind_request_id("outer-id")
    assert get_request_id() == "outer-id"

    inner = bind_request_id("inner-id")
    assert get_request_id() == "inner-id"

    reset_request_id(inner)
    assert get_request_id() == "outer-id"

    reset_request_id(outer)
    assert get_request_id() is None


def test_bind_unsafe_request_id_stores_uuid() -> None:
    """Given 非法入站 ID, When bind, Then 上下文中是新生成的 UUID 且 reset 后清空."""
    token = bind_request_id("id;drop")
    try:
        bound = get_request_id()
        assert bound is not None
        _assert_generated_uuid(bound)
        assert bound != "id;drop"
    finally:
        reset_request_id(token)
    assert get_request_id() is None
