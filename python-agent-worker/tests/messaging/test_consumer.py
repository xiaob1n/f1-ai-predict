"""手动确认与永久坏消息的隔离顺序。"""

from __future__ import annotations

import asyncio
import json
import sqlite3
from pathlib import Path

import pytest

from f1_predict.common.config import Settings
from f1_predict.reliability.idempotency import InboxStore
from f1_predict.worker.consumer import process_delivery


class FakeDelivery:
    def __init__(self, body: bytes) -> None:
        self.body = body
        self.acks = 0
        self.rejects = 0

    async def ack(self) -> None:
        self.acks += 1

    async def reject(self, *, requeue: bool = False) -> None:
        assert not requeue
        self.rejects += 1


def test_ack_only_after_inbox_commit(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    path = tmp_path / "inbox.sqlite3"
    store = InboxStore(path)
    message = FakeDelivery(json.dumps(request_v2_payload).encode())
    asyncio.run(process_delivery(message, store, Settings()))
    assert message.acks == 1
    assert message.rejects == 0
    with sqlite3.connect(path) as connection:
        assert connection.execute("SELECT message_id FROM request_inbox").fetchone() == (
            "msg-1",
        )
    duplicate = FakeDelivery(message.body)
    asyncio.run(process_delivery(duplicate, InboxStore(path), Settings()))
    assert duplicate.acks == 1


def test_invalid_or_conflicting_message_quarantined_before_reject(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    path = tmp_path / "inbox.sqlite3"
    store = InboxStore(path)
    bad = FakeDelivery(b"{bad json")
    asyncio.run(process_delivery(bad, store, Settings()))
    assert bad.acks == 0
    assert bad.rejects == 1
    good = FakeDelivery(json.dumps(request_v2_payload).encode())
    asyncio.run(process_delivery(good, store, Settings()))
    request_v2_payload["messageId"] = "different"
    conflict = FakeDelivery(json.dumps(request_v2_payload).encode())
    asyncio.run(process_delivery(conflict, store, Settings()))
    assert conflict.acks == 0
    assert conflict.rejects == 1
    with sqlite3.connect(path) as connection:
        assert connection.execute("SELECT COUNT(*) FROM request_quarantine").fetchone() == (2,)
        assert connection.execute("SELECT COUNT(*) FROM request_inbox").fetchone() == (1,)


def test_unsupported_version_and_oversized_message_are_quarantined(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    path = tmp_path / "inbox.sqlite3"
    store = InboxStore(path)
    request_v2_payload["schemaVersion"] = "3"
    unknown = FakeDelivery(json.dumps(request_v2_payload).encode())
    asyncio.run(process_delivery(unknown, store, Settings()))
    oversized = FakeDelivery(b"x" * 1100)
    asyncio.run(
        process_delivery(oversized, store, Settings(consumer_max_message_bytes=1024))
    )

    assert (unknown.acks, unknown.rejects) == (0, 1)
    assert (oversized.acks, oversized.rejects) == (0, 1)
    with sqlite3.connect(path) as connection:
        assert connection.execute(
            "SELECT reason, length(body) FROM request_quarantine ORDER BY id"
        ).fetchall() == [
            ("unsupported_schema_version", len(unknown.body)),
            ("oversized_message", 1024),
        ]


def test_disk_error_does_not_ack_or_reject(tmp_path: Path) -> None:
    class BrokenStore:
        def quarantine(self, body: bytes, reason: str, max_bytes: int) -> None:
            raise sqlite3.OperationalError("disk unavailable")

    message = FakeDelivery(b"invalid")
    with pytest.raises(sqlite3.OperationalError):
        asyncio.run(process_delivery(message, BrokenStore(), Settings()))  # type: ignore[arg-type]
    assert (message.acks, message.rejects) == (0, 0)


def test_inbox_error_does_not_ack_or_reject(request_v2_payload: dict[str, object]) -> None:
    class BrokenStore:
        def save(self, request: object) -> None:
            raise sqlite3.OperationalError("disk unavailable")

    message = FakeDelivery(json.dumps(request_v2_payload).encode())
    with pytest.raises(sqlite3.OperationalError):
        asyncio.run(process_delivery(message, BrokenStore(), Settings()))  # type: ignore[arg-type]
    assert (message.acks, message.rejects) == (0, 0)
