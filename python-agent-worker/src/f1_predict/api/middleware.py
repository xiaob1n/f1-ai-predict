"""请求上下文中间件：绑定 request_id、回写响应头、记录访问摘要。

复用 ``common.request_id`` 与 ``common.logging``，不在此重写白名单或日志字段。
路径只取 ``request.url.path``，避免把查询串写入日志。
"""

from __future__ import annotations

import time
from typing import override

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from f1_predict.api.errors import handle_unhandled_error
from f1_predict.common.logging import AccessLogEvent, log_access
from f1_predict.common.request_id import (
    HEADER,
    bind_request_id,
    get_request_id,
    reset_request_id,
)


class RequestContextMiddleware(BaseHTTPMiddleware):
    """为每个 HTTP 请求绑定安全 request_id，并在异常路径复位上下文。"""

    @override
    async def dispatch(
        self,
        request: Request,
        call_next: RequestResponseEndpoint,
    ) -> Response:
        """绑定上下文、调用下游、回写头并记录访问日志。"""
        started = time.monotonic()
        token = bind_request_id(request.headers.get(HEADER))
        try:
            try:
                response = await call_next(request)
            except Exception as exc:  # noqa: BLE001, BROAD_EXCEPT_OK
                # ExceptionMiddleware 在内侧；此处兜底，保证 500 也能写上头并复位上下文。
                response = await handle_unhandled_error(request, exc)
            request_id = get_request_id()
            if request_id is not None:
                response.headers[HEADER] = request_id
            duration_ms = int((time.monotonic() - started) * 1000)
            log_access(
                AccessLogEvent(
                    method=request.method,
                    path=request.url.path,
                    status=response.status_code,
                    duration_ms=duration_ms,
                )
            )
            return response
        finally:
            reset_request_id(token)
