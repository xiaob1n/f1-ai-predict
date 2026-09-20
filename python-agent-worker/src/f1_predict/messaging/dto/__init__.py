"""RabbitMQ 预测消息 DTO：请求、结果、进度与失败。

属性 snake_case，线上 JSON camelCase；本包不建立队列连接。
"""

from f1_predict.messaging.dto.failure import PredictionFailureMessage
from f1_predict.messaging.dto.progress import PredictionProgressMessage
from f1_predict.messaging.dto.request import (
    PredictionRequestMessage,
    QuestionOptionPayload,
    QuestionPayload,
    RaceContext,
)
from f1_predict.messaging.dto.result import (
    EvidenceRef,
    PredictionResultMessage,
    SelectedOption,
)

__all__ = [
    "EvidenceRef",
    "PredictionFailureMessage",
    "PredictionProgressMessage",
    "PredictionRequestMessage",
    "PredictionResultMessage",
    "QuestionOptionPayload",
    "QuestionPayload",
    "RaceContext",
    "SelectedOption",
]
