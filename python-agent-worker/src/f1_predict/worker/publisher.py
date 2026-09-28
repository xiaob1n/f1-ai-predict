"""结果 outbox 的至少一次投递：确认路由后才允许本地标记完成。"""

from __future__ import annotations

import aio_pika
from pamqp.commands import Basic

from f1_predict.common.config import Settings


class ResultPublisher:
    """使用独立开启 confirm 和 mandatory return 的 AMQP channel。"""

    def __init__(self, channel: aio_pika.abc.AbstractChannel, settings: Settings) -> None:
        self.channel = channel
        self.settings = settings

    async def publish(self, *, outcome_type: str, payload_json: str, message_id: str) -> None:
        """任何 nack/return/超时均抛错，SQLite 中的事件保持待发送。"""
        if outcome_type == "RESULT":
            exchange_name = self.settings.result_exchange
            routing_key = self.settings.result_routing_key
        elif outcome_type == "FAILURE":
            exchange_name = self.settings.failure_exchange
            routing_key = self.settings.failure_routing_key
        else:
            raise ValueError("unknown outcome type")
        body = payload_json.encode("utf-8")
        limit = (self.settings.result_max_message_bytes if outcome_type == "RESULT"
                 else self.settings.failure_max_message_bytes)
        if len(body) > limit:
            raise ValueError("outcome exceeds message size limit")
        exchange = await self.channel.get_exchange(exchange_name, ensure=True)
        confirmation = await exchange.publish(
            aio_pika.Message(
                body=body,
                message_id=message_id,
                content_type="application/json",
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
            ),
            routing_key=routing_key,
            mandatory=True,
            timeout=10,
        )
        if not isinstance(confirmation, Basic.Ack):
            raise ConnectionError("broker did not confirm the outcome")
