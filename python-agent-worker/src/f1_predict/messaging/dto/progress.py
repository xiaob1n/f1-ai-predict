"""预测进度消息：长任务阶段切换的 best-effort 通知，不作为业务事实。"""

from __future__ import annotations

from pydantic import Field

from f1_predict.messaging.dto.base import PredictionMessageEnvelope, UtcDateTime


class PredictionProgressMessage(PredictionMessageEnvelope):
    """Python 在阶段切换时发布的进度事件。"""

    phase: str = Field(alias="phase")
    progress: float = Field(alias="progress")
    worker_node: str = Field(alias="workerNode")
    message: str = Field(alias="message")
    started_at: UtcDateTime = Field(alias="startedAt")
