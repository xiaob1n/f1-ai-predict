"""预测失败消息：只携带安全摘要，禁止堆栈、连接串与 URL。"""

from __future__ import annotations

from pydantic import Field

from f1_predict.messaging.dto.base import PredictionMessageEnvelope


class PredictionFailureMessage(PredictionMessageEnvelope):
    """Python 确定无法完成任务时发布的失败消息。"""

    phase: str = Field(alias="phase")
    error_code: str = Field(alias="errorCode")
    error_message: str = Field(alias="errorMessage")
    retryable: bool = Field(alias="retryable")
    attempt: int = Field(alias="attempt")
    max_retries: int = Field(alias="maxRetries")
    worker_node: str = Field(alias="workerNode")
