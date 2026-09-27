"""验证落盘后去重、相同任务不同消息隔离与重启恢复。"""

from __future__ import annotations

import sqlite3
from pathlib import Path

from f1_predict.messaging.dto.request_v2 import PredictionRequestV2
from f1_predict.reliability.idempotency import InboxStore


def test_inbox_deduplicates_across_restart(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    path = tmp_path / "inbox.sqlite3"
    first = InboxStore(path)
    request = PredictionRequestV2.model_validate(request_v2_payload)
    assert first.save(request) == "inserted"
    assert InboxStore(path).save(request) == "duplicate"
    with sqlite3.connect(path) as connection:
        assert connection.execute("SELECT COUNT(*) FROM request_inbox").fetchone() == (1,)
        assert connection.execute("PRAGMA journal_mode").fetchone() == ("wal",)
        assert connection.execute("PRAGMA synchronous").fetchone() == (2,)


def test_same_job_changed_message_is_conflict(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    store = InboxStore(tmp_path / "inbox.sqlite3")
    assert store.save(PredictionRequestV2.model_validate(request_v2_payload)) == "inserted"
    request_v2_payload["messageId"] = "msg-2"
    assert store.save(PredictionRequestV2.model_validate(request_v2_payload)) == "conflict"


def test_quarantine_preserves_bounded_original_body(tmp_path: Path) -> None:
    path = tmp_path / "inbox.sqlite3"
    store = InboxStore(path)
    store.quarantine(b"invalid" * 4, "malformed", max_bytes=10)
    with sqlite3.connect(path) as connection:
        assert connection.execute(
            "SELECT body, reason FROM request_quarantine"
        ).fetchone() == (b"invalidinv", "malformed")
