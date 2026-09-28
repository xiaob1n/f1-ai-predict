"""验证落盘后去重、相同任务不同消息隔离与重启恢复。"""

from __future__ import annotations

import json
import sqlite3
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from f1_predict.messaging.dto.failure_v2 import (
    PredictionFailureCode,
    PredictionFailureV2,
)
from f1_predict.messaging.dto.request_v2 import PredictionRequestV2
from f1_predict.messaging.dto.result_v2 import PredictionResultV2, SelectedOptionV2
from f1_predict.reliability.idempotency import (
    FeatureSnapshotConflict,
    InboxStore,
)


def _success_dto(request: PredictionRequestV2) -> dict[str, object]:
    return PredictionResultV2.from_request(
        request,
        message_id="result-message",
        selected_options=[SelectedOptionV2(optionId=1, position=1)],
        confidence=0.75,
        reasoning_summary="基于冻结特征生成预测",
        evidence=[],
        generated_at=request.data_cutoff,
    ).model_dump(mode="json", by_alias=True)


def _failure_dto(request: PredictionRequestV2, *, attempt: int) -> dict[str, object]:
    return PredictionFailureV2.from_request(
        request,
        message_id="failure-message",
        failure_code=PredictionFailureCode.INTERNAL_ERROR,
        summary="预测处理失败",
        attempt=attempt,
        generated_at=request.data_cutoff,
    ).model_dump(mode="json", by_alias=True)


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


def test_claim_lease_recovery_and_immutable_feature_snapshot(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    store = InboxStore(tmp_path / "inbox.sqlite3")
    request = PredictionRequestV2.model_validate(request_v2_payload)
    store.save(request)
    now = datetime(2026, 9, 27, tzinfo=UTC)

    first = store.claim_next(lease_seconds=30, now=now)
    assert first is not None
    assert first.attempt == 1
    assert store.claim_next(lease_seconds=30, now=now + timedelta(seconds=29)) is None

    snapshot = {"features": {"grid": 1, "weather": "dry"}}
    assert store.save_feature_snapshot(
        request.prediction_job_id, first.lease_token, snapshot, now=now
    )
    assert not store.save_feature_snapshot(
        request.prediction_job_id, first.lease_token, snapshot, now=now
    )
    with pytest.raises(FeatureSnapshotConflict):
        store.save_feature_snapshot(
            request.prediction_job_id,
            first.lease_token,
            {"features": {"grid": 2}},
            now=now,
        )

    recovered = store.claim_next(lease_seconds=30, now=now + timedelta(seconds=30))
    assert recovered is not None
    assert recovered.attempt == 2
    assert recovered.feature_snapshot_json == '{"features":{"grid":1,"weather":"dry"}}'
    assert not store.save_feature_snapshot(
        request.prediction_job_id,
        recovered.lease_token,
        snapshot,
        now=now + timedelta(seconds=30),
    )
    with pytest.raises(RuntimeError, match="lease"):
        store.finish(
            request.prediction_job_id,
            first.lease_token,
            terminal_status="SUCCEEDED",
            result=_success_dto(request),
            now=now + timedelta(seconds=30),
        )


def test_claim_rejects_corrupt_payload_and_feature_snapshot_hashes(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    now = datetime(2026, 9, 27, tzinfo=UTC)
    payload_path = tmp_path / "bad-payload.sqlite3"
    payload_store = InboxStore(payload_path)
    request = PredictionRequestV2.model_validate(request_v2_payload)
    payload_store.save(request)
    with sqlite3.connect(payload_path) as connection:
        connection.execute(
            "UPDATE request_inbox SET payload_json = '{}' WHERE prediction_job_id = ?",
            (request.prediction_job_id,),
        )
    with pytest.raises(sqlite3.DatabaseError, match="payload hash mismatch"):
        payload_store.claim_next(lease_seconds=10, now=now)

    snapshot_path = tmp_path / "bad-snapshot.sqlite3"
    snapshot_store = InboxStore(snapshot_path)
    snapshot_store.save(request)
    claim = snapshot_store.claim_next(lease_seconds=10, now=now)
    assert claim is not None
    snapshot_store.save_feature_snapshot(
        request.prediction_job_id,
        claim.lease_token,
        {"features": {"grid": 1}},
        now=now,
    )
    with sqlite3.connect(snapshot_path) as connection:
        connection.execute(
            "UPDATE request_inbox SET feature_snapshot_json = ?, lease_until = ? "
            "WHERE prediction_job_id = ?",
            ('{"features":{"grid":2}}', (now - timedelta(seconds=1)).isoformat(),
             request.prediction_job_id),
        )
    with pytest.raises(sqlite3.DatabaseError, match="feature snapshot hash mismatch"):
        snapshot_store.claim_next(lease_seconds=10, now=now)


def test_retry_is_bounded_and_respects_next_attempt(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    store = InboxStore(tmp_path / "inbox.sqlite3")
    request = PredictionRequestV2.model_validate(request_v2_payload)
    store.save(request)
    now = datetime(2026, 9, 27, tzinfo=UTC)
    first = store.claim_next(lease_seconds=10, now=now)
    assert first is not None
    assert store.retry(
        request.prediction_job_id,
        first.lease_token,
        max_attempts=2,
        base_delay_seconds=5,
        max_delay_seconds=20,
        now=now,
    ) == "scheduled"
    assert store.claim_next(lease_seconds=10, now=now + timedelta(seconds=4)) is None
    second = store.claim_next(lease_seconds=10, now=now + timedelta(seconds=5))
    assert second is not None and second.attempt == 2
    assert store.retry(
        request.prediction_job_id,
        second.lease_token,
        max_attempts=2,
        base_delay_seconds=5,
        max_delay_seconds=20,
        now=now + timedelta(seconds=5),
    ) == "exhausted"
    store.finish(
        request.prediction_job_id,
        second.lease_token,
        terminal_status="FAILED",
        result=_failure_dto(request, attempt=second.attempt),
        now=now + timedelta(seconds=5),
    )
    assert store.claim_next(lease_seconds=10, now=now + timedelta(seconds=100)) is None
    events = store.pending_outbox()
    assert len(events) == 1
    assert events[0].outcome_type == "FAILURE"
    assert '"failureCode":"INTERNAL_ERROR"' in events[0].payload_json
    assert "permanent" not in events[0].payload_json


def test_terminal_outbox_is_atomic_unique_and_markable(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    store = InboxStore(tmp_path / "inbox.sqlite3")
    request = PredictionRequestV2.model_validate(request_v2_payload)
    store.save(request)
    now = datetime(2026, 9, 27, tzinfo=UTC)
    claim = store.claim_next(lease_seconds=10, now=now)
    assert claim is not None
    result = _success_dto(request)
    store.finish(
        request.prediction_job_id,
        claim.lease_token,
        terminal_status="SUCCEEDED",
        result=result,
        now=now,
    )
    store.finish(
        request.prediction_job_id,
        claim.lease_token,
        terminal_status="SUCCEEDED",
        result=result,
        now=now + timedelta(seconds=1),
    )
    events = store.pending_outbox()
    assert len(events) == 1
    assert store.mark_outbox_published(events[0].event_id, now=now)
    assert not store.mark_outbox_published(events[0].event_id, now=now)
    assert store.pending_outbox() == []
    with sqlite3.connect(tmp_path / "inbox.sqlite3") as connection:
        assert connection.execute(
            "SELECT status FROM request_inbox WHERE prediction_job_id = ?",
            (request.prediction_job_id,),
        ).fetchone() == ("SUCCEEDED",)
        assert connection.execute("SELECT COUNT(*) FROM terminal_outbox").fetchone() == (1,)


def test_migrates_legacy_database_with_backup_and_data_verification(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    path = tmp_path / "legacy.sqlite3"
    request = PredictionRequestV2.model_validate(request_v2_payload)
    payload = InboxStore._canonical_json(request.model_dump(mode="json", by_alias=True))
    digest = __import__("hashlib").sha256(payload.encode("utf-8")).hexdigest()
    with sqlite3.connect(path) as connection:
        connection.executescript(
            """
            CREATE TABLE request_inbox (
                prediction_job_id TEXT PRIMARY KEY,
                message_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_hash TEXT NOT NULL,
                received_at TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status = 'RECEIVED')
            );
            CREATE TABLE request_quarantine (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                body BLOB NOT NULL,
                reason TEXT NOT NULL,
                received_at TEXT NOT NULL
            );
            """
        )
        connection.execute(
            "INSERT INTO request_inbox VALUES (?, ?, ?, ?, ?, 'RECEIVED')",
            (request.prediction_job_id, request.message_id, payload, digest, "2026-09-27T00:00:00+00:00"),
        )
        connection.execute(
            "INSERT INTO request_quarantine(body, reason, received_at) VALUES (?, ?, ?)",
            (b"bad", "legacy", "2026-09-27T00:00:00+00:00"),
        )

    store = InboxStore(path)
    assert store.save(request) == "duplicate"
    backup = Path(f"{path}.pre-migration-v2.bak")
    assert backup.exists()
    with sqlite3.connect(backup) as connection:
        assert connection.execute("SELECT COUNT(*) FROM request_inbox").fetchone() == (1,)
        assert connection.execute("SELECT COUNT(*) FROM request_quarantine").fetchone() == (1,)
    with sqlite3.connect(path) as connection:
        assert connection.execute("PRAGMA user_version").fetchone() == (2,)
        assert connection.execute("SELECT COUNT(*) FROM request_inbox").fetchone() == (1,)
        assert connection.execute("SELECT COUNT(*) FROM request_quarantine").fetchone() == (1,)
        assert "RUNNING" in connection.execute(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'request_inbox'"
        ).fetchone()[0]


def test_v1_migration_normalizes_wrapped_outbox_dto(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    path = tmp_path / "v1.sqlite3"
    request = PredictionRequestV2.model_validate(request_v2_payload)
    request_json = InboxStore._canonical_json(request.model_dump(mode="json", by_alias=True))
    request_hash = __import__("hashlib").sha256(request_json.encode("utf-8")).hexdigest()
    dto = _success_dto(request)
    dto_json = InboxStore._canonical_json(dto)
    with sqlite3.connect(path) as connection:
        connection.executescript(
            """
            CREATE TABLE request_inbox (
                prediction_job_id TEXT PRIMARY KEY,
                message_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_hash TEXT NOT NULL,
                received_at TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('RECEIVED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
                attempt INTEGER NOT NULL DEFAULT 0,
                lease_until TEXT,
                next_attempt_at TEXT,
                feature_snapshot_json TEXT,
                feature_snapshot_hash TEXT,
                terminal_json TEXT,
                terminal_at TEXT
            );
            CREATE TABLE terminal_outbox (
                event_id TEXT PRIMARY KEY,
                prediction_job_id TEXT NOT NULL UNIQUE,
                payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                published_at TEXT
            );
            """
        )
        connection.execute(
            """INSERT INTO request_inbox
            (prediction_job_id, message_id, payload_json, payload_hash, received_at, status,
             attempt, terminal_json, terminal_at)
            VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', 1, ?, ?)""",
            (
                request.prediction_job_id,
                request.message_id,
                request_json,
                request_hash,
                "2026-09-27T00:00:00+00:00",
                dto_json,
                "2026-09-27T00:01:00+00:00",
            ),
        )
        connection.execute(
            "INSERT INTO terminal_outbox VALUES (?, ?, ?, ?, NULL)",
            (
                "old-event-id",
                request.prediction_job_id,
                InboxStore._canonical_json(
                    {"eventId": "old-event-id", "predictionJobId": request.prediction_job_id,
                     "status": "SUCCEEDED", "result": dto}
                ),
                "2026-09-27T00:01:00+00:00",
            ),
        )
        connection.execute("PRAGMA user_version = 1")

    InboxStore(path)
    with sqlite3.connect(path) as connection:
        outbox_row = connection.execute(
            "SELECT outcome_type, payload_json FROM terminal_outbox"
        ).fetchone()
        assert outbox_row == ("RESULT", dto_json)
        assert json.loads(outbox_row[1]) == dto
        assert connection.execute("PRAGMA user_version").fetchone() == (2,)


def test_legacy_migration_refuses_corrupted_payload_hash(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    path = tmp_path / "corrupt-legacy.sqlite3"
    request = PredictionRequestV2.model_validate(request_v2_payload)
    payload = InboxStore._canonical_json(request.model_dump(mode="json", by_alias=True))
    with sqlite3.connect(path) as connection:
        connection.execute(
            """CREATE TABLE request_inbox (
                prediction_job_id TEXT PRIMARY KEY,
                message_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_hash TEXT NOT NULL,
                received_at TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status = 'RECEIVED')
            )"""
        )
        connection.execute(
            """INSERT INTO request_inbox VALUES (?, ?, ?, ?, ?, 'RECEIVED')""",
            (request.prediction_job_id, request.message_id, payload, "0" * 64, "2026-09-27T00:00:00+00:00"),
        )

    with pytest.raises(sqlite3.DatabaseError, match="payload hash mismatch"):
        InboxStore(path)

    with sqlite3.connect(path) as connection:
        assert connection.execute("PRAGMA user_version").fetchone() == (0,)
        columns = {row[1] for row in connection.execute("PRAGMA table_info(request_inbox)")}
        assert "lease_token" not in columns
        assert connection.execute("SELECT COUNT(*) FROM request_inbox").fetchone() == (1,)
    assert Path(f"{path}.pre-migration-v2.bak").exists()


def test_quarantine_preserves_bounded_original_body(tmp_path: Path) -> None:
    path = tmp_path / "inbox.sqlite3"
    store = InboxStore(path)
    store.quarantine(b"invalid" * 4, "malformed", max_bytes=10)
    with sqlite3.connect(path) as connection:
        assert connection.execute(
            "SELECT body, reason FROM request_quarantine"
        ).fetchone() == (b"invalidinv", "malformed")
