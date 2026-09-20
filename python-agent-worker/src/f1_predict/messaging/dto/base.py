"""消息 DTO 基类：统一 camelCase 别名、禁止未知字段、UTC ``Z`` 时间。"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Annotated, ClassVar, Literal

from pydantic import (
    AfterValidator,
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    PlainSerializer,
)


def _to_utc(value: datetime) -> datetime:
    """把任意时区感知时间归一到 UTC，供模型内部持有。"""
    return value.astimezone(UTC)


def _dump_utc_z(value: datetime) -> str:
    """线上 JSON 使用 ISO 8601 的 ``Z`` 后缀，禁止 ``+00:00``。"""
    utc_value = value.astimezone(UTC)
    timespec = "microseconds" if utc_value.microsecond else "seconds"
    return utc_value.replace(tzinfo=None).isoformat(timespec=timespec) + "Z"


# 所有消息时间字段共用：拒绝 naive、入站归一 UTC、JSON 输出 Z。
type UtcDateTime = Annotated[
    AwareDatetime,
    AfterValidator(_to_utc),
    PlainSerializer(_dump_utc_z, return_type=str, when_used="json"),
]


class F1PredictBaseModel(BaseModel):
    """预测消息 DTO 基类。

    Python 属性 snake_case，线上 JSON 一律 camelCase；
    ``populate_by_name`` 允许两种名称入站，
    ``serialize_by_alias`` 保证 ``model_dump`` / ``model_dump_json`` 默认输出别名。
    """

    model_config: ClassVar[ConfigDict] = ConfigDict(
        populate_by_name=True,
        serialize_by_alias=True,
        extra="forbid",
        str_strip_whitespace=True,
    )


class PredictionMessageEnvelope(F1PredictBaseModel):
    """四类预测消息共享的追踪与协议信封。"""

    schema_version: Literal["1"] = Field(alias="schemaVersion")
    message_id: str = Field(alias="messageId")
    prediction_job_id: str = Field(alias="predictionJobId")
    batch_id: int = Field(alias="batchId")
    question_id: int = Field(alias="questionId")
    trace_id: str = Field(alias="traceId")
