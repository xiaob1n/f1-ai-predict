"""单进程持久 inbox：同一业务任务只保存一个已校验的请求。"""

from __future__ import annotations

import hashlib
import json
import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import Literal

from f1_predict.messaging.dto.request_v2 import PredictionRequestV2

type StoreResult = Literal["inserted", "duplicate", "conflict"]


class InboxStore:
    """使用稳定本地卷的 SQLite，不允许多个独立卷上的实例同时消费。"""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        if not self.path.parent.is_dir():
            raise ValueError("SQLite parent directory does not exist")
        with self._connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS request_inbox (
                    prediction_job_id TEXT PRIMARY KEY,
                    message_id TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    payload_hash TEXT NOT NULL,
                    received_at TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status = 'RECEIVED')
                );
                CREATE TABLE IF NOT EXISTS request_quarantine (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    body BLOB NOT NULL,
                    reason TEXT NOT NULL,
                    received_at TEXT NOT NULL
                );
                """
            )

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.path, timeout=5.0, isolation_level=None)
        try:
            connection.execute("PRAGMA busy_timeout=5000")
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA synchronous=FULL")
            yield connection
        finally:
            connection.close()

    def save(self, request: PredictionRequestV2) -> StoreResult:
        """在同一事务中插入或核对首份不可变载荷。"""
        payload = json.dumps(
            request.model_dump(mode="json", by_alias=True),
            sort_keys=True,
            ensure_ascii=False,
            separators=(",", ":"),
        )
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
                        "duplicate" if row == (digest, payload) else "conflict"
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
