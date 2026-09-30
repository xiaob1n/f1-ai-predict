"""只向隔离 RabbitMQ 重投原始 outbox 消息，用于核查双端去重。"""

from __future__ import annotations

import asyncio
import json
import os
import re
import sys

import aio_pika
from run import E2EError, credentials, sql_query, state, verify_containers


async def main() -> None:
    # 独立执行时也必须核验本次容器及 URL，不能借用宿主生产 worker 的连接值。
    value = state()
    if value.get("schema") != "ready":
        raise E2EError("隔离数据库尚未初始化")
    verify_containers(value)
    _, password = credentials()
    expected_url = f"amqp://e2e:{password}@127.0.0.1:15673/e2e"
    if os.environ.get("F1_PREDICT_RABBITMQ_URL") != expected_url:
        raise E2EError("仅允许连接当前隔离项目的 RabbitMQ")
    payload = json.load(sys.stdin)
    if set(payload) != {"messageId", "body"}:
        raise E2EError("仅接受原始消息身份和载荷")
    message_id, body = payload["messageId"], payload["body"]
    if not isinstance(message_id, str) or not isinstance(body, str) or len(body) > 262144:
        raise E2EError("原始消息身份或载荷无效")
    if re.fullmatch(r"[A-Za-z0-9_-]{1,128}", message_id) is None:
        raise E2EError("消息 ID 无效")
    rows = sql_query(value, f"SELECT payload_json FROM prediction_request_outbox WHERE message_id='{message_id}'")
    if rows != [[body]]:
        raise E2EError("消息不属于当前隔离库的原始 outbox")
    connection = await aio_pika.connect_robust(expected_url)
    try:
        channel = await connection.channel(publisher_confirms=True, on_return_raises=True)
        exchange = await channel.get_exchange("f1.prediction.request.v2", ensure=True)
        await exchange.publish(
            aio_pika.Message(
                body=payload["body"].encode("utf-8"),
                message_id=payload["messageId"],
                content_type="application/json",
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
            ),
            routing_key="prediction.request.v2",
            mandatory=True,
        )
    finally:
        await connection.close()


if __name__ == "__main__":
    asyncio.run(main())
