from __future__ import annotations

from datetime import UTC, datetime

import pytest

from f1_predict.replay.manifest import (
    CLEAN_RULE_VERSION,
    build_manifest,
    canonical_json_bytes,
    canonical_sha256,
    manifest_sha256,
    validate_manifest,
)


def identity() -> dict[str, object]:
    digest = "a" * 64
    return {
        "sourceQuestionId": 123,
        "sourceSnapshotId": 123,
        "sourceRoundId": 13,
        "questionTextHash": digest,
        "optionTextHashes": {"18": digest, "11059": digest},
        "optionDrivers": {"18": 10, "11059": 43},
        "isolatedIdMapping": {
            "questionId": 1,
            "snapshotId": 1,
            "roundId": 1,
            "optionIds": {"18": 2, "11059": 3},
        },
        "meetingIdentity": {"mysqlMeetingKey": 1174, "mongoMeetingKey": 1293},
        "sessions": [{"sessionKey": 11355, "type": "Practice"}],
        "drivers": [
            {"driverNumber": 10, "name": "Pierre Gasly"},
            {"driverNumber": 43, "name": "Franco Colapinto"},
        ],
        "evidenceHashes": {"sessionCatalog": digest},
    }


def test_canonical_json_and_hash_are_order_independent() -> None:
    assert canonical_json_bytes({"b": 1, "a": "x"}) == b'{"a":"x","b":1}'
    assert canonical_sha256({"a": 1, "b": 2}) == canonical_sha256({"b": 2, "a": 1})


def test_canonical_json_rejects_non_finite_numbers() -> None:
    with pytest.raises(ValueError, match="canonical JSON"):
        canonical_json_bytes({"duration": float("nan")})


def test_builds_and_validates_manifest_with_bound_file_hashes() -> None:
    raw = [{"lap": 1}]
    fixture = [{"recordId": "a"}]
    policy = {"minimumLaps": 3}
    metadata = identity()
    file_hashes = {
        "rawRows": canonical_sha256(raw),
        "fixture": canonical_sha256(fixture),
        "policy": canonical_sha256(policy),
        "identityMetadata": canonical_sha256(metadata),
    }
    manifest = build_manifest(
        metadata,
        import_started_at=datetime(2026, 9, 5, 14, tzinfo=UTC),
        import_completed_at=datetime(2026, 9, 5, 14, 1, tzinfo=UTC),
        sporting_cutoff=datetime(2026, 9, 5, 13, 59, 59, 999000, tzinfo=UTC),
        raw_rows=raw,
        normalized_laps=fixture,
        query={
            "collection": "laps",
            "scope": "meeting/session/driver",
            "projection": "fixed-lap-fields",
            "timeoutMs": "5000",
            "limit": "501",
        },
        counts={
            "raw": 1,
            "duplicateMerged": 0,
            "conflict": 0,
            "excludedIdentity": 0,
            "excludedInvalid": 0,
            "excludedSportingCutoff": 0,
            "excludedProxyRule": 0,
            "exported": 1,
        },
        rules={"version": CLEAN_RULE_VERSION},
        versions={"exporter": "1", "cleanRule": CLEAN_RULE_VERSION, "modelMode": "stub"},
        policy=policy,
        file_hashes=file_hashes,
    )

    validate_manifest(manifest)
    assert manifest["mode"] == "HISTORICAL_ENGINEERING_REPLAY"
    assert manifest["time"]["sourceFirstSeenStatus"] == "UNKNOWN"
    assert manifest["fileHashes"] == file_hashes
    assert len(manifest_sha256(manifest)) == 64
    tampered = {**manifest, "packageId": "0" * 64}
    with pytest.raises(ValueError, match="packageId"):
        validate_manifest(tampered)
    with pytest.raises(ValueError, match="top level"):
        validate_manifest([])


def test_manifest_requires_identity_and_matching_file_hashes() -> None:
    now = datetime(2026, 9, 5, 14, tzinfo=UTC)
    arguments = {
        "import_started_at": now,
        "import_completed_at": now,
        "sporting_cutoff": now,
        "raw_rows": [],
        "normalized_laps": [],
        "query": {
            "collection": "laps",
            "scope": "fixed meeting/session/driver",
            "projection": "fixed lap whitelist",
            "timeoutMs": "5000",
            "limit": "501",
        },
        "counts": {
            "raw": 0,
            "duplicateMerged": 0,
            "conflict": 0,
            "excludedIdentity": 0,
            "excludedInvalid": 0,
            "excludedSportingCutoff": 0,
            "excludedProxyRule": 0,
            "exported": 0,
        },
        "rules": {"version": CLEAN_RULE_VERSION},
        "versions": {"exporter": "1", "cleanRule": CLEAN_RULE_VERSION, "modelMode": "stub"},
        "policy": {"minimum_laps": 3},
    }
    with pytest.raises(ValueError, match="identity metadata is incomplete"):
        build_manifest({}, **arguments)

    metadata = identity()
    expected = {
        "rawRows": canonical_sha256([]),
        "fixture": canonical_sha256([]),
        "policy": canonical_sha256(arguments["policy"]),
        "identityMetadata": canonical_sha256(metadata),
    }
    expected["fixture"] = "b" * 64
    with pytest.raises(ValueError, match="does not match"):
        build_manifest(metadata, file_hashes=expected, **arguments)
    with pytest.raises(ValueError, match="counts do not match"):
        build_manifest(
            metadata,
            file_hashes={
                "rawRows": canonical_sha256([{"unexpected": True}]),
                "fixture": canonical_sha256([]),
                "policy": canonical_sha256(arguments["policy"]),
                "identityMetadata": canonical_sha256(metadata),
            },
            **{**arguments, "raw_rows": [{"unexpected": True}]},
        )


def test_manifest_rejects_501_rows_and_naive_clock() -> None:
    now = datetime(2026, 9, 5, 14, tzinfo=UTC)
    metadata = identity()
    common = {
        "import_started_at": now,
        "import_completed_at": now,
        "sporting_cutoff": now,
        "normalized_laps": [],
        "query": {
            "collection": "laps",
            "scope": "fixed meeting/session/driver",
            "projection": "fixed lap whitelist",
            "timeoutMs": "5000",
            "limit": "501",
        },
        "counts": {
            "raw": 0,
            "duplicateMerged": 0,
            "conflict": 0,
            "excludedIdentity": 0,
            "excludedInvalid": 0,
            "excludedSportingCutoff": 0,
            "excludedProxyRule": 0,
            "exported": 0,
        },
        "rules": {"version": CLEAN_RULE_VERSION},
        "versions": {"exporter": "1", "cleanRule": CLEAN_RULE_VERSION, "modelMode": "stub"},
        "policy": {"minimum_laps": 3},
    }
    file_hashes = {
        "rawRows": canonical_sha256([]),
        "fixture": canonical_sha256([]),
        "policy": canonical_sha256(common["policy"]),
        "identityMetadata": canonical_sha256(metadata),
    }
    with pytest.raises(ValueError, match="500-row bound"):
        build_manifest(metadata, raw_rows=[{}] * 501, file_hashes=file_hashes, **common)
    with pytest.raises(ValueError, match="timezone-aware"):
        build_manifest(
            metadata,
            raw_rows=[],
            file_hashes=file_hashes,
            **{**common, "import_started_at": datetime.fromisoformat("2026-09-05T14:00:00")},
        )
