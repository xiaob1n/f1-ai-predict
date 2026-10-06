from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path

import pytest

import f1_predict.replay.bundle as bundle_module
from f1_predict.replay.bundle import (
    _LEGACY_RUNTIME_PATH,
    capture_staging,
    load_staging,
    seal_bundle,
)
from f1_predict.replay.manifest import canonical_sha256

START = datetime(2026, 9, 5, 14, tzinfo=UTC)
END = datetime(2026, 9, 5, 14, 1, tzinfo=UTC)
CUTOFF = datetime(2026, 9, 5, 13, 59, 59, 999000, tzinfo=UTC)
TEXT = "Which driver will be faster?"
OPTION_TEXTS = {"18": "Driver A", "19": "Driver B"}


def sample_identity() -> dict[str, object]:
    return {
        "sourceQuestionId": 1,
        "sourceSnapshotId": 2,
        "sourceRoundId": 13,
        "questionTextHash": canonical_sha256(TEXT),
        "optionTextHashes": {key: canonical_sha256(value) for key, value in OPTION_TEXTS.items()},
        "optionDrivers": {"18": 10, "19": 43},
        "meetingIdentity": {"mysqlMeetingKey": 1174, "mongoMeetingKey": 1293},
        "sessions": [{"sessionKey": 11355, "type": "Practice"}],
        "drivers": [{"driverNumber": 10, "name": "Driver A"}, {"driverNumber": 43, "name": "Driver B"}],
        "evidenceHashes": {"sessionCatalog": "c" * 64},
    }


def sample_source() -> dict[str, object]:
    return {
        "question": {
            "id": 1, "roundId": 13, "gamedayId": 17, "sourceQuestionId": 9001,
            "questionNo": 1, "status": "OPEN", "latestSnapshotId": 2,
            "firstSeenAt": "2026-09-05T14:00:00Z",
        },
        "snapshot": {
            "id": 2, "questionId": 1, "snapshotNo": 1,
            "contentHash": "d" * 64, "createdAt": "2026-09-05T14:00:00Z",
            "raw": {"Text": TEXT, "SubText": "", "OptionTemplateId": 1, "Config": {"ChoiceLimit": 1}},
        },
        "season": {"id": 1, "year": 2026, "name": "2026", "status": "ACTIVE"},
        "round": {
            "id": 13, "seasonId": 1, "roundNumber": 1,
            "grandPrixName": "Example GP", "circuitName": "Example Circuit", "country": "Example",
            "locality": "Example City", "startDate": "2026-09-05", "endDate": "2026-09-06", "status": "ACTIVE",
        },
        "sessions": [{"meetingKey": 1174, "sessionKey": 11355, "sessionName": "Practice 1", "sessionType": "Practice", "gamedayId": 17, "startDateUtc": "2026-09-05T12:00:00Z", "endDateUtc": "2026-09-05T13:00:00Z", "status": "FINISHED"}],
        "options": [
            {"optionId": 18, "optionNo": 1, "optionText": OPTION_TEXTS["18"], "points": 0, "chance": 0},
            {"optionId": 19, "optionNo": 2, "optionText": OPTION_TEXTS["19"], "points": 0, "chance": 0},
        ],
    }


def sample_lap() -> dict[str, object]:
    return {
        "_key": "lap-1", "meeting_key": 1293, "session_key": 11355,
        "driver_number": 10, "lap_number": 1,
        "date_start": datetime(2026, 9, 5, 13, 58, tzinfo=UTC),
        "lap_duration": 87.0, "duration_sector_1": 29.0,
        "duration_sector_2": 29.0, "duration_sector_3": 29.0,
        "is_pit_out_lap": False,
    }


def clock_values():
    values = iter((START, END))
    return lambda: next(values)


def test_staging_is_private_non_executable_and_keeps_capture_clock(tmp_path: Path) -> None:
    capture = capture_staging(tmp_path, sample_source(), [sample_lap()], sample_identity(), CUTOFF, clock=clock_values())

    assert capture.path.stat().st_mode & 0o777 == 0o700
    assert (capture.path / "staging.json").stat().st_mode & 0o777 == 0o600
    assert not (capture.path / "manifest.json").exists()
    assert set(json.loads((capture.path / "capture_data.json").read_text())["rawRows"][0]) <= {
        "_key", "meeting_key", "session_key", "driver_number", "lap_number", "date_start",
        "lap_duration", "duration_sector_1", "duration_sector_2", "duration_sector_3",
        "is_pit_out_lap", "source_metadata",
    }
    loaded = load_staging(capture.path)
    assert loaded.capture_hash == capture.capture_hash
    assert loaded.first_seen_at == START
    assert loaded.import_completed_at == END
    assert "isolatedIdMapping" not in loaded.identity


def test_import_completed_clock_is_observed_after_durable_capture_data(tmp_path: Path) -> None:
    calls = 0

    def clock() -> datetime:
        nonlocal calls
        calls += 1
        if calls == 1:
            return START
        capture_dirs = list(tmp_path.glob("staging-*"))
        assert len(capture_dirs) == 1
        assert (capture_dirs[0] / "capture_data.json").is_file()
        status = json.loads((capture_dirs[0] / "staging_status.json").read_text())
        assert status["state"] == "CAPTURING"
        return END

    capture = capture_staging(tmp_path, sample_source(), [sample_lap()], sample_identity(), CUTOFF, clock=clock)

    assert calls == 2
    assert load_staging(capture.path).import_completed_at == END


def test_failed_capture_keeps_durable_source_and_failed_status(tmp_path: Path) -> None:
    calls = 0

    def failing_clock() -> datetime:
        nonlocal calls
        calls += 1
        if calls == 1:
            return START
        assert list(tmp_path.glob("staging-*/capture_data.json"))
        raise RuntimeError("clock failed after data persisted")

    with pytest.raises(RuntimeError, match="clock failed"):
        capture_staging(tmp_path, sample_source(), [sample_lap()], sample_identity(), CUTOFF, clock=failing_clock)

    staging_path = next(tmp_path.glob("staging-*"))
    assert (staging_path / "capture_data.json").exists()
    status = json.loads((staging_path / "staging_status.json").read_text())
    assert status["state"] == "FAILED"
    assert status["captureDataPersisted"] is True
    with pytest.raises(ValueError, match="state=FAILED"):
        load_staging(staging_path)


def test_seal_publishes_only_after_actual_id_mapping_and_verifies_bundle(tmp_path: Path) -> None:
    capture = capture_staging(tmp_path, sample_source(), [sample_lap()], sample_identity(), CUTOFF, clock=clock_values())
    destination = tmp_path / "sealed"

    sealed = seal_bundle(
        capture,
        destination,
        {"seasonId": 104, "questionId": 101, "snapshotId": 102, "roundId": 103, "optionIds": {"18": 201, "19": 202}},
    )

    assert sealed.manifest_path == destination / "manifest.json"
    assert len(sealed.manifest_hash) == 64
    assert sealed.policy.question_id == 101
    assert sealed.policy.question_snapshot_id == 102
    assert sealed.policy.option_drivers == {201: 10, 202: 43}
    manifest = json.loads(sealed.manifest_path.read_text())
    assert manifest["identity"]["isolatedIdMapping"]["roundId"] == 103
    assert "seasonId" not in manifest["identity"]["isolatedIdMapping"]
    provenance = json.loads((destination / "source_provenance.json").read_text())
    sealed_identity = json.loads((destination / "identity_metadata.json").read_text())
    assert canonical_sha256(provenance) == sealed_identity["evidenceHashes"]["sourceProvenance"]
    assert provenance["captureHash"] == sealed_identity["evidenceHashes"]["stagingCapture"]
    with pytest.raises(FileExistsError):
        seal_bundle(capture.path, destination, {"questionId": 1, "snapshotId": 2, "roundId": 3, "optionIds": {"18": 4, "19": 5}})


def test_staging_rejects_unknown_source_fields_and_tampering(tmp_path: Path) -> None:
    source = sample_source()
    source["unexpected"] = "secret"
    with pytest.raises(ValueError, match="exactly"):
        capture_staging(tmp_path, source, [], sample_identity(), CUTOFF, clock=clock_values())
    assert list(tmp_path.iterdir()) == []

    capture = capture_staging(tmp_path, sample_source(), [], sample_identity(), CUTOFF, clock=clock_values())
    staging_file = capture.path / "capture_data.json"
    content = json.loads(staging_file.read_text())
    content["identity"]["sourceRoundId"] = "tampered"
    staging_file.write_text(json.dumps(content))
    with pytest.raises(ValueError, match="capture data hash"):
        load_staging(capture.path)


def test_staging_and_file_permissions_are_enforced(tmp_path: Path) -> None:
    root = tmp_path / "open-root"
    root.mkdir(mode=0o700)
    root.chmod(0o755)
    with pytest.raises(PermissionError, match="private"):
        capture_staging(root, sample_source(), [], sample_identity(), CUTOFF, clock=clock_values())
    root.chmod(0o700)
    capture = capture_staging(root, sample_source(), [], sample_identity(), CUTOFF, clock=clock_values())
    staging_file = capture.path / "staging.json"
    staging_file.chmod(0o644)
    with pytest.raises(PermissionError, match="private"):
        load_staging(capture.path)
    staging_file.chmod(0o600)
    original = root / "backup.json"
    original.write_text(staging_file.read_text())
    staging_file.unlink()
    staging_file.symlink_to(original)
    with pytest.raises(ValueError, match="symlink"):
        load_staging(capture.path)


def test_staging_rejects_legacy_runtime_path() -> None:
    with pytest.raises(ValueError, match="legacy E2E runtime"):
        capture_staging(_LEGACY_RUNTIME_PATH, sample_source(), [], sample_identity(), CUTOFF, clock=clock_values())


def test_seal_failure_never_publishes_partial_manifest(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    capture = capture_staging(tmp_path, sample_source(), [sample_lap()], sample_identity(), CUTOFF, clock=clock_values())
    destination = tmp_path / "partial"
    original_write = bundle_module._write_exclusive

    def fail_on_manifest(path: Path, value: object) -> None:
        if path.name == "manifest.json":
            raise OSError("simulated partial write failure")
        original_write(path, value)

    monkeypatch.setattr(bundle_module, "_write_exclusive", fail_on_manifest)
    with pytest.raises(OSError, match="partial write"):
        seal_bundle(
            capture,
            destination,
            {"seasonId": 104, "questionId": 101, "snapshotId": 102, "roundId": 103, "optionIds": {"18": 201, "19": 202}},
        )
    assert not destination.exists()
    assert not list(tmp_path.glob(".partial-*"))
    assert not (capture.path / "manifest.json").exists()


def test_sealed_bundle_detects_policy_tampering(tmp_path: Path) -> None:
    capture = capture_staging(tmp_path, sample_source(), [sample_lap()], sample_identity(), CUTOFF, clock=clock_values())
    sealed = seal_bundle(
        capture,
        tmp_path / "tampered",
        {"seasonId": 104, "questionId": 101, "snapshotId": 102, "roundId": 103, "optionIds": {"18": 201, "19": 202}},
    )
    policy_path = sealed.manifest_path.parent / "policy.json"
    policy = json.loads(policy_path.read_text())
    policy["minimum_laps"] = 99
    policy_path.write_text(json.dumps(policy))

    from f1_predict.prediction.replay_context import ReplayContext

    with pytest.raises(ValueError, match="file hash"):
        ReplayContext.load(
            str(sealed.manifest_path),
            str(sealed.manifest_path.parent / "fixture.json"),
            str(policy_path),
            model_mode="stub",
            expected_manifest_hash=sealed.manifest_hash,
        )


def test_seal_rejects_fake_or_incomplete_actual_id_mapping(tmp_path: Path) -> None:
    capture = capture_staging(tmp_path, sample_source(), [sample_lap()], sample_identity(), CUTOFF, clock=clock_values())
    with pytest.raises(ValueError, match="isolatedIdMapping"):
        seal_bundle(
            capture,
            tmp_path / "invalid",
            {"questionId": 101, "snapshotId": 102, "roundId": 103, "optionIds": {"18": 201}},
        )
    assert not (tmp_path / "invalid").exists()


def test_staging_rejects_symlink_root_and_501_rows(tmp_path: Path) -> None:
    real_root = tmp_path / "real"
    real_root.mkdir(mode=0o700)
    alias = tmp_path / "alias"
    alias.symlink_to(real_root, target_is_directory=True)
    with pytest.raises(ValueError, match="symlink"):
        capture_staging(alias, sample_source(), [], sample_identity(), CUTOFF, clock=clock_values())
    with pytest.raises(ValueError, match="500-row"):
        capture_staging(real_root, sample_source(), [sample_lap()] * 501, sample_identity(), CUTOFF, clock=clock_values())
    assert list(real_root.iterdir()) == []
