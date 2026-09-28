"""持久 inbox：处理消息去重，并为任务执行与终态事件提供可靠存储。"""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import uuid
from collections.abc import Iterator
from contextlib import closing, contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Literal

from f1_predict.messaging.dto.failure_v2 import PredictionFailureV2
from f1_predict.messaging.dto.request_v2 import PredictionRequestV2
from f1_predict.messaging.dto.result_v2 import PredictionResultV2

type StoreResult = Literal["inserted", "duplicate", "conflict"]
type RetryResult = Literal["scheduled", "exhausted"]
type TerminalStatus = Literal["SUCCEEDED", "FAILED"]
_SCHEMA_VERSION = 2


@dataclass(frozen=True)
class InboxClaim:
    """被当前 worker 租约占有的任务快照。"""

    prediction_job_id: str
    message_id: str
    payload_json: str
    attempt: int
    lease_token: str
    feature_snapshot_json: str | None


@dataclass(frozen=True)
class OutboxEvent:
    """尚待发布或已扫描到的终态事件。"""

    event_id: str
    prediction_job_id: str
    outcome_type: Literal["RESULT", "FAILURE"]
    payload_json: str
    created_at: str


class FeatureSnapshotConflict(RuntimeError):
    """同一任务试图覆盖已固定的特征快照。"""


class InboxStore:
    """使用稳定本地卷的 SQLite，不允许多个独立卷上的实例同时消费。"""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        if not self.path.parent.is_dir():
            raise ValueError("SQLite parent directory does not exist")
        self._initialize()

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.path, timeout=5.0, isolation_level=None)
        try:
            connection.execute("PRAGMA busy_timeout=5000")
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA synchronous=FULL")
            connection.row_factory = sqlite3.Row
            yield connection
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._connect() as connection:
            table = connection.execute(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'request_inbox'"
            ).fetchone()
            version = connection.execute("PRAGMA user_version").fetchone()[0]
            if table is None:
                connection.executescript(self._schema_sql())
                connection.execute(f"PRAGMA user_version = {_SCHEMA_VERSION}")
            elif version == 0:
                self._migrate_legacy(connection)
            elif version < _SCHEMA_VERSION:
                self._migrate_v1(connection)
            else:
                self._ensure_support_tables(connection)

    @staticmethod
    def _schema_sql() -> str:
        return """
            CREATE TABLE IF NOT EXISTS request_inbox (
                prediction_job_id TEXT PRIMARY KEY,
                message_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_hash TEXT NOT NULL,
                received_at TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('RECEIVED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
                attempt INTEGER NOT NULL DEFAULT 0 CHECK(attempt >= 0),
                lease_until TEXT,
                next_attempt_at TEXT,
                feature_snapshot_json TEXT,
                feature_snapshot_hash TEXT,
                lease_token TEXT,
                terminal_json TEXT,
                terminal_at TEXT
            );
            CREATE TABLE IF NOT EXISTS request_quarantine (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                body BLOB NOT NULL,
                reason TEXT NOT NULL,
                received_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS terminal_outbox (
                event_id TEXT PRIMARY KEY,
                prediction_job_id TEXT NOT NULL UNIQUE,
                outcome_type TEXT NOT NULL CHECK(outcome_type IN ('RESULT', 'FAILURE')),
                payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                published_at TEXT,
                FOREIGN KEY(prediction_job_id) REFERENCES request_inbox(prediction_job_id)
            );
            CREATE INDEX IF NOT EXISTS idx_request_inbox_claim
                ON request_inbox(status, next_attempt_at, lease_until, received_at);
            CREATE INDEX IF NOT EXISTS idx_terminal_outbox_pending
                ON terminal_outbox(published_at, created_at);
        """

    @staticmethod
    def _execute_schema(connection: sqlite3.Connection, schema: str) -> None:
        for statement in schema.split(";"):
            if statement.strip():
                connection.execute(statement)

    def _ensure_support_tables(self, connection: sqlite3.Connection) -> None:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS request_quarantine (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                body BLOB NOT NULL,
                reason TEXT NOT NULL,
                received_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS terminal_outbox (
                event_id TEXT PRIMARY KEY,
                prediction_job_id TEXT NOT NULL UNIQUE,
                outcome_type TEXT NOT NULL CHECK(outcome_type IN ('RESULT', 'FAILURE')),
                payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                published_at TEXT,
                FOREIGN KEY(prediction_job_id) REFERENCES request_inbox(prediction_job_id)
            );
            CREATE INDEX IF NOT EXISTS idx_request_inbox_claim
                ON request_inbox(status, next_attempt_at, lease_until, received_at);
            CREATE INDEX IF NOT EXISTS idx_terminal_outbox_pending
                ON terminal_outbox(published_at, created_at);
            """
        )

    def _migrate_v1(self, connection: sqlite3.Connection) -> None:
        """保留现有任务与快照，添加租约 fencing 和 outbox 类型。"""
        backup_path = Path(f"{self.path}.pre-migration-v{_SCHEMA_VERSION}.bak")
        temporary_backup_path = Path(f"{backup_path}.tmp")
        try:
            with (
                closing(sqlite3.connect(self.path, timeout=5.0)) as source,
                closing(sqlite3.connect(temporary_backup_path)) as backup,
            ):
                source.backup(backup)
            os.replace(temporary_backup_path, backup_path)
        finally:
            if temporary_backup_path.exists():
                temporary_backup_path.unlink()

        connection.execute("BEGIN IMMEDIATE")
        try:
            connection.execute("ALTER TABLE request_inbox ADD COLUMN lease_token TEXT")
            connection.execute(
                "ALTER TABLE terminal_outbox ADD COLUMN outcome_type TEXT NOT NULL "
                "DEFAULT 'RESULT' CHECK(outcome_type IN ('RESULT', 'FAILURE'))"
            )
            rows = connection.execute(
                """SELECT terminal_outbox.event_id, terminal_outbox.prediction_job_id,
                          terminal_outbox.payload_json, request_inbox.status
                FROM terminal_outbox JOIN request_inbox USING (prediction_job_id)"""
            ).fetchall()
            for row in rows:
                envelope = json.loads(row["payload_json"])
                dto_data = envelope.get("result", envelope)
                dto_type = (
                    PredictionResultV2 if row["status"] == "SUCCEEDED" else PredictionFailureV2
                )
                dto_payload = dto_type.model_validate(dto_data).model_dump(
                    mode="json", by_alias=True
                )
                if dto_payload["predictionJobId"] != row["prediction_job_id"]:
                    raise sqlite3.DatabaseError("outbox DTO job id mismatch")
                outcome_type = "RESULT" if row["status"] == "SUCCEEDED" else "FAILURE"
                normalized_json = self._canonical_json(dto_payload)
                connection.execute(
                    "UPDATE terminal_outbox SET outcome_type = ?, payload_json = ? WHERE event_id = ?",
                    (outcome_type, normalized_json, row["event_id"]),
                )
                connection.execute(
                    "UPDATE request_inbox SET terminal_json = ? WHERE prediction_job_id = ?",
                    (normalized_json, row["prediction_job_id"]),
                )
            connection.execute(
                """CREATE TABLE IF NOT EXISTS request_quarantine (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    body BLOB NOT NULL,
                    reason TEXT NOT NULL,
                    received_at TEXT NOT NULL
                )"""
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_request_inbox_claim "
                "ON request_inbox(status, next_attempt_at, lease_until, received_at)"
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_terminal_outbox_pending "
                "ON terminal_outbox(published_at, created_at)"
            )
            connection.execute(f"PRAGMA user_version = {_SCHEMA_VERSION}")
            connection.commit()
        except Exception:
            connection.rollback()
            raise

    def _migrate_legacy(self, connection: sqlite3.Connection) -> None:
        """用一致性备份保护旧库，再在事务内校验并重建 RECEIVED 表。"""
        backup_path = Path(f"{self.path}.pre-migration-v{_SCHEMA_VERSION}.bak")
        temporary_backup_path = Path(f"{backup_path}.tmp")
        try:
            with (
                closing(sqlite3.connect(self.path, timeout=5.0)) as source,
                closing(sqlite3.connect(temporary_backup_path)) as backup,
            ):
                source.backup(backup)
            os.replace(temporary_backup_path, backup_path)
        finally:
            if temporary_backup_path.exists():
                temporary_backup_path.unlink()

        connection.execute("BEGIN IMMEDIATE")
        try:
            before = connection.execute(
                "SELECT prediction_job_id, message_id, payload_json, payload_hash, received_at, status "
                "FROM request_inbox ORDER BY prediction_job_id"
            ).fetchall()
            before_count, before_hash = self._rows_digest(before)
            for row in before:
                expected_payload_hash = hashlib.sha256(
                    row["payload_json"].encode("utf-8")
                ).hexdigest()
                if row["payload_hash"] != expected_payload_hash:
                    raise sqlite3.DatabaseError(
                        f"request inbox payload hash mismatch: {row['prediction_job_id']}"
                    )
            connection.execute("ALTER TABLE request_inbox RENAME TO request_inbox_legacy")
            self._execute_schema(connection, self._schema_sql())
            connection.execute(
                """INSERT INTO request_inbox
                (prediction_job_id, message_id, payload_json, payload_hash, received_at, status)
                SELECT prediction_job_id, message_id, payload_json, payload_hash, received_at, status
                FROM request_inbox_legacy"""
            )
            after = connection.execute(
                "SELECT prediction_job_id, message_id, payload_json, payload_hash, received_at, status "
                "FROM request_inbox ORDER BY prediction_job_id"
            ).fetchall()
            after_count, after_hash = self._rows_digest(after)
            if (before_count, before_hash) != (after_count, after_hash):
                raise sqlite3.DatabaseError("request inbox migration verification failed")
            connection.execute("DROP TABLE request_inbox_legacy")
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_request_inbox_claim "
                "ON request_inbox(status, next_attempt_at, lease_until, received_at)"
            )
            connection.execute(
                """CREATE TABLE IF NOT EXISTS request_quarantine (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    body BLOB NOT NULL,
                    reason TEXT NOT NULL,
                    received_at TEXT NOT NULL
                )"""
            )
            connection.execute(f"PRAGMA user_version = {_SCHEMA_VERSION}")
            connection.commit()
        except Exception:
            connection.rollback()
            raise

    @staticmethod
    def _rows_digest(rows: list[sqlite3.Row] | list[tuple[object, ...]]) -> tuple[int, str]:
        digest = hashlib.sha256()
        for row in rows:
            digest.update(
                json.dumps(tuple(row), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            )
            digest.update(b"\n")
        return len(rows), digest.hexdigest()

    @staticmethod
    def _canonical_json(value: object) -> str:
        return json.dumps(
            value, sort_keys=True, ensure_ascii=False, separators=(",", ":")
        )

    @staticmethod
    def _timestamp(value: datetime | None = None) -> datetime:
        current = value or datetime.now(UTC)
        if current.tzinfo is None:
            raise ValueError("datetime must be timezone-aware")
        return current.astimezone(UTC)

    def save(self, request: PredictionRequestV2) -> StoreResult:
        """在同一事务中插入或核对首份不可变载荷。"""
        payload = self._canonical_json(request.model_dump(mode="json", by_alias=True))
        digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute(
                    "SELECT payload_hash, payload_json FROM request_inbox WHERE prediction_job_id = ?",
                    (request.prediction_job_id,),
                ).fetchone()
                if row is not None:
                    result: StoreResult = (
                        "duplicate" if (row["payload_hash"], row["payload_json"]) == (digest, payload)
                        else "conflict"
                    )
                else:
                    connection.execute(
                        """INSERT INTO request_inbox
                        (prediction_job_id, message_id, payload_json, payload_hash, received_at, status)
                        VALUES (?, ?, ?, ?, ?, 'RECEIVED')""",
                        (
                            request.prediction_job_id,
                            request.message_id,
                            payload,
                            digest,
                            datetime.now(UTC).isoformat(),
                        ),
                    )
                    result = "inserted"
                connection.commit()
                return result
            except Exception:
                connection.rollback()
                raise

    def claim_next(
        self, *, lease_seconds: int, now: datetime | None = None
    ) -> InboxClaim | None:
        """原子认领一条到期任务；过期 RUNNING 租约可以被恢复。"""
        if lease_seconds <= 0:
            raise ValueError("lease_seconds must be positive")
        current = self._timestamp(now)
        current_text = current.isoformat()
        lease_until = (current + timedelta(seconds=lease_seconds)).isoformat()
        lease_token = str(uuid.uuid4())
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute(
                    """SELECT prediction_job_id, message_id, payload_json, payload_hash, attempt,
                              feature_snapshot_json, feature_snapshot_hash
                    FROM request_inbox
                    WHERE (status = 'RECEIVED' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
                       OR (status = 'RUNNING' AND lease_until <= ?)
                    ORDER BY COALESCE(next_attempt_at, received_at), received_at, prediction_job_id
                    LIMIT 1""",
                    (current_text, current_text),
                ).fetchone()
                if row is None:
                    connection.commit()
                    return None
                payload_hash = hashlib.sha256(row["payload_json"].encode("utf-8")).hexdigest()
                if payload_hash != row["payload_hash"]:
                    raise sqlite3.DatabaseError("request inbox payload hash mismatch")
                snapshot_json = row["feature_snapshot_json"]
                snapshot_hash = row["feature_snapshot_hash"]
                if snapshot_json is None:
                    if snapshot_hash is not None:
                        raise sqlite3.DatabaseError("request inbox feature snapshot hash mismatch")
                elif hashlib.sha256(snapshot_json.encode("utf-8")).hexdigest() != snapshot_hash:
                    raise sqlite3.DatabaseError("request inbox feature snapshot hash mismatch")
                next_attempt = row["attempt"] + 1
                connection.execute(
                    """UPDATE request_inbox
                    SET status = 'RUNNING', attempt = ?, lease_until = ?, lease_token = ?,
                        next_attempt_at = NULL
                    WHERE prediction_job_id = ?""",
                    (next_attempt, lease_until, lease_token, row["prediction_job_id"]),
                )
                connection.commit()
                return InboxClaim(
                    prediction_job_id=row["prediction_job_id"],
                    message_id=row["message_id"],
                    payload_json=row["payload_json"],
                    attempt=next_attempt,
                    lease_token=lease_token,
                    feature_snapshot_json=row["feature_snapshot_json"],
                )
            except Exception:
                connection.rollback()
                raise

    @staticmethod
    def _require_lease(
        row: sqlite3.Row | None,
        prediction_job_id: str,
        lease_token: str,
        current: datetime,
    ) -> None:
        if row is None:
            raise KeyError(prediction_job_id)
        if (
            row["status"] != "RUNNING"
            or row["lease_token"] != lease_token
            or row["lease_until"] is None
            or row["lease_until"] <= current.isoformat()
        ):
            raise RuntimeError("task lease is no longer active")

    def save_feature_snapshot(
        self,
        prediction_job_id: str,
        lease_token: str,
        snapshot: dict[str, object],
        *,
        now: datetime | None = None,
    ) -> bool:
        """固定首次特征快照；重复写入相同快照安全，修改快照被拒绝。"""
        current = self._timestamp(now)
        snapshot_json = self._canonical_json(snapshot)
        snapshot_hash = hashlib.sha256(snapshot_json.encode("utf-8")).hexdigest()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute(
                    "SELECT status, lease_token, lease_until, feature_snapshot_json, feature_snapshot_hash "
                    "FROM request_inbox WHERE prediction_job_id = ?",
                    (prediction_job_id,),
                ).fetchone()
                self._require_lease(row, prediction_job_id, lease_token, current)
                if row["feature_snapshot_json"] is not None:
                    if (row["feature_snapshot_json"], row["feature_snapshot_hash"]) != (
                        snapshot_json,
                        snapshot_hash,
                    ):
                        raise FeatureSnapshotConflict(prediction_job_id)
                    connection.commit()
                    return False
                connection.execute(
                    """UPDATE request_inbox SET feature_snapshot_json = ?, feature_snapshot_hash = ?
                    WHERE prediction_job_id = ?""",
                    (snapshot_json, snapshot_hash, prediction_job_id),
                )
                connection.commit()
                return True
            except Exception:
                connection.rollback()
                raise

    def retry(
        self,
        prediction_job_id: str,
        lease_token: str,
        *,
        max_attempts: int,
        base_delay_seconds: int,
        max_delay_seconds: int,
        now: datetime | None = None,
    ) -> RetryResult:
        """指数退避重试；达到次数上限时由调用方生成失败 DTO 并完成任务。"""
        if max_attempts < 1 or base_delay_seconds < 0 or max_delay_seconds < 0:
            raise ValueError("retry limits must be non-negative and max_attempts positive")
        current = self._timestamp(now)
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute(
                    "SELECT attempt, status, lease_token, lease_until FROM request_inbox "
                    "WHERE prediction_job_id = ?",
                    (prediction_job_id,),
                ).fetchone()
                self._require_lease(row, prediction_job_id, lease_token, current)
                if row["attempt"] >= max_attempts:
                    connection.commit()
                    return "exhausted"
                else:
                    delay = min(
                        base_delay_seconds * (2 ** max(0, row["attempt"] - 1)),
                        max_delay_seconds,
                    )
                    connection.execute(
                        """UPDATE request_inbox
                        SET status = 'RECEIVED', lease_until = NULL, lease_token = NULL,
                            next_attempt_at = ?
                        WHERE prediction_job_id = ?""",
                        ((current + timedelta(seconds=delay)).isoformat(), prediction_job_id),
                    )
                    result = "scheduled"
                connection.commit()
                return result
            except Exception:
                connection.rollback()
                raise

    def finish(
        self,
        prediction_job_id: str,
        lease_token: str,
        *,
        terminal_status: TerminalStatus,
        result: dict[str, object],
        now: datetime | None = None,
    ) -> None:
        """同一事务中完成任务并写入唯一终态 outbox 事件。"""
        current = self._timestamp(now)
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                self._finish_in_transaction(
                    connection, prediction_job_id, lease_token, terminal_status, result, current
                )
                connection.commit()
            except Exception:
                connection.rollback()
                raise

    def _finish_in_transaction(
        self,
        connection: sqlite3.Connection,
        prediction_job_id: str,
        lease_token: str,
        terminal_status: TerminalStatus,
        result: dict[str, object],
        current: datetime,
    ) -> None:
        row = connection.execute(
            "SELECT status, terminal_json, lease_token, lease_until FROM request_inbox "
            "WHERE prediction_job_id = ?",
            (prediction_job_id,),
        ).fetchone()
        if row is None:
            raise KeyError(prediction_job_id)
        dto_type = PredictionResultV2 if terminal_status == "SUCCEEDED" else PredictionFailureV2
        dto = dto_type.model_validate(result)
        if dto.prediction_job_id != prediction_job_id:
            raise ValueError("terminal DTO predictionJobId does not match the claimed task")
        dto_payload = dto.model_dump(mode="json", by_alias=True)
        terminal_json = self._canonical_json(dto_payload)
        if row["status"] in ("SUCCEEDED", "FAILED"):
            if row["status"] == terminal_status and row["terminal_json"] == terminal_json:
                return
            raise RuntimeError("terminal task result is immutable")
        self._require_lease(row, prediction_job_id, lease_token, current)
        created_at = current.isoformat()
        event_id = dto_payload["messageId"]
        outcome_type: Literal["RESULT", "FAILURE"] = (
            "RESULT" if terminal_status == "SUCCEEDED" else "FAILURE"
        )
        connection.execute(
            """UPDATE request_inbox
            SET status = ?, lease_until = NULL, lease_token = NULL, next_attempt_at = NULL,
                terminal_json = ?, terminal_at = ?
            WHERE prediction_job_id = ?""",
            (terminal_status, terminal_json, created_at, prediction_job_id),
        )
        connection.execute(
            """INSERT INTO terminal_outbox
            (event_id, prediction_job_id, outcome_type, payload_json, created_at)
            VALUES (?, ?, ?, ?, ?)""",
            (event_id, prediction_job_id, outcome_type, terminal_json, created_at),
        )

    def pending_outbox(self, limit: int = 100) -> list[OutboxEvent]:
        """按创建顺序扫描未发布的终态事件。"""
        if not 1 <= limit <= 1000:
            raise ValueError("limit must be between 1 and 1000")
        with self._connect() as connection:
            rows = connection.execute(
                """SELECT event_id, prediction_job_id, outcome_type, payload_json, created_at
                FROM terminal_outbox WHERE published_at IS NULL
                ORDER BY created_at, event_id LIMIT ?""",
                (limit,),
            ).fetchall()
            return [OutboxEvent(**dict(row)) for row in rows]

    def mark_outbox_published(
        self, event_id: str, *, now: datetime | None = None
    ) -> bool:
        """标记发布成功；事件不存在或已标记时返回 False。"""
        published_at = self._timestamp(now).isoformat()
        with self._connect() as connection:
            cursor = connection.execute(
                """UPDATE terminal_outbox SET published_at = ?
                WHERE event_id = ? AND published_at IS NULL""",
                (published_at, event_id),
            )
            return cursor.rowcount == 1

    def quarantine(self, body: bytes, reason: str, max_bytes: int) -> None:
        """坏消息须先持久隔离，再允许 RabbitMQ 死信路由。"""
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                connection.execute(
                    "INSERT INTO request_quarantine (body, reason, received_at) VALUES (?, ?, ?)",
                    (body[:max_bytes], reason[:256], datetime.now(UTC).isoformat()),
                )
                connection.commit()
            except Exception:
                connection.rollback()
                raise
