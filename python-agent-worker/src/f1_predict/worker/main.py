"""独立 RabbitMQ 消费进程入口与进程自身的健康探针。"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import random
import re
import signal
import sys
from datetime import UTC, datetime, timedelta
from importlib import metadata
from pathlib import Path

import aio_pika
from pydantic import SecretStr

from f1_predict.common.config import Settings
from f1_predict.features.repository import InMemoryLapRepository
from f1_predict.messaging.topology import declare_topology
from f1_predict.prediction.data import load_lap_fixture
from f1_predict.prediction.model import ModelGateway
from f1_predict.prediction.model_calls import ModelCallLedger, inspect_ledger
from f1_predict.prediction.model_http import LocalJsonModel
from f1_predict.prediction.model_vendor_api import (
    DeepSeekConfig,
    DeepSeekModel,
    create_openai_sdk_transport,
)
from f1_predict.prediction.policy import (
    BothAdvanceQ1Policy,
    SnapshotPolicy,
    load_policy,
)
from f1_predict.prediction.processor import PredictionProcessor
from f1_predict.prediction.replay_context import ReplayContext
from f1_predict.prediction.replay_model import ReplayBaselineModel
from f1_predict.reliability.idempotency import InboxStore
from f1_predict.worker.consumer import process_delivery
from f1_predict.worker.publisher import ResultPublisher

logger = logging.getLogger(__name__)

_USAGE = """用法: python -m f1_predict.worker.main [--preflight [--init-ledger] | --check-live | --check-ready | --help]

  (无参数)       启动独立 RabbitMQ 消费者；配置全部来自 F1_PREDICT_* 环境变量。
                 DeepSeek 模式下账本不存在时会按当前配置创建，并冻结预算与“一账本一任务”绑定。
  --preflight    DeepSeek 真实前向的不联网预检：不连接 Broker、不建 inbox、不发送厂商请求。
                 默认只读：账本存在时只读核对持久配置且要求没有调用记录；账本不存在时只校验目录，
                 不创建账本，并在输出中报告 ledgerInitialized=false。
                 报告已装 SDK 版本与 deepseek 额外依赖锁定是否一致（matchesLockedPins），不一致只警告。
  --init-ledger  仅与 --preflight 同用：预检通过后在账本不存在时创建空账本，并持久冻结预算与绑定。
  --check-live   读取健康文件判断进程存活。
  --check-ready  读取健康文件判断 Broker 就绪。
  --help, -h     显示本说明，不读取任何配置。"""
"""命令行说明；预检的本地写入副作用必须在此显式告知。"""

_VENDOR_MAX_DISTINCT_JOBS = 1
"""真实厂商入口的账本固定只服务一个预测任务，不开放配置。"""

_OPENAI_SDK_VERSION = "2.11.0"
"""与 pyproject deepseek 额外依赖锁定一致；仅预检核对，运行时不收窄 pyproject 声明的范围。"""


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


def _require_vendor_sdk() -> None:
    """运行时只确认适配器所需 SDK 符号可导入；版本范围以 pyproject 声明为准，不创建客户端、不联网。"""
    try:
        from openai import APIConnectionError, APIStatusError, AsyncOpenAI  # noqa: F401
    except ImportError as error:
        raise RuntimeError("DeepSeek requires the openai SDK with the adapter's API surface") from error


def inspect_vendor_sdk_versions() -> tuple[dict[str, str], bool]:
    """预检专用：只读包元数据，报告是否与 deepseek 额外依赖的精确锁定一致；不导入 SDK、不联网、不拒绝。"""
    versions: dict[str, str] = {}
    for name in ("openai", "httpx"):
        try:
            versions[name] = metadata.version(name)
        except metadata.PackageNotFoundError:
            versions[name] = "UNKNOWN"
    httpx_match = re.fullmatch(r"0\.28\.(\d+)", versions["httpx"])
    matches = (
        versions["openai"] == _OPENAI_SDK_VERSION
        and httpx_match is not None
        and int(httpx_match.group(1)) >= 1
    )
    return versions, matches


def _require_vendor_gate(settings: Settings) -> SecretStr:
    """厂商门禁：provider、开关、完整重验、密钥与 SDK 符号；不创建任何文件或客户端。"""
    if settings.model_provider != "deepseek":
        raise ValueError("DeepSeek model construction requires model_provider=deepseek")
    if not settings.vendor_api_enabled:
        raise ValueError("DeepSeek requires vendor_api_enabled=true")
    # Settings 允许赋值覆盖且不重新校验；此处按当前值完整重验（不重读环境），防止绕过预算、审批与模式约束。
    Settings.model_validate({**settings.model_dump(), "deepseek_api_key": settings.deepseek_api_key})
    if settings.deepseek_api_key is None or not settings.deepseek_api_key.get_secret_value().strip():
        raise ValueError("DeepSeek API key is required")
    _require_vendor_sdk()
    return settings.deepseek_api_key


def build_deepseek_model(settings: Settings) -> DeepSeekModel:
    """厂商模型唯一构造入口：门禁全部通过后才建账本与惰性传输，构造期间不联网。"""
    api_key = _require_vendor_gate(settings)
    config = DeepSeekConfig(
        api_key=api_key,
        model=settings.deepseek_model,
        timeout_seconds=settings.model_timeout_seconds,
    )
    ledger = ModelCallLedger(
        settings.deepseek_ledger_path,
        max_calls=settings.deepseek_max_calls,
        call_reserve_microunits=settings.deepseek_reservation_micro_usd,
        total_budget_microunits=settings.deepseek_max_cost_micro_usd,
        # 真实厂商入口固定一账本一任务：旧队列消息无法借各自的单任务预算放大总调用。
        max_distinct_jobs=_VENDOR_MAX_DISTINCT_JOBS,
    )
    transport = create_openai_sdk_transport(config)
    # 仅记录可审计的非敏感标识，便于按审批引用与账本核对本次运行。
    logger.info(
        "DeepSeek 厂商调用已启用: model=%s approvalId=%s maxCalls=%d reserveMicroUsd=%d "
        "maxCostMicroUsd=%d ledger=%s",
        settings.deepseek_model, settings.deepseek_approval_id, settings.deepseek_max_calls,
        settings.deepseek_reservation_micro_usd, settings.deepseek_max_cost_micro_usd, ledger.path,
    )
    return DeepSeekModel(config, transport=transport, ledger=ledger)


def _load_prediction_inputs(
    settings: Settings,
) -> tuple[SnapshotPolicy | BothAdvanceQ1Policy, ReplayContext | None, InMemoryLapRepository]:
    """装载并核对策略、回放上下文与审计圈速；处理器与预检共用，只读文件。"""
    if not settings.prediction_policy_path or not settings.prediction_laps_path:
        raise ValueError("enabled prediction requires policy and audited laps")
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
    return policy, replay_context, load_lap_fixture(settings.prediction_laps_path)


def build_prediction_processor(
    settings: Settings,
    store: InboxStore,
    *,
    vendor_model: DeepSeekModel | None = None,
) -> PredictionProcessor:
    """显式构造离线处理器；DeepSeek 仅接受调用方注入的已建模型。"""
    if not settings.prediction_enabled:
        raise ValueError("prediction processor requires prediction_enabled")
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
    policy, replay_context, repository = _load_prediction_inputs(settings)
    model: ModelGateway
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
        repository,
        model,
        policy,
        max_attempts=max_attempts,
        lease_seconds=max(90, int(settings.model_timeout_seconds) + 30),
        replay_context=replay_context,
    )


def preflight(settings: Settings, *, init_ledger: bool = False) -> dict[str, object]:
    """真实前向启动前的不联网预检：复用启动门禁与题目/圈速装载，不连 Broker、不建 inbox、不发厂商请求。

    默认只读：已存在账本使用 mode=ro 和显式 BEGIN 在同一快照核对配置与调用数；WAL 模式下可能读取或短暂创建 -shm sidecar，不修改账本业务数据，但快照之后的新写入不会被阻止。账本不存在时仅校验父目录并报告未初始化。
    仅 init_ledger=True 时创建空账本并持久冻结预算与“一账本一任务”绑定。
    账本绑定首个预留的任务，这不等于授权该任务；预检要求本次运行的账本没有任何调用记录。
    """
    if not settings.rabbitmq_url or not settings.consumer_sqlite_path:
        raise ValueError("consumer requires RabbitMQ URL and persistent SQLite path")
    if not Path(settings.consumer_sqlite_path).expanduser().parent.is_dir():
        raise ValueError("consumer SQLite parent directory does not exist")
    _require_vendor_gate(settings)
    sdk_versions, matches_pins = inspect_vendor_sdk_versions()
    warnings: list[str] = []
    if not matches_pins:
        warnings.append(
            f"installed SDK versions differ from the deepseek extra pins (openai=={_OPENAI_SDK_VERSION}, "
            "httpx>=0.28.1,<0.29); the adapter was only verified against the pinned versions"
        )
    expected = (
        settings.deepseek_max_calls, settings.deepseek_reservation_micro_usd,
        settings.deepseek_max_cost_micro_usd, _VENDOR_MAX_DISTINCT_JOBS,
    )
    inspection = inspect_ledger(settings.deepseek_ledger_path)
    if inspection is not None:
        persisted = (
            inspection.max_calls, inspection.call_reserve_microunits,
            inspection.total_budget_microunits, inspection.max_distinct_jobs,
        )
        if persisted != expected:
            raise ValueError("ledger budget configuration differs from its persisted approval")
        if inspection.reserved_calls:
            raise ValueError("preflight requires a fresh ledger for this run; use a new run directory")
    policy, _, _ = _load_prediction_inputs(settings)
    ledger_initialized = inspection is not None
    if init_ledger and not ledger_initialized:
        build_deepseek_model(settings)
        ledger_initialized = True
    if not ledger_initialized:
        warnings.append("ledger is not initialized; the worker creates it at startup, or rerun with --init-ledger")
    return {
        "status": "PREFLIGHT_PASSED",
        "vendorRequestsSent": 0,
        "executionMode": settings.prediction_execution_mode,
        "model": settings.deepseek_model,
        "promptVersion": settings.prompt_version,
        "featureVersion": settings.feature_version,
        "sdkVersions": sdk_versions,
        "matchesLockedPins": matches_pins,
        "warnings": warnings,
        "apiKeyConfigured": True,
        "rabbitmqUrlConfigured": True,
        "approvalId": settings.deepseek_approval_id,
        "budgetScope": "PER_JOB_LEDGER_BOUND_TO_FIRST_JOB",
        "requiredJavaBatch": f"exactly one batch with question_ids=[{policy.question_id}]",
        "maxDistinctJobsPerLedger": _VENDOR_MAX_DISTINCT_JOBS,
        "maxCallsPerJob": settings.deepseek_max_calls,
        "effectiveMaxAttempts": min(settings.model_max_attempts, settings.deepseek_max_calls),
        "reserveMicroUsdPerCall": settings.deepseek_reservation_micro_usd,
        "maxCostMicroUsdPerJob": settings.deepseek_max_cost_micro_usd,
        "ledgerPath": str(Path(settings.deepseek_ledger_path).expanduser().absolute()),
        "ledgerInitialized": ledger_initialized,
        "ledgerInitializedByThisPreflight": ledger_initialized and inspection is None,
        "ledgerReservedCalls": 0,
        "questionSnapshotId": policy.question_snapshot_id,
        "questionId": policy.question_id,
        "meetingKey": policy.meeting_key,
        "consumerSqlitePath": settings.consumer_sqlite_path,
    }


async def run(settings: Settings) -> None:
    """短连接失败退避重试；SQLite 错误会先关连接使未 ACK 消息重投。"""
    if not settings.rabbitmq_url or not settings.consumer_sqlite_path:
        raise ValueError("consumer requires RabbitMQ URL and persistent SQLite path")
    vendor_model: DeepSeekModel | None = None
    if settings.model_provider == "deepseek" or settings.vendor_api_enabled:
        # 厂商路径只经唯一构造入口；开关关闭或任何门禁缺项时，在建库、建账本和连接 Broker 前拒绝。
        vendor_model = build_deepseek_model(settings)
    store = InboxStore(settings.consumer_sqlite_path)
    processor: PredictionProcessor | None = None
    if settings.prediction_enabled:
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
            # 只记录异常类型：连接异常文本可能携带 Broker URL 凭据。
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
    """启动独立消费者、执行文件心跳健康检查，或做真实前向的不联网预检。"""
    if "--help" in sys.argv or "-h" in sys.argv:
        print(_USAGE)
        return
    if "--init-ledger" in sys.argv and "--preflight" not in sys.argv:
        raise SystemExit("--init-ledger is only valid together with --preflight")
    settings = Settings()
    if "--preflight" in sys.argv:
        logging.basicConfig(level=settings.log_level)
        summary = preflight(settings, init_ledger="--init-ledger" in sys.argv)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return
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
