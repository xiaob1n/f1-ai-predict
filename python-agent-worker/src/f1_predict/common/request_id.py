"""HTTP 与异步任务共用的请求追踪标识。

使用 ``contextvars`` 保存当前 request_id，避免线程本地存储在 async
任务切换时串请求。入站值只接受短且无控制字符的白名单，降低日志注入风险。
"""

from __future__ import annotations

import re
import uuid
from contextvars import ContextVar, Token
from typing import Final

# 对外响应与入站校验使用的请求头，与 Java RequestId.HEADER 对齐。
HEADER: Final[str] = "X-Request-Id"

# 结构化日志中的关联键，与 Java RequestId.MDC_KEY / JSON 字段 requestId 对齐。
MDC_KEY: Final[str] = "requestId"

# 入站 X-Request-Id 白名单：短、可见 ASCII、无空白与控制字符。
_SAFE_REQUEST_ID: Final[re.Pattern[str]] = re.compile(r"^[A-Za-z0-9._-]{1,128}$")

# 请求级上下文；默认 None 表示尚未绑定。禁止改用 threading.local。
_request_id: ContextVar[str | None] = ContextVar("f1_predict_request_id", default=None)


def resolve_request_id(incoming: str | None) -> str:
    """解析入站 request_id。

    仅接受白名单形态；缺失、过长、含空白/符号的值一律丢弃并改生成 UUID。
    """
    if incoming is not None and _SAFE_REQUEST_ID.fullmatch(incoming) is not None:
        return incoming
    return str(uuid.uuid4())


def bind_request_id(incoming: str | None) -> Token[str | None]:
    """把解析后的 request_id 写入当前上下文，返回可复位的 token。"""
    return _request_id.set(resolve_request_id(incoming))


def get_request_id() -> str | None:
    """读取当前上下文中的 request_id；尚未绑定时返回 None。"""
    return _request_id.get()


def reset_request_id(token: Token[str | None]) -> None:
    """用 bind 返回的 token 恢复上下文，避免请求结束后泄漏到后续任务。"""
    _request_id.reset(token)
