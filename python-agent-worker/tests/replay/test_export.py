from __future__ import annotations

from datetime import UTC, datetime

import pytest

from f1_predict.replay.export import (
    FIXTURE_FIELDS,
    export_laps,
    read_laps_bounded,
)
from f1_predict.replay.manifest import canonical_sha256

CUTOFF = datetime(2026, 9, 5, 13, 59, 59, 999000, tzinfo=UTC)
STARTED = datetime(2026, 9, 5, 14, 0, tzinfo=UTC)
COMPLETED = datetime(2026, 9, 5, 14, 1, tzinfo=UTC)
IDENTITY = {
    "sourceQuestionId": 123,
    "sourceSnapshotId": 123,
    "sourceRoundId": 13,
    "questionTextHash": "a" * 64,
    "optionTextHashes": {"18": "b" * 64, "11059": "d" * 64},
    "optionDrivers": {"18": 10, "11059": 43},
    "isolatedIdMapping": {
        "questionId": 1,
        "snapshotId": 1,
        "optionIds": {"18": 2, "11059": 3},
    },
    "meetingIdentity": {"mysqlMeetingKey": 1174, "mongoMeetingKey": 1293},
    "sessions": [{"sessionKey": 11355, "type": "Practice"}],
    "drivers": [
        {"driverNumber": 10, "name": "Pierre Gasly"},
        {"driverNumber": 43, "name": "Franco Colapinto"},
    ],
    "evidenceHashes": {"sessionCatalog": "c" * 64},
}


def lap(**updates: object) -> dict[str, object]:
    row: dict[str, object] = {
        "_key": "lap-1",
        "meeting_key": 1293,
        "session_key": 11355,
        "driver_number": 10,
        "lap_number": 1,
        "date_start": datetime(2026, 9, 5, 13, 58, tzinfo=UTC),
        "lap_duration": 87.0,
        "duration_sector_1": 29.0,
        "duration_sector_2": 29.0,
        "duration_sector_3": 29.0,
        "is_pit_out_lap": False,
        "password": "must-not-export",
    }
    row.update(updates)
    return row


class FakeSource:
    def __init__(self, rows: list[dict[str, object]]) -> None:
        self.rows = rows
        self.call: dict[str, object] | None = None

    def find(self, collection, query, *, projection, max_time_ms, limit):
        self.call = {
            "collection": collection,
            "query": query,
            "projection": projection,
            "max_time_ms": max_time_ms,
            "limit": limit,
        }
        return self.rows[:limit]


def test_read_laps_uses_fixed_read_only_bounded_query_and_501_sentinel() -> None:
    source = FakeSource([lap()])
    rows = read_laps_bounded(
        source,
        mongo_meeting_key=1293,
        session_keys=(11355, 11356),
        driver_numbers=(10, 43),
    )

    assert len(rows) == 1
    assert source.call["collection"] == "laps"
    assert source.call["limit"] == 501
    assert source.call["max_time_ms"] == 5000
    assert source.call["projection"]["_id"] == 0
    assert "password" not in source.call["projection"]


def test_read_laps_rejects_501_sentinel_without_truncation() -> None:
    source = FakeSource([lap(_key=f"lap-{index}") for index in range(501)])
    with pytest.raises(ValueError, match="500-row export limit"):
        read_laps_bounded(
            source,
            mongo_meeting_key=1293,
            session_keys=(11355,),
            driver_numbers=(10,),
        )


def test_projected_source_metadata_keeps_only_safe_bounded_identifiers() -> None:
    metadata = {
        "sourceAlias": "OpenF1Archive",
        "sourceRecordId": "lap_record-123.4",
        "sourceContentHash": "A" * 64,
    }
    result = export_laps(
        [lap(source_metadata=metadata)],
        meeting_key=1174,
        mongo_meeting_key=1293,
        session_keys=(11355,),
        driver_numbers=(10, 43),
        sporting_cutoff=CUTOFF,
        import_started_at=STARTED,
        import_completed_at=COMPLETED,
        first_seen_at=COMPLETED,
        identity_metadata=IDENTITY,
    )

    assert result.raw_rows[0]["source_metadata"] == {
        "sourceAlias": "OpenF1Archive",
        "sourceRecordId": "lap_record-123.4",
        "sourceContentHash": "a" * 64,
    }


@pytest.mark.parametrize(
    "metadata",
    [
        None,
        [],
        "https://user:password@example.test/lap",
        {"sourceAlias": "https://example.test/lap"},
        {"sourceRecordId": "record@internal"},
        {"sourceRecordId": "internal/path"},
        {"sourceRecordId": "internal\\\\path"},
        {"sourceAlias": "internal\nrecord"},
        {"sourceRecordId": "x" * 129},
        {"sourceContentHash": "g" * 64},
        {"unexpected": "https://user:password@10.0.0.1"},
    ],
)
def test_export_rejects_unsafe_source_metadata_without_exporting_value(metadata: object) -> None:
    with pytest.raises(ValueError, match="source_metadata"):
        export_laps(
            [lap(source_metadata=metadata)],
            meeting_key=1174,
            mongo_meeting_key=1293,
            session_keys=(11355,),
            driver_numbers=(10, 43),
            sporting_cutoff=CUTOFF,
            import_started_at=STARTED,
            import_completed_at=COMPLETED,
            first_seen_at=COMPLETED,
            identity_metadata=IDENTITY,
        )


def test_export_rejects_untrusted_source_alias() -> None:
    with pytest.raises(ValueError, match="source alias must match"):
        export_laps(
            [lap()],
            meeting_key=1174,
            mongo_meeting_key=1293,
            session_keys=(11355,),
            driver_numbers=(10, 43),
            sporting_cutoff=CUTOFF,
            import_started_at=STARTED,
            import_completed_at=COMPLETED,
            first_seen_at=COMPLETED,
            identity_metadata=IDENTITY,
            source_alias="https://user:password@127.0.0.1/laps",
        )


@pytest.mark.parametrize("field", ["sourceAlias", "sourceRecordId"])
@pytest.mark.parametrize("address", ["10.0.0.1", "127.0.0.1", "::1", "fd00::1"])
def test_export_rejects_ip_literals_in_source_metadata(field: str, address: str) -> None:
    with pytest.raises(ValueError, match="source_metadata"):
        export_laps(
            [lap(source_metadata={field: address})],
            meeting_key=1174,
            mongo_meeting_key=1293,
            session_keys=(11355,),
            driver_numbers=(10, 43),
            sporting_cutoff=CUTOFF,
            import_started_at=STARTED,
            import_completed_at=COMPLETED,
            first_seen_at=COMPLETED,
            identity_metadata=IDENTITY,
        )


def test_export_emits_exact_11_field_fixture_and_records_both_clocks() -> None:
    result = export_laps(
        [lap()],
        meeting_key=1174,
        mongo_meeting_key=1293,
        session_keys=(11355,),
        driver_numbers=(10, 43),
        sporting_cutoff=CUTOFF,
        import_started_at=STARTED,
        import_completed_at=COMPLETED,
        first_seen_at=datetime(2026, 9, 5, 14, 0, 30, tzinfo=UTC),
        identity_metadata=IDENTITY,
    )

    assert result.counts["exported"] == 1
    assert set(result.normalized_laps[0]) == FIXTURE_FIELDS
    assert result.normalized_laps[0]["meetingKey"] == 1174
    assert result.normalized_laps[0]["eventTime"] == "2026-09-05T13:58:00.000Z"
    assert result.normalized_laps[0]["firstSeenAt"] == "2026-09-05T14:00:30.000Z"
    assert result.normalized_laps[0]["sourceContentHash"] == canonical_sha256(result.raw_rows[0])
    assert "password" not in result.raw_rows[0]


def test_export_sorts_raw_rows_and_hash_is_independent_of_input_order() -> None:
    rows = [lap(_key="lap-2", lap_number=2), lap(_key="lap-1", lap_number=1)]
    parameters = {
        "meeting_key": 1174,
        "mongo_meeting_key": 1293,
        "session_keys": (11355,),
        "driver_numbers": (10, 43),
        "sporting_cutoff": CUTOFF,
        "import_started_at": STARTED,
        "import_completed_at": COMPLETED,
        "first_seen_at": COMPLETED,
        "identity_metadata": IDENTITY,
    }

    forward = export_laps(rows, **parameters)
    reversed_result = export_laps(list(reversed(rows)), **parameters)

    assert forward.raw_rows == reversed_result.raw_rows
    assert canonical_sha256(forward.raw_rows) == canonical_sha256(reversed_result.raw_rows)


def test_export_rejects_501st_row_even_when_it_is_a_duplicate() -> None:
    with pytest.raises(ValueError, match="501-row sentinel"):
        export_laps(
            [lap()] * 501,
            meeting_key=1174,
            mongo_meeting_key=1293,
            session_keys=(11355,),
            driver_numbers=(10, 43),
            sporting_cutoff=CUTOFF,
            import_started_at=STARTED,
            import_completed_at=COMPLETED,
            first_seen_at=COMPLETED,
            identity_metadata=IDENTITY,
        )


@pytest.mark.parametrize(
    "date_start",
    [
        datetime(2026, 9, 5, 13, 58, 0, 123456, tzinfo=UTC),
        "2026-09-05T13:58:00.1230001Z",
    ],
)
def test_export_rejects_date_start_with_submillisecond_precision(date_start: object) -> None:
    with pytest.raises(ValueError, match="date_start must have millisecond precision"):
        export_laps(
            [lap(date_start=date_start)],
            meeting_key=1174,
            mongo_meeting_key=1293,
            session_keys=(11355,),
            driver_numbers=(10, 43),
            sporting_cutoff=CUTOFF,
            import_started_at=STARTED,
            import_completed_at=COMPLETED,
            first_seen_at=COMPLETED,
            identity_metadata=IDENTITY,
        )


def test_export_applies_sporting_cutoff_before_millisecond_serialization() -> None:
    result = export_laps(
        [
            lap(
                date_start=datetime(2026, 9, 5, 13, 58, 32, 999000, tzinfo=UTC),
                lap_duration=87.0005,
                duration_sector_1=29.0002,
                duration_sector_2=29.0002,
                duration_sector_3=29.0001,
            )
        ],
        meeting_key=1174,
        mongo_meeting_key=1293,
        session_keys=(11355,),
        driver_numbers=(10, 43),
        sporting_cutoff=CUTOFF,
        import_started_at=STARTED,
        import_completed_at=COMPLETED,
        first_seen_at=COMPLETED,
        identity_metadata=IDENTITY,
    )
    assert result.normalized_laps == ()
    assert result.counts["excludedSportingCutoff"] == 1


def test_export_filters_by_sporting_cutoff_and_proxy_quality() -> None:
    result = export_laps(
        [
            lap(_key="late", date_start=datetime(2026, 9, 5, 14, tzinfo=UTC)),
            lap(_key="dirty", duration_sector_3=20.0),
        ],
        meeting_key=1174,
        mongo_meeting_key=1293,
        session_keys=(11355,),
        driver_numbers=(10, 43),
        sporting_cutoff=CUTOFF,
        import_started_at=STARTED,
        import_completed_at=COMPLETED,
        first_seen_at=COMPLETED,
        identity_metadata=IDENTITY,
    )
    assert result.normalized_laps == ()
    assert result.counts["excludedSportingCutoff"] == 1
    assert result.counts["excludedProxyRule"] == 1


def test_export_compares_all_meeting_session_and_driver_keys_to_verified_identity() -> None:
    parameters = {
        "meeting_key": 1174,
        "mongo_meeting_key": 1293,
        "session_keys": (11355,),
        "driver_numbers": (10, 43),
        "sporting_cutoff": CUTOFF,
        "import_started_at": STARTED,
        "import_completed_at": COMPLETED,
        "first_seen_at": COMPLETED,
        "identity_metadata": IDENTITY,
    }
    for change, message in (
        ({"meeting_key": 1175}, "meeting keys"),
        ({"mongo_meeting_key": 1294}, "meeting keys"),
        ({"session_keys": (11356,)}, "session keys"),
        ({"driver_numbers": (10,)}, "driver numbers"),
    ):
        with pytest.raises(ValueError, match=message):
            export_laps([lap()], **{**parameters, **change})


def test_export_fails_closed_for_missing_identity_conflicts_and_late_observation() -> None:
    parameters = {
        "meeting_key": 1174,
        "mongo_meeting_key": 1293,
        "session_keys": (11355,),
        "driver_numbers": (10, 43),
        "sporting_cutoff": CUTOFF,
        "import_started_at": STARTED,
        "import_completed_at": COMPLETED,
        "first_seen_at": COMPLETED,
        "identity_metadata": IDENTITY,
    }
    with pytest.raises(ValueError, match="identity metadata is incomplete"):
        export_laps([lap()], **{**parameters, "identity_metadata": {}})
    with pytest.raises(ValueError, match="conflicting source rows"):
        export_laps([lap(), lap(lap_duration=88.0)], **parameters)
    with pytest.raises(ValueError, match="during the import window"):
        export_laps(
            [lap()],
            **{**parameters, "first_seen_at": datetime(2026, 9, 5, 14, 2, tzinfo=UTC)},
        )


def test_export_requires_501_sentinel_rejection_and_rejects_missing_clock() -> None:
    query_args = {
        "meeting_key": 1174,
        "mongo_meeting_key": 1293,
        "session_keys": (11355,),
        "driver_numbers": (10, 43),
        "sporting_cutoff": CUTOFF,
        "import_started_at": STARTED,
        "import_completed_at": COMPLETED,
        "first_seen_at": COMPLETED,
        "identity_metadata": IDENTITY,
    }
    with pytest.raises(ValueError, match="501-row sentinel"):
        export_laps([lap(_key=f"row-{index}") for index in range(502)], **query_args)
