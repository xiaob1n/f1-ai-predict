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
from f1_predict.prediction.data import load_lap_fixture
from f1_predict.prediction.model_calls import ModelCallLedger
from f1_predict.prediction.model_http import LocalJsonModel
from f1_predict.prediction.model_vendor_api import (
    DeepSeekConfig,
    DeepSeekModel,
    create_openai_sdk_transport,
)
from f1_predict.prediction.policy import BothAdvanceQ1Policy, load_policy
from f1_predict.prediction.processor import PredictionProcessor
from f1_predict.prediction.replay_context import ReplayContext
from f1_predict.prediction.replay_model import ReplayBaselineModel
from f1_predict.reliability.idempotency import InboxStore
from f1_predict.worker.consumer import process_delivery
from f1_predict.worker.publisher import ResultPublisher

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
    """数据库处理失败时保留未确认消息并通知上层重连。"""
    logger.info("_consume 启动: queue=%s", queue.name)
    loop = asyncio.get_running_loop()
    delivery_failure: asyncio.Future[Exception] = loop.create_future()

    async def on_message(message: aio_pika.abc.AbstractIncomingMessage) -> None:
        """只由 process_delivery 确认或隔离消息，存储异常保持未确认。"""
        logger.info("收到消息: delivery_tag=%s, body_size=%d",
                    message.delivery_tag, len(message.body))
        if stop.is_set():
            logger.info("stop信号已设置，保持消息未确认并准备关闭")
            return
        try:
            await process_delivery(message, store, settings)
        except Exception as error:
            logger.exception("消息持久化失败，关闭消费连接以触发未确认消息重投")
            if not delivery_failure.done():
                delivery_failure.set_result(error)
            return
        logger.info("消息处理完成: delivery_tag=%s", message.delivery_tag)

    consumer_tag = await queue.consume(on_message, no_ack=False)
    logger.info("消费者已启动: consumer_tag=%s", consumer_tag)
    stop_task = asyncio.create_task(stop.wait())
    try:
        done, _ = await asyncio.wait(
            (stop_task, delivery_failure), return_when=asyncio.FIRST_COMPLETED
        )
        if delivery_failure in done:
            raise delivery_failure.result()
        logger.info("收到停止信号，准备关闭消费者")
    finally:
        stop_task.cancel()
        await asyncio.gather(stop_task, return_exceptions=True)
        if not delivery_failure.done():
            await queue.cancel(consumer_tag)
            logger.info("消费者已关闭")


async def _process_loop(processor: PredictionProcessor, stop: asyncio.Event) -> None:
    """定时扫描 inbox；请求已 ACK 后的恢复不依赖新消息。"""
    while not stop.is_set():
        processed = await processor.process_once()
        if not processed:
            try:
                await asyncio.wait_for(stop.wait(), timeout=1)
            except TimeoutError:
                pass


async def _publish_loop(
    store: InboxStore, publisher: ResultPublisher, stop: asyncio.Event
) -> None:
    """确认不明时保持事件待发，重连后使用原消息 ID 重发。"""
    while not stop.is_set():
        pending = await asyncio.to_thread(store.pending_outbox, 20)
        for event in pending:
            await publisher.publish(
                outcome_type=event.outcome_type,
                payload_json=event.payload_json,
                message_id=json.loads(event.payload_json)["messageId"],
            )
            await asyncio.to_thread(store.mark_outbox_published, event.event_id)
        if not pending:
            try:
                await asyncio.wait_for(stop.wait(), timeout=1)
            except TimeoutError:
                pass


def build_deepseek_model(settings: Settings) -> DeepSeekModel:
    """根据显式启用的配置构造有界厂商模型与持久调用账本。"""
    if settings.model_provider != "deepseek":
        raise ValueError("DeepSeek model construction requires model_provider=deepseek")
    if not settings.vendor_api_enabled:
        raise ValueError("DeepSeek requires vendor_api_enabled=true")
    if settings.deepseek_api_key is None or not settings.deepseek_api_key.get_secret_value().strip():
        raise ValueError("DeepSeek API key is required")

    config = DeepSeekConfig(
        api_key=settings.deepseek_api_key.get_secret_value(),
        model=settings.deepseek_model,
        timeout_seconds=settings.model_timeout_seconds,
    )
    ledger = ModelCallLedger(
        settings.deepseek_ledger_path,
        max_calls=settings.deepseek_max_calls,
        call_reserve_microunits=settings.deepseek_reservation_micro_usd,
        total_budget_microunits=settings.deepseek_max_cost_micro_usd,
    )
    transport = create_openai_sdk_transport(config)
    return DeepSeekModel(config, transport=transport, ledger=ledger)


def build_prediction_processor(
    settings: Settings,
    store: InboxStore,
    *,
    vendor_model: DeepSeekModel | None = None,
) -> PredictionProcessor:
    """显式构造离线处理器；DeepSeek 仅接受调用方注入的已建模型。"""
    if not settings.prediction_enabled:
        raise ValueError("prediction processor requires prediction_enabled")
    # C阶段已获批准 (deepseek-flash, 无上限预算, 2026-10-05)
    # if settings.vendor_api_enabled:
    #     raise ValueError("vendor API calls remain blocked until separately approved stage C")
    if settings.model_provider == "deepseek":
        if vendor_model is None:
            raise ValueError("deepseek requires an explicit DeepSeekModel to be injected for offline processing")
        if not isinstance(vendor_model, DeepSeekModel):
            raise ValueError("vendor_model must be a DeepSeekModel")
        ledger = vendor_model.ledger
        if (
            vendor_model.config.model != settings.deepseek_model
            or vendor_model.config.model != settings.model_version
            or vendor_model.config.timeout_seconds != settings.model_timeout_seconds
            or Path(ledger.path).expanduser().resolve(strict=False)
            != Path(settings.deepseek_ledger_path).expanduser().resolve(strict=False)
            or ledger.max_calls != settings.deepseek_max_calls
            or ledger.call_reserve_microunits != settings.deepseek_reservation_micro_usd
            or ledger.total_budget_microunits != settings.deepseek_max_cost_micro_usd
        ):
            raise ValueError("DeepSeek model and ledger settings differ from the frozen configuration")
    elif vendor_model is not None:
        raise ValueError("vendor_model is only valid for the deepseek provider")
    replay_stub = (
        settings.prediction_execution_mode == "HISTORICAL_ENGINEERING_REPLAY"
        and settings.prediction_replay_model_mode == "stub"
    )
    if not settings.prediction_policy_path or not settings.prediction_laps_path:
        raise ValueError("enabled prediction requires policy and audited laps")
    if settings.model_provider == "local" and not replay_stub and not settings.model_url:
        raise ValueError("enabled local prediction requires a model endpoint")
    policy = load_policy(settings.prediction_policy_path)
    replay_context = None
    if settings.prediction_execution_mode == "HISTORICAL_ENGINEERING_REPLAY":
        if isinstance(policy, BothAdvanceQ1Policy) or policy is None:
            raise ValueError("historical replay requires a single-question snapshot policy")
        replay_context = ReplayContext.load(
            settings.prediction_replay_manifest_path,
            settings.prediction_laps_path,
            settings.prediction_policy_path,
            model_mode=settings.prediction_replay_model_mode,
            expected_manifest_hash=settings.prediction_replay_manifest_hash,
        )
    supported_versions = (
        ("prompt-h2h-replay-v1", "feature-v1")
        if replay_context is not None
        else ("prompt-q1-v1", "feature-q1-v1")
        if isinstance(policy, BothAdvanceQ1Policy)
        else ("prompt-v1", "feature-v1")
    )
    if (
        policy is None
        or policy.model_version != settings.model_version
        or policy.prompt_version != settings.prompt_version
        or policy.feature_version != settings.feature_version
        or (policy.prompt_version, policy.feature_version) != supported_versions
    ):
        raise ValueError("configured versions must match the registered snapshot policy")
    if settings.model_provider == "deepseek":
        assert vendor_model is not None
        model = vendor_model
        max_attempts = min(settings.model_max_attempts, settings.deepseek_max_calls)
    else:
        model = (
            ReplayBaselineModel()
            if replay_stub
            else LocalJsonModel(
                settings.model_url, settings.model_version, settings.model_timeout_seconds
            )
        )
        max_attempts = settings.model_max_attempts
    return PredictionProcessor(
        store,
        load_lap_fixture(settings.prediction_laps_path),
        model,
        policy,
        max_attempts=max_attempts,
        lease_seconds=max(90, int(settings.model_timeout_seconds) + 30),
        replay_context=replay_context,
    )


async def run(settings: Settings) -> None:
    """短连接失败退避重试；SQLite 错误会先关连接使未 ACK 消息重投。"""
    # C阶段已获批准 (deepseek-flash, 无上限预算, 2026-10-05) - workflow已恢复门控用于测试隔离
    # 当前任务：手动移除以允许真实验收
    # if settings.model_provider == "deepseek" or settings.vendor_api_enabled:
    #     raise ValueError("DeepSeek worker startup is blocked before stage C approval")
    if not settings.rabbitmq_url or not settings.consumer_sqlite_path:
        raise ValueError("consumer requires RabbitMQ URL and persistent SQLite path")
    store = InboxStore(settings.consumer_sqlite_path)
    processor: PredictionProcessor | None = None
    vendor_model: DeepSeekModel | None = None
    if settings.prediction_enabled:
        # 当使用DeepSeek时，先构造模型再传给processor
        if settings.model_provider == "deepseek":
            from f1_predict.prediction.model_calls import ModelCallLedger
            from f1_predict.prediction.model_vendor_api import (
                DeepSeekConfig,
                OpenAISDKDeepSeekTransport,
            )
            if not settings.deepseek_api_key:
                raise ValueError("deepseek_api_key is required for DeepSeek model")
            config = DeepSeekConfig(
                api_key=settings.deepseek_api_key,
                model=settings.deepseek_model,
                timeout_seconds=settings.model_timeout_seconds,
            )
            transport = OpenAISDKDeepSeekTransport(config)
            transport.prepare()
            ledger = ModelCallLedger(
                path=settings.deepseek_ledger_path,
                max_calls=settings.deepseek_max_calls,
                call_reserve_microunits=settings.deepseek_reservation_micro_usd,
                total_budget_microunits=settings.deepseek_max_cost_micro_usd,
            )
            vendor_model = DeepSeekModel(config, transport=transport, ledger=ledger)
        processor = build_prediction_processor(settings, store, vendor_model=vendor_model)
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
            logger.info("RabbitMQ连接成功")
            channel = await connection.channel(publisher_confirms=False)
            logger.info("Channel创建成功")
            await channel.set_qos(prefetch_count=settings.consumer_prefetch)
            logger.info("QoS设置完成: prefetch_count=%d", settings.consumer_prefetch)
            queue = await declare_topology(channel, settings)
            logger.info("拓扑声明完成: queue=%s", queue.name)
            result_channel = await connection.channel(
                publisher_confirms=True, on_return_raises=True
            )
            publisher = ResultPublisher(result_channel, settings)
            heartbeat = asyncio.create_task(_heartbeat(health, beat_stop, connection))
            health.write(True)
            failures = 0
            logger.info("准备启动消费任务")
            activities = [
                asyncio.create_task(_consume(queue, store, settings, stop)),
            ]
            logger.info("消费任务已创建")
            if processor is not None:
                logger.info("启用processor相关任务")
                activities.append(asyncio.create_task(_publish_loop(store, publisher, stop)))
                activities.append(asyncio.create_task(_process_loop(processor, stop)))
            logger.info("所有活动任务已创建，开始等待: activities_count=%d", len(activities))
            try:
                done, _ = await asyncio.wait(
                    activities, return_when=asyncio.FIRST_COMPLETED
                )
                for task in done:
                    task.result()
                if not stop.is_set():
                    raise ConnectionError("worker activity stopped unexpectedly")
            finally:
                for task in activities:
                    task.cancel()
                await asyncio.gather(*activities, return_exceptions=True)
        except (Exception, asyncio.CancelledError) as error:
            if isinstance(error, asyncio.CancelledError):
                raise
            logger.error("消费者暂不可用: errorType=%s", type(error).__name__)
            logger.exception("详细错误信息")  # 临时调试
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
    # 当 consumer_sqlite_path 为空时，使用默认路径
    default_sqlite = settings.consumer_sqlite_path or "/tmp/f1_worker_inbox.db"
    health_path = Path(
        settings.consumer_health_path
        or str(Path(default_sqlite).with_suffix(".health.json"))
    )
    if "--check-ready" in sys.argv or "--check-live" in sys.argv:
        if not HealthFile(health_path).check(ready="--check-ready" in sys.argv):
            raise SystemExit(1)
        return
    logging.basicConfig(level=settings.log_level)
    asyncio.run(run(settings))


if __name__ == "__main__":
    main()
