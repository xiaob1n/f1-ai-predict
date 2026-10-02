"""单题离线编排：外部操作只通过显式注入目标，模拟证据不能替代真实验收。"""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import uuid
from collections.abc import Callable, Mapping
from datetime import datetime
from pathlib import Path
from typing import Any, Protocol

from f1_predict.replay.manifest import (
    canonical_json_bytes,
    canonical_sha256,
    format_utc,
    parse_utc,
)

SCHEMA_NUMBERS = ("001", "002", "003", "004", "006", "007", "008")
SIMULATION_MODE = "OFFLINE_SIMULATION"
SCOPE = "isolated-real-data-mvp"
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}")


class WorkflowBlocked(ValueError):
    """证据不足、身份不符或副作用结果不明时保留现场。"""


class IsolatedTarget(Protocol):
    """适配器只能由调用者显式注入；本版本编排仅接纳模拟目标。"""

    simulated: bool

    def resource_identity(self) -> dict[str, Any]:
        """返回由目标核验的资源标签和回环绑定。"""
        ...

    def table_count(self) -> int:
        """核验 schema 初始化前目标库没有已有表。"""
        ...

    def apply_schema(self, path: Path, digest: str) -> None:
        """仅按批准脚本及其摘要执行目标操作。"""
        ...

    def inspect_schema(self) -> None:
        """独立核对必要表、列和唯一约束。"""
        ...

    def load(self, source: dict[str, Any], identity: dict[str, Any]) -> dict[str, Any]:
        """事务装载副本并读回实际隔离身份映射。"""
        ...

    def post_batch(self, round_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        """提交唯一单题批次；不负责重试未知结果。"""
        ...

    def observe(self, job_id: str, batch_id: int) -> dict[str, Any]:
        """读取同一任务的 REST、SQL 和 SQLite 证据。"""
        ...

    def original_message(self, job_id: str) -> dict[str, Any]:
        """读回原请求 outbox 的消息标识及完整载荷。"""
        ...

    def replay(self, message: dict[str, Any]) -> None:
        """重投原始消息而不是创建新批次。"""
        ...

    def restart_worker(self) -> None:
        """保留 SQLite 卷和冻结包恢复自有工作器。"""
        ...

    def owned_processes(self) -> list[dict[str, Any]]:
        """重新核验 PID 与启动标识并返回自有进程。"""
        ...

    def stop_process(self, process: dict[str, Any]) -> None:
        """停止已核对的进程，不清理数据卷或其他资源。"""
        ...


def validate_resource_identity(value: Mapping[str, Any], project: str) -> None:
    """拒绝共享项目、远程 context 和非回环暴露；字段来自受控目标核验。"""
    if not IDENTIFIER.fullmatch(project) or not project.startswith("real-data-mvp-"):
        raise WorkflowBlocked("专属项目标识不合法")
    if (value.get("project") != project or value.get("scope") != SCOPE
            or value.get("dockerContext") != "local" or value.get("database") != "f1_ai_predict"
            or value.get("sourceShared") is not False):
        raise WorkflowBlocked("专属资源身份不匹配")
    services = value.get("services")
    if not isinstance(services, dict) or set(services) != {"mysql", "rabbitmq"}:
        raise WorkflowBlocked("专属服务目录不完整")
    for service, record in services.items():
        if not isinstance(record, dict) or record.get("project") != project or record.get("service") != service or record.get("scope") != SCOPE:
            raise WorkflowBlocked("服务所有权标签不匹配")
        bindings = record.get("bindings")
        if not isinstance(bindings, list) or not bindings or any(
            not isinstance(item, dict) or item.get("host") != "127.0.0.1"
            or isinstance(item.get("port"), bool) or not isinstance(item.get("port"), int)
            or not 1 <= item["port"] <= 65535 for item in bindings
        ):
            raise WorkflowBlocked("隔离服务必须只绑定回环")


def isolated_environment(inherited: Mapping[str, str], explicit: Mapping[str, str]) -> dict[str, str]:
    """只保留基本进程环境及显式隔离变量，不继承代理、JVM、Spring 或源凭据。"""
    base_keys = {"PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "SYSTEMROOT"}
    allowed = {
        "MVP_PROJECT_NAME", "MVP_MYSQL_ROOT_PASSWORD", "MVP_MYSQL_USER", "MVP_MYSQL_PASSWORD",
        "MVP_RABBIT_USER", "MVP_RABBIT_PASSWORD", "SPRING_CONFIG_LOCATION",
        "F1_PREDICT_RABBITMQ_URL", "F1_PREDICT_CONSUMER_SQLITE_PATH",
        "F1_PREDICT_CONSUMER_HEALTH_PATH", "F1_PREDICT_PREDICTION_ENABLED",
        "F1_PREDICT_PREDICTION_EXECUTION_MODE", "F1_PREDICT_PREDICTION_REPLAY_MODEL_MODE",
        "F1_PREDICT_PREDICTION_REPLAY_MANIFEST_PATH", "F1_PREDICT_PREDICTION_REPLAY_MANIFEST_HASH",
        "F1_PREDICT_PREDICTION_POLICY_PATH", "F1_PREDICT_PREDICTION_LAPS_PATH",
        "F1_PREDICT_MODEL_VERSION", "F1_PREDICT_PROMPT_VERSION", "F1_PREDICT_FEATURE_VERSION",
    }
    if set(explicit) - allowed or any(not isinstance(value, str) or not value for value in explicit.values()):
        raise WorkflowBlocked("隔离环境包含未批准或空白变量")
    mode = explicit.get("F1_PREDICT_PREDICTION_EXECUTION_MODE")
    if mode is not None and (mode != "HISTORICAL_ENGINEERING_REPLAY"
                            or explicit.get("F1_PREDICT_PREDICTION_REPLAY_MODEL_MODE") != "stub"):
        raise WorkflowBlocked("本轮只允许历史回放的本地替身配置")
    return {**{key: value for key, value in inherited.items() if key in base_keys}, **explicit}


def worker_environment(
    inherited: Mapping[str, str], bundle: Path, runtime: Path, approved_hash: str,
    policy: Mapping[str, Any], rabbitmq_url: str,
) -> dict[str, str]:
    """构造未来显式隔离部署参数；不创建连接、不传入源数据库或真实模型地址。"""
    from urllib.parse import unquote, urlsplit

    parsed = urlsplit(rabbitmq_url)
    if (parsed.scheme != "amqp" or parsed.hostname != "127.0.0.1" or parsed.port != 15683
            or unquote(parsed.path[1:]) != "/f1predict-mvp" or not parsed.username or not parsed.password
            or parsed.query or parsed.fragment):
        raise WorkflowBlocked("Worker 只接受专属回环 Broker 与 vhost")
    if not re.fullmatch(r"[a-f0-9]{64}", approved_hash):
        raise WorkflowBlocked("Worker 缺少外部批准的清单摘要")
    directory = runtime.resolve(strict=True)
    for path in (runtime, bundle):
        if path.is_symlink():
            raise WorkflowBlocked("Worker 路径不能是符号链接")
    package = bundle.resolve(strict=True)
    if directory not in package.parents:
        raise WorkflowBlocked("Worker 包必须位于专属运行目录")
    return isolated_environment(inherited, {
        "F1_PREDICT_RABBITMQ_URL": rabbitmq_url,
        "F1_PREDICT_CONSUMER_SQLITE_PATH": str(directory / "worker.sqlite"),
        "F1_PREDICT_CONSUMER_HEALTH_PATH": str(directory / "worker.health.json"),
        "F1_PREDICT_PREDICTION_ENABLED": "true",
        "F1_PREDICT_PREDICTION_EXECUTION_MODE": "HISTORICAL_ENGINEERING_REPLAY",
        "F1_PREDICT_PREDICTION_REPLAY_MODEL_MODE": "stub",
        "F1_PREDICT_PREDICTION_REPLAY_MANIFEST_PATH": str(package / "manifest.json"),
        "F1_PREDICT_PREDICTION_REPLAY_MANIFEST_HASH": approved_hash,
        "F1_PREDICT_PREDICTION_POLICY_PATH": str(package / "policy.json"),
        "F1_PREDICT_PREDICTION_LAPS_PATH": str(package / "fixture.json"),
        "F1_PREDICT_MODEL_VERSION": policy["model_version"],
        "F1_PREDICT_PROMPT_VERSION": policy["prompt_version"],
        "F1_PREDICT_FEATURE_VERSION": policy["feature_version"],
    })


class StateStore:
    """状态原子保存；运行目录须由调用者显式创建且已限定权限。"""

    def __init__(self, directory: Path) -> None:
        self.directory = directory.absolute()
        for part in (self.directory, *self.directory.parents):
            if part.is_symlink():
                raise WorkflowBlocked("状态目录路径不得含符号链接")
        info = self.directory.stat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
            raise WorkflowBlocked("状态目录必须私有且由当前用户拥有")
        self.path = self.directory / "workflow.json"

    def load(self) -> dict[str, Any] | None:
        """只读加载受保护的模拟状态，拒绝模式混淆。"""
        if self.path.is_symlink():
            raise WorkflowBlocked("状态文件不得是符号链接")
        if not self.path.exists():
            return None
        fd = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, "rb") as stream:
            info = os.fstat(stream.fileno())
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                    or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > 1_048_576):
                raise WorkflowBlocked("状态文件权限或大小不合格")
            data = json.load(stream)
        if not isinstance(data, dict) or data.get("mode") != SIMULATION_MODE:
            raise WorkflowBlocked("不能混用真实与模拟状态")
        return data

    def save(self, data: dict[str, Any]) -> None:
        """用私有临时文件和原子替换固定阶段意图。"""
        if data.get("mode") != SIMULATION_MODE:
            raise WorkflowBlocked("本版本仅写模拟状态")
        temporary = self.directory / (".state-" + uuid.uuid4().hex)
        try:
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(canonical_json_bytes(data))
                stream.flush()
                os.fsync(stream.fileno())
            if self.path.is_symlink():
                raise WorkflowBlocked("状态文件不得是符号链接")
            os.replace(temporary, self.path)
            fd = os.open(self.directory, os.O_RDONLY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        finally:
            temporary.unlink(missing_ok=True)


class ReplayWorkflow:
    """只编排已注入的操作，不发现服务、不导入旧 E2E 入口。"""

    def __init__(
        self, store: StateStore, target: IsolatedTarget, project: str, *,
        clock: Callable[[], datetime], monotonic: Callable[[], float],
        wait: Callable[[float], None], timeout_seconds: float = 120.0,
    ) -> None:
        if target.simulated is not True:
            raise WorkflowBlocked("真实资源操作尚未授权；仅允许明确模拟目标")
        if not 0 < timeout_seconds <= 600:
            raise WorkflowBlocked("等待预算必须有界")
        self.store, self.target, self.project = store, target, project
        self.clock, self.monotonic, self.wait = clock, monotonic, wait
        self.timeout_seconds = timeout_seconds
        previous = store.load()
        self.state = previous or {"mode": SIMULATION_MODE, "runId": uuid.uuid4().hex,
                                  "project": project, "stage": "NEW", "postAttempted": False}
        if self.state.get("project") != project:
            raise WorkflowBlocked("运行状态不属于当前项目")

    def _save(self, stage: str, **fields: Any) -> None:
        self.state.update(fields)
        self.state["stage"] = stage
        self.store.save(self.state)

    def _identity(self) -> None:
        validate_resource_identity(self.target.resource_identity(), self.project)

    def prepare(self, sql_root: Path) -> None:
        """DDL 一旦结果不明即停止；不能以重试再次应用 008。"""
        self._identity()
        if self.state["stage"] in {"APPLYING", "SCHEMA_UNCERTAIN"}:
            raise WorkflowBlocked("schema 中断需单独人工核验，禁止自动重跑")
        if self.state["stage"] != "NEW":
            self.target.inspect_schema()
            return
        if self.target.table_count() != 0:
            raise WorkflowBlocked("schema 初始化只允许已确认空库")
        scripts = []
        for number in SCHEMA_NUMBERS:
            matches = list(sql_root.glob(number + "_*.sql"))
            if len(matches) != 1 or matches[0].is_symlink():
                raise WorkflowBlocked("schema 白名单文件缺失或有歧义")
            scripts.append(matches[0])
        self._save("APPLYING", schemaScripts=[item.name for item in scripts])
        try:
            for script in scripts:
                # 原脚本固定顺序、明确摘要；目标实现负责提交，本工具不会执行 shell。
                self.target.apply_schema(script, hashlib.sha256(script.read_bytes()).hexdigest())
            self.target.inspect_schema()
        except (OSError, RuntimeError, ValueError, TypeError, KeyError, IndexError):
            self._save("SCHEMA_UNCERTAIN")
            raise WorkflowBlocked("schema 初始化结果不明，保留现场") from None
        self._save("READY")

    def load_capture(self, source: dict[str, Any], identity: dict[str, Any], capture_hash: str) -> dict[str, Any]:
        """目标必须读回实际 ID；提交不明时保留同一输入再核验，不重新捕获。"""
        self._identity()
        if self.state["stage"] not in {"READY", "LOAD_UNCERTAIN", "LOADED"}:
            raise WorkflowBlocked("装载阶段状态不合法")
        if self.state.get("captureHash", capture_hash) != capture_hash:
            raise WorkflowBlocked("恢复不能替换源捕获")
        self.target.inspect_schema()
        self._save("LOAD_UNCERTAIN", captureHash=capture_hash)
        mapping = self.target.load(source, identity)
        self._save("LOADED", idMapping=mapping)
        return mapping

    def bind_bundle(
        self, manifest_hash: str, policy: dict[str, Any], import_completed_at: datetime,
        sporting_cutoff: datetime,
    ) -> None:
        """只接收已由封签器核验的包；所有后续恢复使用这一份。"""
        if self.state["stage"] not in {"LOADED", "SEALED"}:
            raise WorkflowBlocked("回放包必须在实际 ID 装载后封签")
        if not re.fullmatch(r"[a-f0-9]{64}", manifest_hash):
            raise WorkflowBlocked("manifest 摘要无效")
        if self.state.get("manifestHash", manifest_hash) != manifest_hash:
            raise WorkflowBlocked("运行不能替换回放包")
        mapping = self.state["idMapping"]
        if (policy.get("question_id") != mapping["questionId"]
                or policy.get("question_snapshot_id") != mapping["snapshotId"]):
            raise WorkflowBlocked("封签策略与装载身份不一致")
        self._save("SEALED", manifestHash=manifest_hash, policy=policy,
                   importCompletedAt=format_utc(import_completed_at), sportingCutoff=format_utc(sporting_cutoff))

    def submit(self) -> None:
        """先持久化提交意图；进程中断或 HTTP 响应丢失后绝不再 POST。"""
        self._identity()
        if self.state.get("postAttempted"):
            if self.state["stage"] == "SUBMISSION_UNCERTAIN":
                raise WorkflowBlocked("批次提交结果不明，必须查询核验而非重复 POST")
            return
        if self.state["stage"] != "SEALED":
            raise WorkflowBlocked("未封签包不能创建批次")
        cutoff = parse_utc(self.clock(), field="dataCutoff")
        if cutoff < parse_utc(self.state["importCompletedAt"], field="importCompletedAt"):
            raise WorkflowBlocked("请求截止不能早于导入完成")
        policy = self.state["policy"]
        payload = {"questionIds": [self.state["idMapping"]["questionId"]],
                   "dataCutoff": format_utc(cutoff), "modelVersion": policy["model_version"],
                   "promptVersion": policy["prompt_version"], "featureVersion": policy["feature_version"]}
        self._save("SUBMISSION_UNCERTAIN", postAttempted=True, batchRequest=payload,
                   requestHash=canonical_sha256(payload))
        try:
            response = self.target.post_batch(self.state["idMapping"]["roundId"], payload)
            ids = response["jobIds"]
            if (response.get("questionCount") != 1 or len(ids) != 1
                    or not isinstance(ids[0], str) or not ids[0]
                    or response.get("roundId") != self.state["idMapping"]["roundId"]
                    or isinstance(response["batchId"], bool) or not isinstance(response["batchId"], int)
                    or response["batchId"] <= 0):
                raise WorkflowBlocked("创建响应不属于单题批次")
        except (OSError, RuntimeError, ValueError, TypeError, KeyError, IndexError):
            raise WorkflowBlocked("创建批次结果不明，禁止自动重复 POST") from None
        self._save("SUBMITTED", jobId=ids[0], batchId=response["batchId"])

    def _check_terminal(self, evidence: dict[str, Any]) -> None:
        expected_id = self.state["jobId"]
        outcome, sql, sqlite = evidence["outcome"], evidence["sql"], evidence["sqlite"]
        selected = outcome["result"]["selectedOptions"]
        if (evidence["job"]["predictionJobId"] != expected_id
                or outcome["predictionJobId"] != expected_id
                or evidence["job"]["status"] != "SUCCEEDED" or outcome["status"] != "SUCCEEDED"
                or evidence["batch"]["batchId"] != self.state["batchId"]
                or evidence["batch"]["status"] != "COMPLETED"
                or evidence["batch"]["questionCount"] != 1
                or len(selected) != 1 or selected[0]["position"] != 1
                or str(selected[0]["optionId"]) not in self.state["policy"]["option_drivers"]
                or sql["predictionJobId"] != expected_id or sql["batchId"] != self.state["batchId"]
                or sql["resultCount"] != 1 or sql["receiptCount"] != 1 or sql["failureCount"] != 0
                or sqlite["predictionJobId"] != expected_id
                or parse_utc(sqlite["dataCutoff"], field="dataCutoff") != parse_utc(self.state["batchRequest"]["dataCutoff"], field="dataCutoff")
                or sqlite["executionMode"] != "HISTORICAL_ENGINEERING_REPLAY"
                or parse_utc(sqlite["sportingCutoff"], field="sportingCutoff") != parse_utc(self.state["sportingCutoff"], field="sportingCutoff")
                or parse_utc(sqlite["importCompletedAt"], field="importCompletedAt") != parse_utc(self.state["importCompletedAt"], field="importCompletedAt")
                or sqlite["sourceFirstSeenStatus"] != "UNKNOWN"
                or sqlite["modelVersion"] != self.state["policy"]["model_version"]
                or sqlite["promptVersion"] != self.state["policy"]["prompt_version"]
                or sqlite["featureVersion"] != self.state["policy"]["feature_version"]
                or sqlite["inboxCount"] != 1 or sqlite["outboxCount"] != 1
                or sqlite["status"] != "SUCCEEDED" or sqlite["publishedCount"] != 1
                or sqlite["manifestHash"] != self.state["manifestHash"]
                or not re.fullmatch(r"[a-f0-9]{64}", sqlite["featureHash"])):
            raise WorkflowBlocked("REST/SQL/SQLite 终态证据不一致")

    def wait_terminal(self) -> dict[str, Any]:
        """有界查询每一种终止状态；HTTP 201、ACK 均不作为完成标志。"""
        if self.state["stage"] not in {"SUBMITTED", "COMPLETE", "RECOVERING"}:
            raise WorkflowBlocked("没有可核验的已提交任务")
        self._identity()
        deadline = self.monotonic() + self.timeout_seconds
        while self.monotonic() < deadline:
            try:
                evidence = self.target.observe(self.state["jobId"], self.state["batchId"])
                if evidence.get("workerAlive") is not True or evidence["job"]["status"] == "FAILED":
                    self._save("FAILED")
                    raise WorkflowBlocked("推理失败或工作器已退出")
                if evidence["job"]["status"] == "SUCCEEDED":
                    self._check_terminal(evidence)
                    original_hash = canonical_sha256(self.target.original_message(self.state["jobId"]))
                    if self.state.get("requestMessageHash", original_hash) != original_hash:
                        raise WorkflowBlocked("原始请求消息在查询之间发生变化")
                    self._save("COMPLETE", evidence=evidence, requestMessageHash=original_hash)
                    return evidence
            except WorkflowBlocked:
                raise
            except (OSError, RuntimeError, ValueError, TypeError, KeyError, IndexError):
                self._save("OBSERVATION_UNCERTAIN")
                raise WorkflowBlocked("终态查询失败，保留原批次身份") from None
            self.wait(min(1.0, max(0.0, deadline - self.monotonic())))
        self._save("TIMED_OUT")
        raise WorkflowBlocked("终态等待超出独立有界预算")

    def exercise_recovery(self) -> dict[str, Any]:
        """原消息重投及一次工作器重启，核验唯一终态和冻结输入未变。"""
        if self.state["stage"] != "COMPLETE":
            raise WorkflowBlocked("恢复演练要求已核验终态")
        self._identity()
        before = self.state["evidence"]
        message = self.target.original_message(self.state["jobId"])
        payload = message.get("payload")
        if (not isinstance(payload, dict) or message.get("messageId") != payload.get("messageId")
                or payload.get("predictionJobId") != self.state["jobId"]
                or payload.get("batchId") != self.state["batchId"]
                or payload.get("questionId") != self.state["idMapping"]["questionId"]
                or payload.get("questionSnapshotId") != self.state["idMapping"]["snapshotId"]
                or parse_utc(payload.get("dataCutoff"), field="dataCutoff") != parse_utc(self.state["batchRequest"]["dataCutoff"], field="dataCutoff")):
            raise WorkflowBlocked("重投消息与原任务不一致")
        digest = canonical_sha256(message)
        if self.state.get("requestMessageHash") != digest:
            raise WorkflowBlocked("重投不能替换先前核验的原始消息")
        self._save("RECOVERING", replayMessageHash=digest)
        self.target.replay(message)
        self.target.restart_worker()
        after = self.wait_terminal()
        if (before["sqlite"]["featureHash"] != after["sqlite"]["featureHash"]
                or canonical_sha256(before["outcome"]) != canonical_sha256(after["outcome"])
                or canonical_sha256(self.target.original_message(self.state["jobId"])) != digest):
            self._save("RECOVERY_CONFLICT")
            raise WorkflowBlocked("恢复改变了冻结输入或唯一终态")
        self._save("COMPLETE", recoveryVerified=True)
        return after

    def record_started_process(self, process: dict[str, Any]) -> None:
        """启动记录先写入私有状态；检查时不接受仅由适配器自报的 PID。"""
        if (process.get("project") != self.project or process.get("runId") != self.state["runId"]
                or not process.get("startToken") or isinstance(process.get("pid"), bool)
                or not isinstance(process.get("pid"), int) or process["pid"] <= 0):
            raise WorkflowBlocked("进程启动记录不属于当前运行")
        recorded = [*self.state.get("ownedProcesses", []), dict(process)]
        self._save(self.state["stage"], ownedProcesses=recorded)

    def stop(self) -> None:
        """所有权全部核验后才停止自有进程；不清理卷或其他项目。"""
        self._identity()
        processes = self.target.owned_processes()
        recorded = self.state.get("ownedProcesses", [])
        for process in processes:
            if (process not in recorded or process.get("project") != self.project
                    or process.get("runId") != self.state["runId"]):
                raise WorkflowBlocked("不能停止非本工具拥有的进程")
        for process in processes:
            self.target.stop_process(process)
        self._save("STOPPED")

    def report(self) -> dict[str, Any]:
        """输出只含当前证据状态；模拟通过永远不是 M1 或 M2。"""
        return {"mode": SIMULATION_MODE, "stage": self.state["stage"], "project": self.project,
                "realResourceAccess": False, "realModelCalls": False, "m1Verified": False,
                "m2Verified": False, "simulatedRecoveryVerified": self.state.get("recoveryVerified", False),
                "manifestHash": self.state.get("manifestHash"),
                "postAttempts": int(self.state.get("postAttempted", False))}
