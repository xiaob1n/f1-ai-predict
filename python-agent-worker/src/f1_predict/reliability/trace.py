"""追踪汇：把预测任务阶段写成结构化日志，不写数据库或网络。"""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Callable
from datetime import UTC, datetime
from types import TracebackType
from typing import ClassVar, Self, override

import structlog
from pydantic import BaseModel, ConfigDict, StrictStr


class TraceRecord(BaseModel):
    """一条追踪事件的输入模型。

    字段用 StrictStr，避免 Pydantic 把 int 等非字符串隐式转成 phase。
    """

    model_config: ClassVar[ConfigDict] = ConfigDict(frozen=True, extra="forbid")

    trace_id: StrictStr
    """跨服务追踪标识。"""

    message_id: StrictStr
    """当前消息标识。"""

    prediction_job_id: StrictStr
    """预测任务业务幂等键。"""

    phase: StrictStr
    """当前阶段名，必须是字符串。"""

    occurred_at: datetime
    """事件发生时间；本阶段不额外约束时区策略。"""


class TraceSpan:
    """一次追踪阶段的句柄。

    可变是为了记录关闭状态：close 必须幂等，避免重复写日志。
    """

    def __init__(
        self,
        record: TraceRecord,
        emit: Callable[[TraceRecord], None],
    ) -> None:
        """绑定待写出的记录与写出回调。"""
        self._record: TraceRecord = record
        self._emit: Callable[[TraceRecord], None] = emit
        self._closed: bool = False

    def close(self) -> None:
        """关闭跨度并写出追踪日志；重复调用无副作用。"""
        if self._closed:
            return
        self._closed = True
        self._emit(self._record)

    def __enter__(self) -> Self:
        """作为上下文管理器进入时返回自身。"""
        return self

    def __exit__(
        self,
        _exc_type: type[BaseException] | None,
        _exc: BaseException | None,
        _tb: TracebackType | None,
    ) -> None:
        """离开 with 时关闭；不吞掉业务异常。"""
        self.close()


class TraceSink(ABC):
    """追踪汇抽象基类。

    统一记录 trace_id / message_id / prediction_job_id / phase。
    """

    @abstractmethod
    def start_span(
        self,
        *,
        trace_id: str,
        message_id: str,
        prediction_job_id: str,
        phase: str,
    ) -> TraceSpan:
        """开启一个阶段跨度，返回可 close 的上下文句柄。"""

    @abstractmethod
    def record(self, record: TraceRecord) -> None:
        """记录一条结构化追踪事件。"""


class ConsoleTraceSink(TraceSink):
    """阶段一追踪汇：只写 structlog，不访问数据库或网络。"""

    @override
    def start_span(
        self,
        *,
        trace_id: str,
        message_id: str,
        prediction_job_id: str,
        phase: str,
    ) -> TraceSpan:
        """构造 TraceRecord 并返回可关闭句柄；关闭时复用 record。"""
        record = TraceRecord(
            trace_id=trace_id,
            message_id=message_id,
            prediction_job_id=prediction_job_id,
            phase=phase,
            occurred_at=datetime.now(UTC),
        )
        return TraceSpan(record=record, emit=self.record)

    @override
    def record(self, record: TraceRecord) -> None:
        """把追踪字段写入 structlog，供控制台/JSON 渲染。"""
        logger = structlog.get_logger("f1_predict.reliability")
        logger.info(
            "追踪事件",
            trace_id=record.trace_id,
            message_id=record.message_id,
            prediction_job_id=record.prediction_job_id,
            phase=record.phase,
            occurred_at=record.occurred_at,
        )
