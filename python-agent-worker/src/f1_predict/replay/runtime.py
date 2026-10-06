"""仅将已注入系统对象转为受控回放目标的运行时适配器。"""

from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import stat
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, ClassVar

from f1_predict.replay.workflow import WorkflowBlocked, validate_resource_identity


@dataclass(frozen=True, slots=True)
class RuntimeAuthorization:
    """调用方独立提供的授权上下文；仅凭字段无法证明外部授权真实性。"""

    project: str
    scope: str


class OperationJournal:
    """只为 OFFLINE_ADAPTER_TEST 保存不可混用的单运行操作意图。"""

    mode = "OFFLINE_ADAPTER_TEST"

    def __init__(self, directory: Path, *, project: str, resource_identity: dict[str, Any]) -> None:
        if not isinstance(project, str) or not project.startswith("real-data-mvp-"):
            raise WorkflowBlocked("journal requires a dedicated project")
        if not isinstance(resource_identity, dict):
            raise WorkflowBlocked("journal requires a resource identity mapping")
        self.directory = Path(directory).expanduser().absolute()
        if ".runtime" in self.directory.parts:
            raise WorkflowBlocked("legacy E2E runtime directories are forbidden")
        for part in (self.directory, *self.directory.parents):
            if part.is_symlink():
                raise WorkflowBlocked("journal path cannot contain symlinks")
        try:
            self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
            info = self.directory.stat()
        except OSError:
            raise WorkflowBlocked("journal directory could not be prepared safely") from None
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
            raise WorkflowBlocked("journal directory must be owned and private")
        self.path = self.directory / "runtime.json"
        self.lock_path = self.directory / "runtime.lock"
        self.project = project
        self.resource_identity = json.loads(json.dumps(resource_identity))
        self.identity_hash = hashlib.sha256(json.dumps(
            self.resource_identity, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")).hexdigest()
        with self._exclusive_lock() as descriptor:
            self._persisted_state = self.load()
            marker = self._read_revision(descriptor)
            if self._persisted_state is None:
                if marker:
                    raise WorkflowBlocked("persisted journal state is missing; do not create a replacement")
            else:
                digest = self._state_digest(self._persisted_state)
                if marker and marker != digest:
                    raise WorkflowBlocked("journal state and persistent revision do not match")
                if not marker:
                    self._write_revision(descriptor, digest)
        self._state = self._persisted_state or {
            "mode": self.mode, "project": project, "resourceIdentityHash": self.identity_hash,
            "stage": "NEW", "postAttempted": False, "ownedProcesses": [],
        }
        if self._state.get("project") != project or self._state.get("resourceIdentityHash") != self.identity_hash:
            raise WorkflowBlocked("journal project or resource identity changed")

    def load(self) -> dict[str, Any] | None:
        """读取并校验本地状态，拒绝符号链接、非私有文件和其他运行模式。"""
        if self.path.is_symlink():
            raise WorkflowBlocked("journal file cannot be a symlink")
        if not self.path.exists():
            return None
        try:
            descriptor = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(descriptor, "rb") as stream:
                info = os.fstat(stream.fileno())
                if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                        or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > 1_048_576):
                    raise WorkflowBlocked("journal file must be owned, private and bounded")
                value = json.load(stream)
        except WorkflowBlocked:
            raise
        except Exception:  # noqa: BLE001 - JSON/OS 异常不得包含在本地错误响应中。
            raise WorkflowBlocked("journal file could not be read safely") from None
        required = {"mode", "project", "resourceIdentityHash", "stage", "postAttempted", "ownedProcesses"}
        if (not isinstance(value, dict) or value.get("mode") != self.mode
                or not required <= set(value) or not isinstance(value.get("project"), str)
                or not re.fullmatch(r"[a-f0-9]{64}", str(value.get("resourceIdentityHash", "")))
                or not isinstance(value.get("stage"), str) or not isinstance(value.get("postAttempted"), bool)
                or not isinstance(value.get("ownedProcesses"), list)):
            raise WorkflowBlocked("journal mode or document shape is invalid")
        return value

    @contextmanager
    def _exclusive_lock(self):
        """使用当前用户私有锁串行化本地操作意图提交。"""
        if self.lock_path.is_symlink():
            raise WorkflowBlocked("journal lock cannot be a symlink")
        try:
            descriptor = os.open(self.lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
            info = os.fstat(descriptor)
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                    or stat.S_IMODE(info.st_mode) != 0o600):
                raise WorkflowBlocked("journal lock must be owned and private")
            fcntl.flock(descriptor, fcntl.LOCK_EX)
            yield descriptor
        except WorkflowBlocked:
            raise
        except OSError:
            raise WorkflowBlocked("journal lock could not be acquired safely") from None
        finally:
            if "descriptor" in locals():
                os.close(descriptor)

    @staticmethod
    def _state_digest(state: dict[str, Any]) -> str:
        """为私有锁文件生成非敏感的状态版本摘要。"""
        return hashlib.sha256(json.dumps(state, sort_keys=True, separators=(",", ":"),
                                        ensure_ascii=False).encode("utf-8")).hexdigest()

    @staticmethod
    def _read_revision(descriptor: int) -> str:
        os.lseek(descriptor, 0, os.SEEK_SET)
        marker = os.read(descriptor, 65).decode("ascii")
        if marker and not re.fullmatch(r"[a-f0-9]{64}", marker):
            raise WorkflowBlocked("journal lock revision is invalid")
        return marker

    @staticmethod
    def _write_revision(descriptor: int, digest: str) -> None:
        os.ftruncate(descriptor, 0)
        os.lseek(descriptor, 0, os.SEEK_SET)
        if os.write(descriptor, digest.encode("ascii")) != 64:
            raise OSError("short revision write")
        os.fsync(descriptor)

    def save(self, *, stage: str, **fields: Any) -> dict[str, Any]:
        """锁定并比较持久状态后原子保存，拒绝旧实例覆盖新意图。"""
        with self._exclusive_lock() as lock_descriptor:
            persisted = self.load()
            marker = self._read_revision(lock_descriptor)
            expected = self._state_digest(self._persisted_state) if self._persisted_state is not None else ""
            if persisted != self._persisted_state or marker != expected:
                raise WorkflowBlocked("journal changed in another runtime; reopen before continuing")
            return self._save_locked(lock_descriptor=lock_descriptor, stage=stage, **fields)

    def _save_locked(self, *, lock_descriptor: int, stage: str, **fields: Any) -> dict[str, Any]:
        """原子保存操作意图；已经到达终态的运行不可被普通更新复活。"""
        current = self._state
        terminal = {"STOPPED", "FAILED", "RECOVERY_CONFLICT", "SCHEMA_UNCERTAIN", "RESTART_UNCERTAIN"}
        intent_transitions = {
            "SUBMISSION_INTENT": {"SUBMITTED", "SUBMISSION_UNCERTAIN", "OBSERVED"},
            "SUBMISSION_UNCERTAIN": {"OBSERVED", "SUBMITTED"},
            "REPLAY_INTENT": {"REPLAYED"},
            "RESTART_INTENT": {"RESTARTED", "RESTART_UNCERTAIN"},
            "STOP_INTENT": {"STOPPED"},
        }
        if current.get("stage") in terminal and stage != current["stage"]:
            raise WorkflowBlocked("uncertain or terminal journal state cannot be automatically advanced")
        if current.get("stage") in intent_transitions and stage not in intent_transitions[current["stage"]]:
            raise WorkflowBlocked("interrupted operation intent cannot be automatically retried")
        if current.get("postAttempted") is True and fields.get("postAttempted", True) is not True:
            raise WorkflowBlocked("persisted submission intent cannot be cleared")
        value = json.loads(json.dumps(
            {**current, **fields, "mode": self.mode, "project": self.project,
             "resourceIdentityHash": self.identity_hash, "stage": stage},
            sort_keys=True, separators=(",", ":"), ensure_ascii=False,
        ))
        temporary = self.directory / (".runtime-" + uuid.uuid4().hex)
        try:
            temporary_descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(temporary_descriptor, "wb") as stream:
                stream.write(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                        ensure_ascii=False).encode("utf-8"))
                stream.flush()
                os.fsync(stream.fileno())
            if self.path.is_symlink():
                raise WorkflowBlocked("journal file cannot be a symlink")
            os.replace(temporary, self.path)
            directory_descriptor = os.open(self.directory, os.O_RDONLY)
            try:
                os.fsync(directory_descriptor)
            finally:
                os.close(directory_descriptor)
            self._write_revision(lock_descriptor, self._state_digest(value))
        except WorkflowBlocked:
            raise
        except Exception:  # noqa: BLE001 - 本地编码或磁盘异常不应泄漏状态内容。
            raise WorkflowBlocked("journal state could not be saved safely") from None
        finally:
            temporary.unlink(missing_ok=True)
        self._state = value
        self._persisted_state = value
        return dict(value)

    @property
    def state(self) -> dict[str, Any]:
        """返回当前状态的隔离副本。"""
        return json.loads(json.dumps(self._state))


class RuntimeTarget:
    """为已注入测试系统或经独立授权的运行适配器提供显式操作边界。"""

    # 即使连接 fake backend，此适配器也不伪装成 ReplayWorkflow 的模拟目标。
    simulated = False

    _SYSTEM_METHODS: ClassVar[frozenset[str]] = frozenset({
        "resource_identity", "table_count", "inspect_schema", "apply_schema", "load",
        "post_batch", "find_submission", "observe", "original_message", "replay", "restart_worker",
        "owned_processes", "stop_process",
    })

    def __init__(
        self, system: Any, *, project: str, authorization: RuntimeAuthorization | None,
        permission_check: Any = None,
    ) -> None:
        if system is None or not isinstance(project, str) or not project.startswith("real-data-mvp-"):
            raise ValueError("runtime target requires an injected system and dedicated project")
        self._system = system
        self.project = project
        self._authorization = authorization
        self._permission_check = permission_check

    def _invoke(self, name: str, *args: Any) -> Any:
        if name in self._SYSTEM_METHODS:
            self._require_authorization()
        method = getattr(self._system, name, None)
        if not callable(method):
            raise WorkflowBlocked(f"runtime adapter lacks {name}")
        try:
            return method(*args)
        except Exception:  # noqa: BLE001 - 后端异常（包括授权错误）可能包含凭据或 SQL。
            raise WorkflowBlocked(f"runtime operation {name} failed safely") from None

    def _require_authorization(self) -> None:
        auth = self._authorization
        checker = self._permission_check
        if (auth is None or auth.project != self.project or auth.scope != "isolated-real-data-mvp"
                or not callable(checker)):
            raise PermissionError("runtime operation requires separately supplied project authorization")
        try:
            permitted = checker(auth, self.project, "isolated-real-data-mvp")
        except Exception:  # noqa: BLE001 - 不回显授权检查器内部信息。
            raise PermissionError("runtime authorization could not be verified") from None
        if permitted is not True:
            raise PermissionError("runtime operation is not externally authorized")

    def resource_identity(self) -> dict[str, Any]:
        """读取实际资源身份，并核对专属标签、回环绑定与模板端口。"""
        identity = self._invoke("resource_identity")
        if not isinstance(identity, dict):
            raise WorkflowBlocked("resource identity must be a mapping")
        validate_resource_identity(identity, self.project)
        expected_ports = {"mysql": {13316}, "rabbitmq": {15683, 15682}}
        for service, expected in expected_ports.items():
            ports = {item["port"] for item in identity["services"][service]["bindings"]}
            if ports != expected:
                raise WorkflowBlocked("resource ports do not match the fixed compose template")
        return identity

    def table_count(self) -> int:
        """只读返回目标表数，拒绝布尔或非法计数。"""
        value = self._invoke("table_count")
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise WorkflowBlocked("table count is invalid")
        return value

    def apply_schema(self, path: Path, digest: str) -> None:
        """校验白名单脚本和审定内容摘要后调用注入的 DDL 边界。"""
        if (path.is_symlink() or path.name not in {
                "001_create_database.sql", "002_season_round.sql", "003_question.sql",
                "004_prediction.sql", "006_sync.sql", "007_prediction_request_outbox.sql",
                "008_prediction_outcome.sql",
        } or not re.fullmatch(r"[a-f0-9]{64}", digest)):
            raise WorkflowBlocked("schema path or digest is invalid")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != digest:
            raise WorkflowBlocked("schema content no longer matches its approved digest")
        self._invoke("apply_schema", path, digest)

    def inspect_schema(self) -> Any:
        """调用后端只读结构检查，不申请额外写入权限。"""
        return self._invoke("inspect_schema")

    def load(self, source: dict[str, Any], identity: dict[str, Any]) -> dict[str, Any]:
        """转发最小隔离装载，并要求返回完整且合法的实际 ID 映射。"""
        if not isinstance(source, dict) or not isinstance(identity, dict):
            raise WorkflowBlocked("load input must be mappings")
        try:
            from f1_predict.replay.isolated import _validate_source

            normalized = _validate_source(source, identity)
        except (ValueError, TypeError, KeyError):
            raise WorkflowBlocked("load source or source identity is invalid") from None
        options = normalized["options"]
        expected_options = {str(item["optionId"]) for item in options}
        result = self._invoke("load", source, identity)
        required = {"questionId", "snapshotId", "roundId", "optionIds"}
        if (not isinstance(result, dict) or set(result) != required
                or any(isinstance(result.get(key), bool) or not isinstance(result.get(key), int)
                       or result[key] <= 0 for key in ("questionId", "snapshotId", "roundId"))
                or not isinstance(result.get("optionIds"), dict) or len(result["optionIds"]) != 2
                or set(result["optionIds"]) != expected_options
                or any(not isinstance(key, str) or not key or isinstance(value, bool)
                       or not isinstance(value, int) or value <= 0
                       for key, value in result["optionIds"].items())):
            raise WorkflowBlocked("load adapter did not return a complete actual identifier mapping")
        return result

    def post_batch(self, round_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        """仅允许冻结单题请求，并校验后端返回的唯一批次和任务。"""
        if isinstance(round_id, bool) or not isinstance(round_id, int) or round_id <= 0:
            raise WorkflowBlocked("round identity is invalid")
        keys = {"questionIds", "dataCutoff", "modelVersion", "promptVersion", "featureVersion"}
        questions = payload.get("questionIds") if isinstance(payload, dict) else None
        if (not isinstance(payload, dict) or set(payload) != keys or not isinstance(questions, list)
                or len(questions) != 1 or isinstance(questions[0], bool)
                or not isinstance(questions[0], int) or questions[0] <= 0
                or any(not isinstance(payload[key], str) or not payload[key].strip()
                       for key in keys - {"questionIds"})
                or not _is_utc_timestamp(payload.get("dataCutoff"))):
            raise WorkflowBlocked("only a minimal single-question batch payload is permitted")
        result = self._invoke("post_batch", round_id, dict(payload))
        if (not isinstance(result, dict) or isinstance(result.get("roundId"), bool)
                or result.get("roundId") != round_id or isinstance(result.get("questionCount"), bool)
                or result.get("questionCount") != 1
                or isinstance(result.get("batchId"), bool) or not isinstance(result.get("batchId"), int)
                or result["batchId"] <= 0 or not isinstance(result.get("jobIds"), list)
                or len(result["jobIds"]) != 1 or not isinstance(result["jobIds"][0], str)
                or not result["jobIds"][0]):
            raise WorkflowBlocked("batch adapter returned an invalid single-question result")
        return result

    def find_submission(self, round_id: int, payload: dict[str, Any]) -> dict[str, Any] | None:
        """按冻结请求只读查询批次；不存在时返回 None 且绝不执行 POST。"""
        if isinstance(round_id, bool) or not isinstance(round_id, int) or round_id <= 0:
            raise WorkflowBlocked("submission lookup round identity is invalid")
        if not isinstance(payload, dict):
            raise WorkflowBlocked("submission lookup payload is invalid")
        request = {"roundId": round_id, "payload": payload}
        request_hash = hashlib.sha256(json.dumps(
            request, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")).hexdigest()
        result = self._invoke("find_submission", round_id, dict(payload))
        if result is None:
            return None
        if (not isinstance(result, dict) or result.get("requestHash") != request_hash
                or result.get("roundId") != round_id
                or isinstance(result.get("questionCount"), bool) or result.get("questionCount") != 1
                or isinstance(result.get("batchId"), bool) or not isinstance(result.get("batchId"), int)
                or result["batchId"] <= 0 or not isinstance(result.get("jobIds"), list)
                or len(result["jobIds"]) != 1 or not isinstance(result["jobIds"][0], str)
                or not result["jobIds"][0]):
            raise WorkflowBlocked("submission lookup returned an invalid batch")
        return result

    def observe(self, job_id: str, batch_id: int) -> dict[str, Any]:
        """只读观察指定任务，拒绝不完整或跨任务的证据集合。"""
        if not isinstance(job_id, str) or not job_id.strip() or isinstance(batch_id, bool) or not isinstance(batch_id, int) or batch_id <= 0:
            raise WorkflowBlocked("observation identity is invalid")
        result = self._invoke("observe", job_id, batch_id)
        required = {"workerAlive", "job", "batch", "outcome", "sql", "sqlite"}
        if (not isinstance(result, dict) or set(result) != required or result.get("workerAlive") is not True
                or any(not isinstance(result.get(key), dict) for key in required - {"workerAlive"})):
            raise WorkflowBlocked("observation result is incomplete or malformed")
        if (result["job"].get("predictionJobId") != job_id or result["batch"].get("batchId") != batch_id
                or result["outcome"].get("predictionJobId") != job_id
                or result["sql"].get("predictionJobId") != job_id or result["sql"].get("batchId") != batch_id
                or result["sqlite"].get("predictionJobId") != job_id):
            raise WorkflowBlocked("observation result identity does not match request")
        return result

    def original_message(self, job_id: str) -> dict[str, Any]:
        """读取指定任务的原始消息，校验完整 v2 请求与双层消息身份。"""
        if not isinstance(job_id, str) or not job_id.strip():
            raise WorkflowBlocked("message identity is invalid")
        result = self._invoke("original_message", job_id)
        payload = result.get("payload") if isinstance(result, dict) else None
        required_text = ("traceId", "dataCutoff", "modelVersion", "promptVersion", "featureVersion")
        required_ids = ("batchId", "questionId", "questionSnapshotId")
        if (not isinstance(result, dict) or set(result) != {"messageId", "payload"}
                or not isinstance(payload, dict) or payload.get("schemaVersion") != "2"
                or not isinstance(result.get("messageId"), str) or not result["messageId"].strip()
                or payload.get("predictionJobId") != job_id or payload.get("messageId") != result["messageId"]
                or any(not isinstance(payload.get(key), str) or not payload[key].strip() for key in required_text)
                or any(isinstance(payload.get(key), bool) or not isinstance(payload.get(key), int)
                       or payload[key] <= 0 for key in required_ids)
                or not isinstance(payload.get("question"), dict)
                or not isinstance(payload.get("raceContext"), dict)):
            raise WorkflowBlocked("stored original message identity is invalid")
        try:
            from f1_predict.messaging.dto.request_v2 import PredictionRequestV2

            parsed = PredictionRequestV2.model_validate(payload)
        except Exception:  # noqa: BLE001 - 只对 DTO 公开错误，不回显原消息内容。
            raise WorkflowBlocked("stored original message body does not match v2 contract") from None
        if parsed.prediction_job_id != job_id or parsed.message_id != result["messageId"]:
            raise WorkflowBlocked("stored original message body identity is inconsistent")
        return result

    def replay(self, message: dict[str, Any]) -> None:
        """只重投后端读回的原消息，不接纳被调用方修改的载荷。"""
        if not isinstance(message, dict):
            raise WorkflowBlocked("replay requires the original message mapping")
        job_id = message.get("payload", {}).get("predictionJobId") if isinstance(message.get("payload"), dict) else None
        if not isinstance(job_id, str) or not job_id:
            raise WorkflowBlocked("replay must retain original message identity")
        stored = self.original_message(job_id)
        if stored != message:
            raise WorkflowBlocked("replay payload must exactly match the stored original message")
        self._invoke("replay", stored)

    def restart_worker(self) -> dict[str, list[dict[str, Any]]] | None:
        """经独立授权检查重启专属 Worker，并校验其提供的前后进程凭证。"""
        receipt = self._invoke("restart_worker")
        if receipt is None:
            return None
        if not isinstance(receipt, dict) or set(receipt) != {"previous", "replacement"}:
            raise WorkflowBlocked("worker restart must return a complete ownership receipt")
        return {"previous": self._validate_process_records(receipt["previous"]),
                "replacement": self._validate_process_records(receipt["replacement"])}

    def _validate_process_records(self, records: Any) -> list[dict[str, Any]]:
        """校验进程清单结构及 PID、运行号和令牌的唯一性。"""
        required = {"pid", "project", "runId", "startToken"}
        if not isinstance(records, list) or any(
            not isinstance(item, dict) or set(item) != required or item.get("project") != self.project
            or not isinstance(item.get("runId"), str) or not item["runId"].strip()
            or not isinstance(item.get("startToken"), str) or not item["startToken"].strip()
            or isinstance(item.get("pid"), bool) or not isinstance(item.get("pid"), int)
            or item["pid"] <= 0 for item in records
        ):
            raise WorkflowBlocked("owned process inventory is invalid")
        if (len({item["pid"] for item in records}) != len(records)
                or len({item["startToken"] for item in records}) != len(records)):
            raise WorkflowBlocked("owned process identity values must be unique")
        return [dict(item) for item in records]

    def owned_processes(self) -> list[dict[str, Any]]:
        """读取当前进程清单；字段本身不构成可停止的所有权证明。"""
        return self._validate_process_records(self._invoke("owned_processes"))

    def stop_process(self, process: dict[str, Any]) -> None:
        """单条停止不建立完整所有权证明，必须经 stop_owned_processes。"""
        del process
        raise WorkflowBlocked("single-process stop is forbidden; verify the complete recorded inventory")

    def stop_owned_processes(self, expected: list[dict[str, Any]]) -> None:
        """先逐项核验登记与当前 PID/run/token 集合，再执行任何停止操作。"""
        if not isinstance(expected, list) or any(not isinstance(item, dict) for item in expected):
            raise WorkflowBlocked("recorded process inventory is invalid")
        current = self.owned_processes()
        identity = lambda item: (item.get("pid"), item.get("project"), item.get("runId"), item.get("startToken"))
        if (len(expected) != len(current) or sorted(map(identity, expected)) != sorted(map(identity, current))
                or any(item.get("project") != self.project for item in current)):
            raise WorkflowBlocked("complete process ownership inventory changed before stop")
        for process in expected:
            self._invoke("stop_process", process)


def _is_utc_timestamp(value: Any) -> bool:
    """使用回放共用解析器校验严格 UTC 时间戳。"""
    if not isinstance(value, str) or "T" not in value or not value.endswith(("Z", "+00:00")):
        return False
    try:
        from f1_predict.replay.manifest import parse_utc

        parse_utc(value, field="dataCutoff")
    except (TypeError, ValueError):
        return False
    return True


class ReplayRuntime:
    """仅供明确注入的离线适配测试执行带操作意图的运行流程。"""

    _SCHEMA_FILES = frozenset({
        "001_create_database.sql", "002_season_round.sql", "003_question.sql",
        "004_prediction.sql", "006_sync.sql", "007_prediction_request_outbox.sql",
        "008_prediction_outcome.sql",
    })
    _TERMINAL = frozenset({"STOPPED", "FAILED", "RECOVERY_CONFLICT"})

    def __init__(self, target: RuntimeTarget, journal: OperationJournal, *, mode: str) -> None:
        if mode != OperationJournal.mode or not isinstance(target, RuntimeTarget):
            raise WorkflowBlocked("runtime supports only an injected OFFLINE_ADAPTER_TEST target")
        if journal.project != target.project:
            raise WorkflowBlocked("runtime target and journal projects differ")
        self.target = target
        self.journal = journal

    def _identity(self) -> dict[str, Any]:
        identity = self.target.resource_identity()
        digest = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":"),
                                 ensure_ascii=False).encode("utf-8")).hexdigest()
        if digest != self.journal.identity_hash:
            raise WorkflowBlocked("resource identity changed during the run")
        return identity

    @staticmethod
    def _validate_submission_message(original: dict[str, Any], state: dict[str, Any], job_id: str) -> str:
        """将原始 v2 消息的冻结身份绑定到已提交请求意图。"""
        payload = original.get("payload") if isinstance(original, dict) else None
        intent = state.get("submissionIntent")
        submitted = intent.get("payload") if isinstance(intent, dict) else None
        question_ids = submitted.get("questionIds") if isinstance(submitted, dict) else None
        race = payload.get("raceContext") if isinstance(payload, dict) else None
        expected_intent_hash = hashlib.sha256(json.dumps(
            intent, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")).hexdigest() if isinstance(intent, dict) else None
        if (not isinstance(payload, dict) or not isinstance(submitted, dict) or not isinstance(race, dict)
                or state.get("submissionIntentHash") != expected_intent_hash
                or payload.get("predictionJobId") != job_id
                or payload.get("batchId") != state.get("batchId")
                or not isinstance(question_ids, list) or len(question_ids) != 1
                or payload.get("questionId") != question_ids[0]
                or payload.get("dataCutoff") != submitted.get("dataCutoff")
                or payload.get("modelVersion") != submitted.get("modelVersion")
                or payload.get("promptVersion") != submitted.get("promptVersion")
                or payload.get("featureVersion") != submitted.get("featureVersion")
                or race.get("roundId") != intent.get("roundId")):
            raise WorkflowBlocked("original message identity does not match submitted intent")
        return hashlib.sha256(json.dumps(
            original, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")).hexdigest()

    def prepare(self, schema_scripts: list[tuple[Path, str]], approved_hash: str) -> dict[str, Any]:
        """持久化 DDL 意图后才调用注入目标；不确定时仅允许人工只读核验。"""
        self._identity()
        state = self.journal.state
        if state["stage"] in {"APPLYING", "SCHEMA_UNCERTAIN"}:
            raise WorkflowBlocked("schema result is uncertain; automatic DDL retry is forbidden")
        if state["stage"] in {"READY", "SUBMISSION_INTENT", "SUBMISSION_UNCERTAIN", "SUBMITTED", "OBSERVED", "REPLAYED", "RESTARTED", "STOPPED"}:
            if not isinstance(schema_scripts, list) or not schema_scripts:
                raise WorkflowBlocked("schema plan is required to resume preparation")
            current_plan = []
            for path, digest in schema_scripts:
                if (not isinstance(path, Path) or path.is_symlink() or path.name not in self._SCHEMA_FILES
                        or not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest)):
                    raise WorkflowBlocked("schema script is not on the approved allowlist")
                try:
                    actual = hashlib.sha256(path.read_bytes()).hexdigest()
                except OSError:
                    raise WorkflowBlocked("schema script could not be read safely") from None
                if actual != digest:
                    raise WorkflowBlocked("schema content no longer matches its approved digest")
                current_plan.append((path, digest))
            expected_names = ["001_create_database.sql", "002_season_round.sql", "003_question.sql",
                              "004_prediction.sql", "006_sync.sql", "007_prediction_request_outbox.sql",
                              "008_prediction_outcome.sql"]
            plan_hash = hashlib.sha256(json.dumps(
                [[path.name, digest] for path, digest in current_plan], separators=(",", ":"),
                ensure_ascii=False).encode("utf-8")).hexdigest()
            if ([path.name for path, _ in current_plan] != expected_names or plan_hash != approved_hash
                    or state.get("schemaHash") != plan_hash):
                raise WorkflowBlocked("schema plan differs from the persisted approved plan")
            self.target.inspect_schema()
            return state
        if state["stage"] != "NEW" or not isinstance(schema_scripts, list) or not schema_scripts:
            raise WorkflowBlocked("schema preparation input or state is invalid")
        plan: list[tuple[Path, str]] = []
        for path, digest in schema_scripts:
            if (not isinstance(path, Path) or path.is_symlink() or path.name not in self._SCHEMA_FILES
                    or not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest)):
                raise WorkflowBlocked("schema script is not on the approved allowlist")
            try:
                actual = hashlib.sha256(path.read_bytes()).hexdigest()
            except OSError:
                raise WorkflowBlocked("schema script could not be read safely") from None
            if actual != digest:
                raise WorkflowBlocked("schema content no longer matches its approved digest")
            plan.append((path, digest))
        names = [path.name for path, _ in plan]
        expected_names = [
            "001_create_database.sql", "002_season_round.sql", "003_question.sql",
            "004_prediction.sql", "006_sync.sql", "007_prediction_request_outbox.sql",
            "008_prediction_outcome.sql",
        ]
        if names != expected_names:
            raise WorkflowBlocked("schema plan must contain the full ordered allowlist")
        digest_input = json.dumps([[path.name, digest] for path, digest in plan],
                                  separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        if not isinstance(approved_hash, str) or hashlib.sha256(digest_input).hexdigest() != approved_hash:
            raise WorkflowBlocked("schema plan does not match its approved hash")
        if self.target.table_count() != 0:
            raise WorkflowBlocked("schema preparation requires a verified empty target")
        self.journal.save(stage="APPLYING", schemaNames=names, schemaHash=approved_hash)
        try:
            for path, digest in plan:
                self.target.apply_schema(path, digest)
            self.target.inspect_schema()
        except Exception:  # noqa: BLE001 - 保留任何中断场景的 DDL 不确定状态。
            self.journal.save(stage="SCHEMA_UNCERTAIN")
            raise WorkflowBlocked("schema result is uncertain; preserve the target for read-only review") from None
        return self.journal.save(stage="READY")

    def inspect_schema(self) -> Any:
        """仅执行只读 schema 观察，不推进或清除不确定 DDL 状态。"""
        self._identity()
        return self.target.inspect_schema()

    def submit(self, round_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        """先固定单题 POST 意图；丢失响应或重启后不重复提交。"""
        self._identity()
        state = self.journal.state
        required_fields = {"questionIds", "dataCutoff", "modelVersion", "promptVersion", "featureVersion"}
        question_ids = payload.get("questionIds") if isinstance(payload, dict) else None
        if (not isinstance(payload, dict) or set(payload) != required_fields
                or not isinstance(question_ids, list) or len(question_ids) != 1
                or isinstance(question_ids[0], bool) or not isinstance(question_ids[0], int)
                or question_ids[0] <= 0
                or any(not isinstance(payload[key], str) or not payload[key].strip()
                       for key in required_fields - {"questionIds"})
                or not _is_utc_timestamp(payload.get("dataCutoff"))
                or isinstance(round_id, bool) or not isinstance(round_id, int) or round_id <= 0):
            raise WorkflowBlocked("single-question batch payload is invalid")
        frozen_payload = json.loads(json.dumps(payload, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False))
        intent = {"roundId": round_id, "payload": frozen_payload}
        intent_hash = hashlib.sha256(json.dumps(intent, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False).encode("utf-8")).hexdigest()
        if state.get("postAttempted") or state["stage"] in self._TERMINAL:
            if state.get("stage") == "SUBMITTED" and state.get("submissionIntentHash") == intent_hash:
                return state
            raise WorkflowBlocked("submission intent already exists or differs; do not repeat POST")
        if state["stage"] != "READY":
            raise WorkflowBlocked("schema must be verified before submission")
        self.journal.save(stage="SUBMISSION_INTENT", postAttempted=True,
                          submissionIntent=intent, submissionIntentHash=intent_hash)
        try:
            response = self.target.post_batch(round_id, frozen_payload)
        except Exception:  # noqa: BLE001 - 丢失响应一律视为不确定且禁止再次 POST。
            self.journal.save(stage="SUBMISSION_UNCERTAIN")
            raise WorkflowBlocked("submission result is uncertain; POST will not be retried") from None
        return self.journal.save(stage="SUBMITTED", submissionResponse=response,
                                 jobId=response["jobIds"][0], batchId=response["batchId"])

    def verify_submission(self) -> dict[str, Any] | None:
        """响应不明时按已持久化的请求摘要只读查询，不重复提交。"""
        self._identity()
        state = self.journal.state
        if state["stage"] not in {"SUBMISSION_INTENT", "SUBMISSION_UNCERTAIN"}:
            raise WorkflowBlocked("there is no uncertain submission intent to verify")
        intent = state["submissionIntent"]
        result = self.target.find_submission(intent["roundId"], intent["payload"])
        if result is None:
            return None
        response = {key: result[key] for key in ("roundId", "questionCount", "batchId", "jobIds")}
        return self.journal.save(stage="SUBMITTED", submissionResponse=response,
                                 jobId=response["jobIds"][0], batchId=response["batchId"])

    def observe(self, job_id: str, batch_id: int) -> dict[str, Any]:
        """读取指定任务；观察错误不会创建或重试写操作。"""
        self._identity()
        state = self.journal.state
        if state["stage"] in self._TERMINAL:
            raise WorkflowBlocked("terminal run cannot be observed as active")
        if not state.get("postAttempted") or state.get("jobId") != job_id or state.get("batchId") != batch_id:
            raise WorkflowBlocked("observation identity differs from the submitted batch")
        original = self.target.original_message(job_id)
        original_hash = self._validate_submission_message(original, state, job_id)
        if state.get("originalMessageHash") not in {None, original_hash}:
            raise WorkflowBlocked("original message changed between observations")
        evidence = self.target.observe(job_id, batch_id)
        self.journal.save(stage="OBSERVED", observedJobId=job_id, observedBatchId=batch_id,
                          jobId=job_id, batchId=batch_id, originalMessageHash=original_hash,
                          observationHash=hashlib.sha256(json.dumps(
                              evidence, sort_keys=True, separators=(",", ":"), ensure_ascii=False
                          ).encode("utf-8")).hexdigest())
        return evidence

    def replay(self, job_id: str) -> dict[str, Any]:
        """只读回原始 outbox 消息并核对整条身份后重投，拒绝调用方注入载荷。"""
        self._identity()
        state = self.journal.state
        if state["stage"] not in {"SUBMITTED", "OBSERVED", "REPLAYED", "RESTARTED"} or state.get("jobId") != job_id:
            raise WorkflowBlocked("replay job is not the submitted job")
        original = self.target.original_message(job_id)
        saved_hash = self._validate_submission_message(original, state, job_id)
        if state.get("originalMessageHash", saved_hash) != saved_hash:
            raise WorkflowBlocked("original message changed since it was first observed")
        self.journal.save(stage="REPLAY_INTENT", originalMessageHash=saved_hash)
        self.target.replay(original)
        self.journal.save(stage="REPLAYED", originalMessageHash=saved_hash)
        return original

    def restart(self) -> dict[str, Any]:
        """持久化重启意图，仅接受锚定于登记进程的完整前后所有权凭证。"""
        self._identity()
        state = self.journal.state
        if state["stage"] not in {"REPLAYED", "RESTARTED"}:
            raise WorkflowBlocked("worker restart requires a verified original-message replay")
        if state.get("stage") == "RESTARTED":
            return state
        previous = state.get("ownedProcesses")
        if not isinstance(previous, list) or not previous:
            raise WorkflowBlocked("worker restart requires a previously registered process inventory")
        key = lambda item: (item["pid"], item["project"], item["runId"], item["startToken"])
        observed_before = self.target.owned_processes()
        if sorted(map(key, observed_before)) != sorted(map(key, previous)):
            raise WorkflowBlocked("worker process inventory differs before restart")
        self.journal.save(stage="RESTART_INTENT")
        try:
            receipt = self.target.restart_worker()
            if receipt is None:
                if previous or self.target.owned_processes():
                    raise WorkflowBlocked("worker restart receipt is missing")
                replacement = []
            else:
                replacement = receipt["replacement"]
                current = self.target.owned_processes()
                previous_keys = sorted(map(key, previous))
                receipt_previous_keys = sorted(map(key, receipt["previous"]))
                replacement_keys = sorted(map(key, replacement))
                current_keys = sorted(map(key, current))
                if receipt_previous_keys != previous_keys or replacement_keys != current_keys:
                    raise WorkflowBlocked("worker restart ownership receipt does not match the recorded inventory")
                previous_by_key = {key(item): item for item in previous}
                replacement_by_key = {key(item): item for item in replacement}
                unchanged = previous_by_key.keys() & replacement_by_key.keys()
                removed = [item for item in previous if key(item) not in unchanged]
                added = [item for item in replacement if key(item) not in unchanged]
                if (len(removed) != 1 or len(added) != 1
                        or removed[0]["project"] != added[0]["project"]
                        or removed[0]["runId"] != added[0]["runId"]
                        or removed[0]["startToken"] == added[0]["startToken"]
                        or any(item["pid"] == added[0]["pid"] for item in previous if key(item) in unchanged)):
                    raise WorkflowBlocked("worker restart receipt does not prove one same-run worker replacement")
        except Exception:  # noqa: BLE001 - 进程管理器异常不能证明重启是否已经发生。
            self.journal.save(stage="RESTART_UNCERTAIN")
            raise WorkflowBlocked("worker restart result is uncertain") from None
        return self.journal.save(stage="RESTARTED", ownedProcesses=replacement)

    def register_process(self, process: dict[str, Any]) -> dict[str, Any]:
        """将 PID、项目、运行号及启动令牌写入本地预期所有权清单。"""
        self._identity()
        if (not isinstance(process, dict) or set(process) != {"pid", "project", "runId", "startToken"}
                or process.get("project") != self.target.project
                or not isinstance(process.get("runId"), str) or not process["runId"].strip()
                or not isinstance(process.get("startToken"), str) or not process["startToken"].strip()
                or isinstance(process.get("pid"), bool) or not isinstance(process.get("pid"), int)
                or process["pid"] <= 0):
            raise WorkflowBlocked("process record is incomplete or belongs to another project")
        state = self.journal.state
        if state["stage"] in self._TERMINAL:
            raise WorkflowBlocked("terminal run cannot register a process")
        processes = list(state.get("ownedProcesses", []))
        if process in processes:
            return state
        if any(item.get("pid") == process["pid"] for item in processes):
            raise WorkflowBlocked("PID is already registered with another start token")
        return self.journal.save(stage=state["stage"], ownedProcesses=[*processes, dict(process)])

    def stop_all(self) -> dict[str, Any]:
        """先比对全部登记和当前进程身份，任一差异都会阻止所有 stop。"""
        self._identity()
        state = self.journal.state
        if state["stage"] == "STOPPED":
            return state
        if state["stage"] == "STOP_INTENT":
            raise WorkflowBlocked("interrupted stop intent requires separate manual verification")
        expected = state.get("ownedProcesses", [])
        current = self.target.owned_processes()
        key = lambda item: (item.get("pid"), item.get("project"), item.get("runId"), item.get("startToken"))
        if (not isinstance(expected, list) or not isinstance(current, list)
                or len(expected) != len(current) or sorted(map(key, expected)) != sorted(map(key, current))
                or any(item.get("project") != self.target.project for item in current)):
            raise WorkflowBlocked("process inventory differs from the complete recorded ownership set")
        self.journal.save(stage="STOP_INTENT")
        self.target.stop_owned_processes(expected)
        return self.journal.save(stage="STOPPED")
