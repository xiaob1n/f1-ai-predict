"""存活与就绪检查。

阶段一不探测 RabbitMQ / MongoDB / Qdrant / 模型，就绪检查固定 503 DOWN。
"""

from __future__ import annotations

from typing import Literal

from fastapi import APIRouter
from fastapi.responses import JSONResponse
from pydantic import Field

from f1_predict.api.errors import ApiJsonModel

router = APIRouter()

_NOT_CONFIGURED: Literal["NOT_CONFIGURED"] = "NOT_CONFIGURED"


class LiveResponse(ApiJsonModel):
    """进程存活响应：不检查任何依赖。"""

    status: Literal["UP"] = Field(alias="status")
    """存活状态，进程能响应即 UP。"""


class ReadyChecks(ApiJsonModel):
    """就绪依赖项。阶段一全部为未配置占位。"""

    rabbitmq: Literal["NOT_CONFIGURED"] = Field(alias="rabbitmq")
    """RabbitMQ 连接；阶段一未接入。"""

    mongodb: Literal["NOT_CONFIGURED"] = Field(alias="mongodb")
    """本地 MongoDB；阶段一未接入。"""

    qdrant: Literal["NOT_CONFIGURED"] = Field(alias="qdrant")
    """本地 Qdrant；阶段一未接入。"""

    model: Literal["NOT_CONFIGURED"] = Field(alias="model")
    """模型加载；阶段一未接入。"""


class ReadyResponse(ApiJsonModel):
    """就绪检查响应。阶段一固定 DOWN。"""

    status: Literal["DOWN"] = Field(alias="status")
    """整体就绪状态。"""

    checks: ReadyChecks = Field(alias="checks")
    """分项检查结果。"""


@router.get("/health/live", response_model=LiveResponse)
async def health_live() -> LiveResponse:
    """进程存活即返回 200 UP。"""
    return LiveResponse(status="UP")


@router.get("/health/ready")
async def health_ready() -> JSONResponse:
    """依赖未配置时返回 503 DOWN，避免被当成已就绪。"""
    body = ReadyResponse(
        status="DOWN",
        checks=ReadyChecks(
            rabbitmq=_NOT_CONFIGURED,
            mongodb=_NOT_CONFIGURED,
            qdrant=_NOT_CONFIGURED,
            model=_NOT_CONFIGURED,
        ),
    )
    return JSONResponse(status_code=503, content=body.model_dump(mode="json"))
