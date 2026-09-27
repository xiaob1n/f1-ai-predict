"""独立 RabbitMQ 消费进程入口与进程自身的健康探针。"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import random
import signal
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

import aio_pika

from f1_predict.common.config import Settings
from f1_predict.messaging.topology import declare_topology
from f1_predict.reliability.idempotency import InboxStore
from f1_predict.worker.consumer import process_delivery

logger = logging.getLogger(__name__)


class HealthFile:
    """隔离于 HTTP 进程的心跳；崩溃后过期自动判为不健康。"""

    def __init__(self, path: Path) -> None:
        if not path.parent.is_dir():
            raise ValueError("health file parent directory does not exist")
        self.path = path

    def write(self, ready: bool) -> None:
        """原子更新当前进程的就绪心跳。"""
        temporary = self.path.with_name(self.path.name + f".{os.getpid()}.tmp")
        temporary.write_text(
            json.dumps({"ready": ready, "at": datetime.now(UTC).isoformat()}),
            encoding="utf-8",
        )
        os.replace(temporary, self.path)

    def check(self, *, ready: bool) -> bool:
        """只接受近期心跳；就绪探针还要求 Broker 连接正常。"""
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            instant = datetime.fromisoformat(data["at"])
            return (
                instant.tzinfo is not None
                and datetime.now(UTC) - timedelta(seconds=20)
                <= instant
                <= datetime.now(UTC) + timedelta(seconds=2)
                and (not ready or data["ready"] is True)
            )
        except (OSError, ValueError, KeyError, TypeError):
            return False


async def _heartbeat(health: HealthFile, stop: asyncio.Event, connection: aio_pika.Connection) -> None:
    while not stop.is_set():
        health.write(not connection.is_closed)
        try:
            await asyncio.wait_for(stop.wait(), timeout=5)
        except TimeoutError:
            pass


async def _consume(
    queue: aio_pika.abc.AbstractQueue,
    store: InboxStore,
    settings: Settings,
    stop: asyncio.Event,
) -> None:
    async with queue.iterator(no_ack=False) as iterator:
        async def close_on_stop() -> None:
            await stop.wait()
            await asyncio.wait_for(
                iterator.close(), timeout=settings.consumer_shutdown_seconds
            )

        watcher = asyncio.create_task(close_on_stop())
        try:
            async for message in iterator:
                if stop.is_set():
                    break
                await process_delivery(message, store, settings)
        finally:
            watcher.cancel()
            await asyncio.gather(watcher, return_exceptions=True)


async def run(settings: Settings) -> None:
    """短连接失败退避重试；SQLite 错误会先关连接使未 ACK 消息重投。"""
    if not settings.rabbitmq_url or not settings.consumer_sqlite_path:
        raise ValueError("consumer requires RabbitMQ URL and persistent SQLite path")
    store = InboxStore(settings.consumer_sqlite_path)
    health_path = Path(
        settings.consumer_health_path
        or str(Path(settings.consumer_sqlite_path).with_suffix(".health.json"))
    )
    health = HealthFile(health_path)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(signum, stop.set)
    health.write(False)
    failures = 0
    while not stop.is_set():
        connection: aio_pika.Connection | None = None
        heartbeat: asyncio.Task[None] | None = None
        beat_stop = asyncio.Event()
        try:
            connection = await aio_pika.connect(settings.rabbitmq_url, timeout=10)
            channel = await connection.channel(publisher_confirms=False)
            await channel.set_qos(prefetch_count=settings.consumer_prefetch)
            queue = await declare_topology(channel, settings)
            heartbeat = asyncio.create_task(_heartbeat(health, beat_stop, connection))
            health.write(True)
            failures = 0
            await _consume(queue, store, settings, stop)
        except (Exception, asyncio.CancelledError) as error:
            if isinstance(error, asyncio.CancelledError):
                raise
            logger.error("消费者暂不可用: errorType=%s", type(error).__name__)
            failures += 1
        finally:
            health.write(False)
            beat_stop.set()
            if heartbeat is not None:
                await asyncio.gather(heartbeat, return_exceptions=True)
            if connection is not None and not connection.is_closed:
                await connection.close()
        if not stop.is_set():
            delay = min(60.0, settings.consumer_reconnect_seconds * 2 ** min(failures, 6))
            try:
                await asyncio.wait_for(stop.wait(), delay + random.uniform(0, delay * 0.2))
            except TimeoutError:
                pass


def main() -> None:
    """启动独立消费者或执行文件心跳健康检查。"""
    settings = Settings()
    health_path = Path(
        settings.consumer_health_path
        or str(Path(settings.consumer_sqlite_path).with_suffix(".health.json"))
    )
    if "--check-ready" in sys.argv or "--check-live" in sys.argv:
        if not HealthFile(health_path).check(ready="--check-ready" in sys.argv):
            raise SystemExit(1)
        return
    logging.basicConfig(level=settings.log_level)
    asyncio.run(run(settings))


if __name__ == "__main__":
    main()
