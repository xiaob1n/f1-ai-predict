"""Worker 心跳与运行态。

阶段一返回完整设计字段，依赖类字段为占位：未连队列、未加载模型、无 GPU 读数。
"""

from __future__ import annotations

import socket
import time
from datetime import UTC, datetime
from typing import Annotated, Final, Literal

from fastapi import APIRouter
from fastapi.responses import JSONResponse
from pydantic import AfterValidator, AwareDatetime, Field, PlainSerializer

from f1_predict.api.errors import ApiJsonModel

router = APIRouter()

# 进程启动单调时钟，供 uptimeSeconds 计算，不受墙钟回拨影响。
_STARTED_MONOTONIC: Final[float] = time.monotonic()


def _to_utc(value: datetime) -> datetime:
    """把时区感知时间归一到 UTC。"""
    return value.astimezone(UTC)


def _dump_utc_z(value: datetime) -> str:
    """JSON 输出 ISO 8601 UTC，后缀为 Z。"""
    utc_value = value.astimezone(UTC)
    timespec = "microseconds" if utc_value.microsecond else "seconds"
    return utc_value.replace(tzinfo=None).isoformat(timespec=timespec) + "Z"


type UtcDateTime = Annotated[
    AwareDatetime,
    AfterValidator(_to_utc),
    PlainSerializer(_dump_utc_z, return_type=str, when_used="json"),
]


class GpuStatus(ApiJsonModel):
    """GPU 读数。阶段一未采集硬件，全部为 0。"""

    memory_used_bytes: int = Field(alias="memoryUsedBytes")
    """已用显存字节；阶段一占位 0。"""

    memory_total_bytes: int = Field(alias="memoryTotalBytes")
    """显存总量字节；阶段一占位 0。"""

    utilization: float = Field(alias="utilization")
    """利用率 0~1；阶段一占位 0.0。"""


class WorkerStatusResponse(ApiJsonModel):
    """Worker 状态响应，线上键一律 camelCase。"""

    worker_node: str = Field(alias="workerNode")
    """本机标识；hostname 失败时回退 local-dev。"""

    status: Literal["IDLE"] = Field(alias="status")
    """运行态。阶段一无消费循环，固定 IDLE。"""

    current_job_id: str | None = Field(alias="currentJobId")
    """当前任务；阶段一无线上任务，恒为 null。"""

    last_heartbeat: UtcDateTime = Field(alias="lastHeartbeat")
    """本次查询时刻的 UTC 心跳。"""

    uptime_seconds: int = Field(alias="uptimeSeconds")
    """自进程启动起的单调时钟秒数。"""

    gpu: GpuStatus = Field(alias="gpu")
    """GPU 占位读数。"""

    model_loaded: bool = Field(alias="modelLoaded")
    """模型是否已加载；阶段一未加载。"""

    rabbitmq_connected: bool = Field(alias="rabbitmqConnected")
    """队列是否已连接；阶段一未连接。"""

    queue_depth_estimate: int = Field(alias="queueDepthEstimate")
    """队列深度估计；阶段一占位 0。"""


def _worker_node() -> str:
    """读取本机 hostname，失败或空串时回退 local-dev。"""
    try:
        name = socket.gethostname()
    except OSError:
        return "local-dev"
    if not name:
        return "local-dev"
    return name


def _uptime_seconds() -> int:
    """用单调时钟计算存活秒数，下限为 0。"""
    return max(0, int(time.monotonic() - _STARTED_MONOTONIC))


@router.get("/api/v1/worker/status")
async def get_worker_status() -> JSONResponse:
    """返回 Worker 心跳与阶段一占位运行态。"""
    body = WorkerStatusResponse(
        workerNode=_worker_node(),
        status="IDLE",
        currentJobId=None,
        lastHeartbeat=datetime.now(UTC),
        uptimeSeconds=_uptime_seconds(),
        gpu=GpuStatus(
            memoryUsedBytes=0,
            memoryTotalBytes=0,
            utilization=0.0,
        ),
        modelLoaded=False,
        rabbitmqConnected=False,
        queueDepthEstimate=0,
    )
    return JSONResponse(content=body.model_dump(mode="json"))
