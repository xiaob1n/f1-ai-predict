"""统一 REST 错误体与异常处理器。

对外只返回 ``{code,message}``，不把堆栈、URL、内部类名写进响应。
"""

from __future__ import annotations

from typing import ClassVar

import structlog
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

_LOGGER = structlog.get_logger("f1_predict.api")


class ApiJsonModel(BaseModel):
    """REST JSON 基类：默认按 alias 输出，禁止未知字段。"""

    model_config: ClassVar[ConfigDict] = ConfigDict(
        populate_by_name=True,
        serialize_by_alias=True,
        extra="forbid",
        frozen=True,
    )


class ApiErrorResponse(ApiJsonModel):
    """与 Java ``ApiErrorResponse`` 对齐的错误体。"""

    code: str = Field(alias="code")
    """稳定错误码，如 RESOURCE_NOT_FOUND。"""

    message: str = Field(alias="message")
    """安全的人类可读描述，不含内部细节。"""


def _json_error(*, status_code: int, code: str, message: str) -> JSONResponse:
    """把错误体序列化为 JSON 响应。"""
    body = ApiErrorResponse(code=code, message=message)
    return JSONResponse(status_code=status_code, content=body.model_dump(mode="json"))


def _http_error_code(status_code: int) -> str:
    """把 HTTP 状态映射为对外错误码。"""
    match status_code:
        case 404:
            return "RESOURCE_NOT_FOUND"
        case 422:
            return "VALIDATION_ERROR"
        case _:
            return "HTTP_ERROR"


def _http_error_message(status_code: int) -> str:
    """对外稳定文案，不回显框架 detail。"""
    match status_code:
        case 404:
            return "资源不存在"
        case 422:
            return "请求参数校验失败"
        case _:
            return "请求无法处理"


async def handle_http_exception(_request: Request, exc: Exception) -> JSONResponse:
    """将 Starlette/FastAPI HTTP 异常转为统一错误体。"""
    match exc:
        case StarletteHTTPException(status_code=status_code):
            return _json_error(
                status_code=status_code,
                code=_http_error_code(status_code),
                message=_http_error_message(status_code),
            )
        case _:
            return _json_error(
                status_code=500,
                code="INTERNAL_ERROR",
                message="服务内部错误",
            )


async def handle_validation_error(_request: Request, _exc: Exception) -> JSONResponse:
    """将请求校验失败转为 422，不回显 loc/input。"""
    return _json_error(
        status_code=422,
        code="VALIDATION_ERROR",
        message="请求参数校验失败",
    )


async def handle_unhandled_error(_request: Request, _exc: Exception) -> JSONResponse:
    """将未捕获异常转为 500，不回显类名、堆栈或 URL。"""
    _LOGGER.error("请求处理失败")
    return _json_error(
        status_code=500,
        code="INTERNAL_ERROR",
        message="服务内部错误",
    )


def register_exception_handlers(application: FastAPI) -> None:
    """显式注册 404/422/500 三类边界处理器。"""
    application.add_exception_handler(StarletteHTTPException, handle_http_exception)
    application.add_exception_handler(RequestValidationError, handle_validation_error)
    application.add_exception_handler(Exception, handle_unhandled_error)
