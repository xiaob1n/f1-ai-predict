"""RabbitMQ 预测消息 DTO：请求、结果、进度与失败。

属性 snake_case，线上 JSON camelCase；本包不建立队列连接。
"""

from f1_predict.messaging.dto.failure import PredictionFailureMessage
from f1_predict.messaging.dto.failure_v2 import (
    PredictionFailureCode,
    PredictionFailureV2,
)
from f1_predict.messaging.dto.progress import PredictionProgressMessage
from f1_predict.messaging.dto.request import (
    PredictionRequestMessage,
    QuestionOptionPayload,
    QuestionPayload,
    RaceContext,
)
from f1_predict.messaging.dto.request_v2 import PredictionRequestV2
from f1_predict.messaging.dto.result import (
    EvidenceRef,
    PredictionResultMessage,
    SelectedOption,
)
from f1_predict.messaging.dto.result_v2 import (
    EvidenceSourceV2,
    PredictionResultV2,
    SelectedOptionV2,
)

__all__ = [
    "EvidenceRef",
    "EvidenceSourceV2",
    "PredictionFailureCode",
    "PredictionFailureMessage",
    "PredictionFailureV2",
    "PredictionProgressMessage",
    "PredictionRequestMessage",
    "PredictionRequestV2",
    "PredictionResultMessage",
    "PredictionResultV2",
    "QuestionOptionPayload",
    "QuestionPayload",
    "RaceContext",
    "SelectedOption",
    "SelectedOptionV2",
]
