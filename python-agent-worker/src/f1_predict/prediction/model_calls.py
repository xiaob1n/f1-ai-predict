"""独立 SQLite 模型调用账本，持久化预算、意图及可恢复结果。"""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import stat
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from f1_predict.prediction.model import ModelCandidate
from f1_predict.prediction.model_vendor_api import ModelInvocation


class CallBudgetExceeded(RuntimeError):
    """调用次数或预留费用超过硬预算。"""


class CallIdentityConflict(RuntimeError):
    """同一任务的新调用与首次冻结输入身份不匹配。"""


class CallNotPermitted(RuntimeError):
    """当前任务仍有未决调用或存在不可重试终态。"""


@dataclass(frozen=True, slots=True)
class ReservedCall:
    """调用预留结果；缓存候选时 should_call 为 False。"""

    call_id: str
    should_call: bool
    cached_candidate: ModelCandidate | None = None


@dataclass(frozen=True, slots=True)
class CallSummary:
    """不含密钥或源文本的持久调用安全摘要。"""

    call_id: str
    prediction_job_id: str
    call_number: int
    attempt: int
    state: str
    requested_model: str
    returned_model: str | None
    prompt_version: str
    feature_version: str
    data_cutoff: str
    allowed_option_ids: tuple[int, ...]
    prompt_hash: str
    input_hash: str
    reserved_microunits: int
    usage: dict[str, int] | str


@dataclass(frozen=True, slots=True)
class LedgerSnapshot:
    """账本累计调用、保守费用预留及安全调用摘要。"""

    reserved_calls: int
    reserved_microunits: int
    max_calls: int
    total_budget_microunits: int
    calls: tuple[CallSummary, ...]


class ModelCallLedger:
    """跨进程安全的调用账本；每次状态变化均使用短 SQLite 事务。"""

    def __init__(
        self, path: str | Path, *, max_calls: int, call_reserve_microunits: int,
        total_budget_microunits: int,
    ) -> None:
        if not isinstance(max_calls, int) or isinstance(max_calls, bool) or not 1 <= max_calls <= 3:
            raise ValueError("max_calls must be an integer between 1 and 3")
        if any(not isinstance(value, int) or isinstance(value, bool) or value <= 0
               for value in (call_reserve_microunits, total_budget_microunits)):
            raise ValueError("cost reserves must be positive integers")
        if call_reserve_microunits > total_budget_microunits:
            raise ValueError("per-call reserve exceeds total budget")
        path_value = Path(path).expanduser().absolute()
        self._path = str(path_value)
        self._secure_database_file()
        self._max_calls = max_calls
        self._call_reserve_microunits = call_reserve_microunits
        self._total_budget_microunits = total_budget_microunits
        with self._session() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("""CREATE TABLE IF NOT EXISTS model_call_ledger (
                call_id TEXT PRIMARY KEY,
                prediction_job_id TEXT NOT NULL,
                call_number INTEGER NOT NULL,
                identity_hash TEXT NOT NULL,
                prompt_hash TEXT NOT NULL,
                config_hash TEXT NOT NULL,
                input_hash TEXT NOT NULL,
                requested_model TEXT NOT NULL,
                attempt INTEGER NOT NULL,
                lease_hash TEXT NOT NULL,
                prompt_version TEXT NOT NULL,
                feature_version TEXT NOT NULL,
                data_cutoff TEXT NOT NULL,
                allowed_option_ids TEXT NOT NULL,
                state TEXT NOT NULL,
                retryable INTEGER NOT NULL DEFAULT 0,
                reserved_microunits INTEGER NOT NULL,
                candidate_json TEXT,
                returned_model TEXT,
                usage_json TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(prediction_job_id, call_number)
            )""")
            db.execute("CREATE INDEX IF NOT EXISTS model_call_job_idx ON model_call_ledger(prediction_job_id, call_number DESC)")
            db.execute("""CREATE TABLE IF NOT EXISTS model_call_settings (
                singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                max_calls INTEGER NOT NULL,
                call_reserve_microunits INTEGER NOT NULL,
                total_budget_microunits INTEGER NOT NULL
            )""")
            settings = db.execute("SELECT * FROM model_call_settings WHERE singleton=1").fetchone()
            if settings is None:
                db.execute(
                    "INSERT INTO model_call_settings VALUES(1,?,?,?)",
                    (max_calls, call_reserve_microunits, total_budget_microunits),
                )
            elif (settings["max_calls"], settings["call_reserve_microunits"],
                  settings["total_budget_microunits"]) != (
                      max_calls, call_reserve_microunits, total_budget_microunits
                  ):
                raise ValueError("ledger budget configuration differs from its persisted approval")
            db.commit()

    @property
    def path(self) -> str:
        """返回账本数据库的绝对路径，不包含任何凭据。"""
        return self._path

    @property
    def max_calls(self) -> int:
        """返回单个任务允许持久预留的最多调用次数。"""
        return self._max_calls

    @property
    def call_reserve_microunits(self) -> int:
        """返回每次调用在发送前冻结的费用预留。"""
        return self._call_reserve_microunits

    @property
    def total_budget_microunits(self) -> int:
        """返回单个任务允许预留的费用总上限。"""
        return self._total_budget_microunits

    def reserve(
        self, invocation: ModelInvocation, *, prompt_hash: str,
        config_hash: str, input_hash: str,
    ) -> ReservedCall:
        """先持久化调用意图并原子核算硬预算，后续网络不在事务内。"""
        if not all(_is_sha256(value) for value in (prompt_hash, config_hash, input_hash)):
            raise ValueError("prompt, config and input identities must be SHA-256 hex digests")
        identity = _identity_hash(invocation, prompt_hash, config_hash, input_hash)
        with self._session() as db:
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute(
                "SELECT * FROM model_call_ledger WHERE prediction_job_id=? ORDER BY call_number DESC LIMIT 1",
                (invocation.prediction_job_id,),
            ).fetchone()
            if previous is not None:
                if previous["identity_hash"] != identity:
                    raise CallIdentityConflict("task call identity differs from its frozen intent")
                if previous["state"] == "success":
                    try:
                        cached = ModelCandidate.model_validate_json(previous["candidate_json"])
                    except (TypeError, ValueError):
                        raise CallNotPermitted("cached model candidate is corrupt") from None
                    if (cached.option_ids[0] not in invocation.allowed_option_ids
                            or previous["requested_model"] != invocation.model_version
                            or previous["returned_model"] != invocation.model_version):
                        raise CallNotPermitted("cached model candidate differs from frozen invocation")
                    return ReservedCall(previous["call_id"], False, cached)
                if previous["state"] == "intent":
                    return ReservedCall(previous["call_id"], False)
                if previous["state"] == "uncertain":
                    raise CallNotPermitted("an earlier call may still be in flight")
                if previous["state"] != "rejected" or not previous["retryable"]:
                    raise CallNotPermitted("task call is not approved for retry")
                if invocation.attempt <= previous["attempt"]:
                    raise CallNotPermitted("retry attempt must advance beyond the recorded lease")
            aggregate = db.execute(
                """SELECT COUNT(*) AS calls, COALESCE(SUM(reserved_microunits),0) AS cost
                   FROM model_call_ledger WHERE prediction_job_id=?""",
                (invocation.prediction_job_id,),
            ).fetchone()
            call_number = (previous["call_number"] + 1) if previous is not None else 1
            if aggregate["calls"] >= self.max_calls or call_number > self.max_calls:
                raise CallBudgetExceeded("model call count budget exhausted")
            if aggregate["cost"] + self.call_reserve_microunits > self.total_budget_microunits:
                raise CallBudgetExceeded("model cost reserve budget exhausted")
            call_id = str(uuid.uuid4())
            db.execute(
                """INSERT INTO model_call_ledger(
                    call_id,prediction_job_id,call_number,identity_hash,prompt_hash,config_hash,
                    input_hash,requested_model,attempt,lease_hash,prompt_version,feature_version,data_cutoff,
                    allowed_option_ids,state,reserved_microunits
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (call_id, invocation.prediction_job_id, call_number, identity, prompt_hash,
                 config_hash, input_hash, invocation.model_version, invocation.attempt,
                 hashlib.sha256(invocation.lease_token.encode("utf-8")).hexdigest(),
                 invocation.prompt_version, invocation.feature_version, invocation.data_cutoff.isoformat(),
                 json.dumps(invocation.allowed_option_ids), "intent", self.call_reserve_microunits),
            )
            return ReservedCall(call_id, True)

    def mark_success(
        self, call_id: str, candidate: ModelCandidate, *, returned_model: str,
        usage: dict[str, Any] | None,
    ) -> None:
        """持久化候选供完成事务失败后恢复；未知 usage 明确记录 UNKNOWN。"""
        candidate = ModelCandidate.model_validate(candidate)
        if not returned_model:
            raise ValueError("returned model is required")
        usage_value = _sanitize_usage(usage)
        with self._session() as db:
            row = db.execute(
                "SELECT requested_model,allowed_option_ids FROM model_call_ledger WHERE call_id=?",
                (call_id,),
            ).fetchone()
        if row is None or returned_model != row["requested_model"]:
            raise CallNotPermitted("success model identity differs from reserved intent")
        if candidate.option_ids[0] not in json.loads(row["allowed_option_ids"]):
            raise CallNotPermitted("success candidate differs from frozen allowed options")
        self._transition(call_id, "success", candidate_json=candidate.model_dump_json(),
                         returned_model=returned_model, usage_json=json.dumps(usage_value, sort_keys=True))

    def mark_rejected(self, call_id: str, *, retryable: bool = False) -> None:
        """记录已知拒绝；只有显式可重试拒绝允许再申请预算内调用。"""
        self._transition(call_id, "rejected", retryable=retryable)

    def mark_uncertain(self, call_id: str) -> None:
        """保留费用预留并禁止对不确定请求自动重发。"""
        self._transition(call_id, "uncertain")

    def snapshot(self) -> LedgerSnapshot:
        """返回全账本调用摘要和费用预留；预算上限按任务分别执行。"""
        with self._session() as db:
            row = db.execute(
                "SELECT COUNT(*) AS calls, COALESCE(SUM(reserved_microunits),0) AS cost FROM model_call_ledger"
            ).fetchone()
            rows = db.execute(
                """SELECT call_id,prediction_job_id,call_number,attempt,state,requested_model,
                          returned_model,prompt_version,feature_version,data_cutoff,allowed_option_ids,
                          prompt_hash,input_hash,reserved_microunits,usage_json
                   FROM model_call_ledger ORDER BY created_at,call_number"""
            ).fetchall()
        summaries = tuple(
            CallSummary(
                call_id=item["call_id"],
                prediction_job_id=item["prediction_job_id"],
                call_number=item["call_number"],
                attempt=item["attempt"],
                state=item["state"],
                requested_model=item["requested_model"],
                returned_model=item["returned_model"],
                prompt_version=item["prompt_version"],
                feature_version=item["feature_version"],
                data_cutoff=item["data_cutoff"],
                allowed_option_ids=tuple(json.loads(item["allowed_option_ids"])),
                prompt_hash=item["prompt_hash"],
                input_hash=item["input_hash"],
                reserved_microunits=item["reserved_microunits"],
                usage=_decode_usage(item["usage_json"]),
            )
            for item in rows
        )
        return LedgerSnapshot(
            row["calls"], row["cost"], self.max_calls,
            self.total_budget_microunits, summaries,
        )

    def _transition(self, call_id: str, state: str, **fields: Any) -> None:
        allowed_fields = {"candidate_json", "returned_model", "usage_json", "retryable"}
        if set(fields) - allowed_fields:
            raise ValueError("unsupported ledger update")
        assignments = ["state=?", *(f"{name}=?" for name in fields)]
        values = [state, *fields.values(), call_id]
        with self._session() as db:
            db.execute("BEGIN IMMEDIATE")
            cursor = db.execute(
                f"UPDATE model_call_ledger SET {', '.join(assignments)} WHERE call_id=? AND state='intent'",
                values,
            )
            if cursor.rowcount != 1:
                raise CallNotPermitted("call intent is missing or already completed")

    def _secure_database_file(self) -> None:
        """只允许私有目录中的当前用户独占普通账本文件。"""
        path = Path(self._path)
        for component in (path, *path.parents):
            try:
                component_stat = component.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(component_stat.st_mode):
                raise ValueError("model ledger path components must not be symlinks")
        try:
            parent_stat = path.parent.stat()
        except OSError:
            raise ValueError("model ledger parent directory must already exist") from None
        if (not stat.S_ISDIR(parent_stat.st_mode) or parent_stat.st_uid != os.geteuid()
                or stat.S_IMODE(parent_stat.st_mode) != 0o700):
            raise ValueError("model ledger parent directory must be private and owned by the current user")

        flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
        try:
            descriptor = os.open(self._path, flags, stat.S_IRUSR | stat.S_IWUSR)
        except OSError:
            raise ValueError("model ledger file cannot be opened securely") from None
        try:
            file_stat = os.fstat(descriptor)
            if (not stat.S_ISREG(file_stat.st_mode) or file_stat.st_uid != os.geteuid()
                    or file_stat.st_nlink != 1 or stat.S_IMODE(file_stat.st_mode) != 0o600):
                raise ValueError("model ledger must be a private owned regular file without hard links")
        finally:
            os.close(descriptor)
        for suffix in ("-wal", "-shm"):
            sidecar = Path(f"{self._path}{suffix}")
            try:
                sidecar_stat = sidecar.lstat()
            except FileNotFoundError:
                continue
            if (not stat.S_ISREG(sidecar_stat.st_mode) or sidecar_stat.st_uid != os.geteuid()
                    or sidecar_stat.st_nlink != 1 or stat.S_IMODE(sidecar_stat.st_mode) != 0o600):
                raise ValueError("model ledger sidecars must be private owned regular files")

    @contextmanager
    def _session(self) -> Iterator[sqlite3.Connection]:
        """每次操作显式关闭连接，并强制 WAL/FULL 持久化策略。"""
        self._secure_database_file()
        db = sqlite3.connect(self._path, timeout=30, isolation_level=None)
        db.row_factory = sqlite3.Row
        try:
            db.execute("PRAGMA busy_timeout=30000")
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("PRAGMA synchronous=FULL")
            self._secure_database_file()
            yield db
            if db.in_transaction:
                db.commit()
        finally:
            if db.in_transaction:
                db.rollback()
            db.close()


def _sanitize_usage(usage: dict[str, Any] | None) -> dict[str, int] | str:
    if not isinstance(usage, dict):
        return "UNKNOWN"
    result = {
        key: value
        for key in ("prompt_tokens", "completion_tokens", "total_tokens")
        if isinstance((value := usage.get(key)), int) and not isinstance(value, bool) and value > 0
    }
    return result or "UNKNOWN"


def _decode_usage(value: str | None) -> dict[str, int] | str:
    if value is None:
        return "UNKNOWN"
    try:
        decoded = json.loads(value)
    except (TypeError, ValueError):
        return "UNKNOWN"
    return decoded if decoded == "UNKNOWN" or (
        isinstance(decoded, dict)
        and set(decoded) <= {"prompt_tokens", "completion_tokens", "total_tokens"}
        and all(isinstance(item, int) and not isinstance(item, bool) and item > 0 for item in decoded.values())
    ) else "UNKNOWN"


def _is_sha256(value: object) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(
        character in "0123456789abcdef" for character in value
    )


def _identity_hash(invocation: ModelInvocation, prompt_hash: str, config_hash: str, input_hash: str) -> str:
    payload = {
        "job": invocation.prediction_job_id,
        "options": invocation.allowed_option_ids,
        "cutoff": invocation.data_cutoff.isoformat(),
        "model": invocation.model_version,
        "promptVersion": invocation.prompt_version,
        "featureVersion": invocation.feature_version,
        "promptHash": prompt_hash,
        "configHash": config_hash,
        "inputHash": input_hash,
    }
    serialized = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()
