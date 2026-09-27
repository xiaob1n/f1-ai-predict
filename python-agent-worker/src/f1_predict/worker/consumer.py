"""持久消费：SQLite 提交后 ACK，坏消息隔离后拒绝进入 DLQ。"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Protocol

from pydantic import ValidationError

from f1_predict.common.config import Settings
from f1_predict.messaging.dto.request_v2 import PredictionRequestV2
from f1_predict.reliability.idempotency import InboxStore

logger = logging.getLogger(__name__)


class Delivery(Protocol):
    """只依赖手动 ACK 所需的 AMQP delivery 行为。"""

    body: bytes

    async def ack(self) -> None:
        """确认消息成功接收。"""
        ...

    async def reject(self, *, requeue: bool = False) -> None:
        """拒绝永久坏消息，交给死信交换机。"""
        ...


async def process_delivery(
    message: Delivery, store: InboxStore, settings: Settings
) -> None:
    """数据库故障向外传播，由调用方关 channel 让未确认消息重投。"""
    reason: str | None = None
    request: PredictionRequestV2 | None = None
    if len(message.body) > settings.consumer_max_message_bytes:
        reason = "oversized_message"
    else:
        try:
            envelope = json.loads(message.body)
            if not isinstance(envelope, dict):
                reason = "invalid_envelope"
            elif envelope.get("schemaVersion") != "2":
                reason = "unsupported_schema_version"
            else:
                request = PredictionRequestV2.model_validate(envelope)
        except (ValueError, UnicodeDecodeError, ValidationError):
            reason = "invalid_request"

    if request is not None:
        outcome = await asyncio.to_thread(store.save, request)
        if outcome in ("inserted", "duplicate"):
            await message.ack()
            return
        reason = "conflicting_prediction_job_id"

    assert reason is not None
    await asyncio.to_thread(
        store.quarantine, message.body, reason, settings.consumer_max_message_bytes
    )
    logger.warning("预测请求已隔离: reason=%s", reason)
    await message.reject(requeue=False)
