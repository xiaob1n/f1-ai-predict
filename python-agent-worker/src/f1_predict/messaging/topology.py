"""RabbitMQ v2 请求队列及死信队列的持久拓扑。"""

from __future__ import annotations

import aio_pika

from f1_predict.common.config import Settings


async def _declare_queue_with_dead_letter(
    channel: aio_pika.abc.AbstractChannel,
    *,
    exchange_name: str,
    queue_name: str,
    routing_key: str,
    dead_exchange_name: str,
    dead_queue_name: str,
    dead_routing_key: str,
) -> aio_pika.abc.AbstractQueue:
    """声明独立的持久 direct 路由及其死信队列。"""
    exchange = await channel.declare_exchange(
        exchange_name, aio_pika.ExchangeType.DIRECT, durable=True
    )
    dead_exchange = await channel.declare_exchange(
        dead_exchange_name, aio_pika.ExchangeType.DIRECT, durable=True
    )
    dead_queue = await channel.declare_queue(dead_queue_name, durable=True)
    await dead_queue.bind(dead_exchange, routing_key=dead_routing_key)
    queue = await channel.declare_queue(
        queue_name,
        durable=True,
        arguments={
            "x-dead-letter-exchange": dead_exchange_name,
            "x-dead-letter-routing-key": dead_routing_key,
        },
    )
    await queue.bind(exchange, routing_key=routing_key)
    return queue


async def declare_topology(
    channel: aio_pika.abc.AbstractChannel, settings: Settings
) -> aio_pika.abc.AbstractQueue:
    """显式声明持久请求、结果和失败拓扑，参数不一致时启动失败。"""
    request_queue = await _declare_queue_with_dead_letter(
        channel,
        exchange_name=settings.request_exchange,
        queue_name=settings.request_queue,
        routing_key=settings.request_routing_key,
        dead_exchange_name=settings.dead_letter_exchange,
        dead_queue_name=settings.dead_letter_queue,
        dead_routing_key=settings.dead_letter_routing_key,
    )
    await _declare_queue_with_dead_letter(
        channel,
        exchange_name=settings.result_exchange,
        queue_name=settings.result_queue,
        routing_key=settings.result_routing_key,
        dead_exchange_name=settings.result_dead_letter_exchange,
        dead_queue_name=settings.result_dead_letter_queue,
        dead_routing_key=settings.result_dead_letter_routing_key,
    )
    await _declare_queue_with_dead_letter(
        channel,
        exchange_name=settings.failure_exchange,
        queue_name=settings.failure_queue,
        routing_key=settings.failure_routing_key,
        dead_exchange_name=settings.failure_dead_letter_exchange,
        dead_queue_name=settings.failure_dead_letter_queue,
        dead_routing_key=settings.failure_dead_letter_routing_key,
    )
    return request_queue
