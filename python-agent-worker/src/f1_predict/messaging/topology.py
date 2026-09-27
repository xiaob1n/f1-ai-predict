"""RabbitMQ v2 请求队列及死信队列的持久拓扑。"""

from __future__ import annotations

import aio_pika

from f1_predict.common.config import Settings


async def declare_topology(
    channel: aio_pika.abc.AbstractChannel, settings: Settings
) -> aio_pika.abc.AbstractQueue:
    """显式声明双端约定的拓扑，参数不一致时让启动失败。"""
    request_exchange = await channel.declare_exchange(
        settings.request_exchange, aio_pika.ExchangeType.DIRECT, durable=True
    )
    dead_exchange = await channel.declare_exchange(
        settings.dead_letter_exchange, aio_pika.ExchangeType.DIRECT, durable=True
    )
    dead_queue = await channel.declare_queue(settings.dead_letter_queue, durable=True)
    await dead_queue.bind(dead_exchange, routing_key=settings.dead_letter_routing_key)
    request_queue = await channel.declare_queue(
        settings.request_queue,
        durable=True,
        arguments={
            "x-dead-letter-exchange": settings.dead_letter_exchange,
            "x-dead-letter-routing-key": settings.dead_letter_routing_key,
        },
    )
    await request_queue.bind(
        request_exchange, routing_key=settings.request_routing_key
    )
    return request_queue
