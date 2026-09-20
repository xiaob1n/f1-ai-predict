"""structlog 接入点：访问摘要只记录 method/path/status/durationMs/requestId。

路径在进入日志前去掉查询串，避免把凭证打进日志。requestId 来自
``request_id`` 的 contextvars，不使用线程本地存储。
"""

from __future__ import annotations

import logging as stdlib_logging
import sys
from dataclasses import dataclass
from typing import Final, TextIO, override

import structlog
from structlog.typing import EventDict

from f1_predict.common.request_id import MDC_KEY, get_request_id


@dataclass(frozen=True, slots=True)
class InvalidLogLevelError(Exception):
    """不支持的日志级别。"""

    log_level: str

    @override
    def __str__(self) -> str:
        return f"unsupported log level: {self.log_level}"


@dataclass(frozen=True, slots=True)
class AccessLogEvent:
    """一次 HTTP 访问摘要。

    ``path`` 在构造时剥掉 ``?`` 及之后的查询串；``duration_ms`` 下限为 0。
    """

    method: str
    path: str
    status: int
    duration_ms: int

    def __post_init__(self) -> None:
        object.__setattr__(self, "path", _path_without_query(self.path))
        object.__setattr__(self, "duration_ms", max(0, self.duration_ms))


def _path_without_query(path: str) -> str:
    """去掉查询串，避免把凭证打进日志。"""
    separator_index = path.find("?")
    if separator_index == -1:
        return path
    return path[:separator_index]


_STDLIB_LEVELS: Final[dict[str, int]] = {
    "DEBUG": stdlib_logging.DEBUG,
    "INFO": stdlib_logging.INFO,
    "WARNING": stdlib_logging.WARNING,
    "ERROR": stdlib_logging.ERROR,
    "CRITICAL": stdlib_logging.CRITICAL,
}


def _stdlib_level(log_level: str) -> int:
    """把配置中的级别名映射为标准库级别；未知名称拒绝。"""
    try:
        return _STDLIB_LEVELS[log_level.upper()]
    except KeyError:
        raise InvalidLogLevelError(log_level=log_level) from None


def _add_request_id(
    _logger: object,
    _method_name: str,
    event_dict: EventDict,
) -> EventDict:
    """把当前上下文的 requestId 并入事件；已有字段不覆盖。"""
    if MDC_KEY not in event_dict:
        request_id = get_request_id()
        if request_id is not None:
            event_dict[MDC_KEY] = request_id
    return event_dict


def configure_logging(*, log_level: str = "INFO", stream: TextIO | None = None) -> None:
    """配置 structlog：JSON 输出、级别过滤，并把 requestId 写入日志上下文。"""
    output = sys.stdout if stream is None else stream
    structlog.reset_defaults()
    structlog.configure(
        processors=[
            _add_request_id,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(_stdlib_level(log_level)),
        logger_factory=structlog.PrintLoggerFactory(file=output),
        cache_logger_on_first_use=False,
    )


def log_access(event: AccessLogEvent) -> None:
    """记录一条访问完成事件，字段与 Java RequestLoggingFilter 对齐。"""
    logger = structlog.get_logger("f1_predict.access")
    logger.info(
        "HTTP 请求完成",
        method=event.method,
        path=event.path,
        status=event.status,
        durationMs=event.duration_ms,
        requestId=get_request_id(),
    )
