"""受控历史工程回放的包校验与模型调用前拒绝。"""

from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from f1_predict.common.config import Settings
from f1_predict.messaging.dto.request_v2 import PredictionRequestV2
from f1_predict.prediction.data import load_lap_fixture
from f1_predict.prediction.model import ModelCandidate
from f1_predict.prediction.policy import SnapshotPolicy
from f1_predict.prediction.processor import PredictionProcessor
from f1_predict.prediction.replay_context import ReplayContext
from f1_predict.prediction.replay_model import ReplayBaselineModel
from f1_predict.reliability.idempotency import InboxStore
from f1_predict.replay.manifest import (
    build_manifest,
    canonical_sha256,
    format_utc,
    manifest_sha256,
)


class CapturingModel:
    def __init__(self) -> None:
        self.calls = 0
        self.context: dict[str, object] = {}

    async def predict(self, context: str) -> ModelCandidate:
        self.calls += 1
        self.context = json.loads(context)
        return ModelCandidate(option_ids=[18], confidence=0.55, reasoning_summary="工程基线对照")


def _bundle(folder: Path) -> tuple[ReplayContext, SnapshotPolicy]:
    started = datetime(2026, 9, 27, 7, 0, tzinfo=UTC)
    completed = started + timedelta(minutes=1)
    cutoff = started - timedelta(minutes=1)
    question = "Who qualifies highest for the Italian Grand Prix?"
    options = {"18": "Pierre Gasly", "11059": "Franco Colapinto"}
    policy = SnapshotPolicy(
        question_snapshot_id=11, question_id=7, question_text=question,
        meeting_key=1174, session_keys=(11355, 11356),
        option_drivers={18: 10, 11059: 43}, minimum_laps=3,
        model_version="model-v1", prompt_version="prompt-h2h-replay-v1",
        feature_version="feature-v1",
    )
    identity = {
        "sourceQuestionId": 123, "sourceSnapshotId": 123, "sourceRoundId": 13,
        "questionTextHash": canonical_sha256(question),
        "optionTextHashes": {key: canonical_sha256(text) for key, text in options.items()},
        "optionDrivers": {"18": 10, "11059": 43},
        "isolatedIdMapping": {"questionId": 7, "snapshotId": 11, "roundId": 2,
                              "optionIds": {"18": 18, "11059": 11059}},
        "meetingIdentity": {"mysqlMeetingKey": 1174, "mongoMeetingKey": 1293},
        "sessions": [{"sessionKey": 11355, "type": "Practice"}, {"sessionKey": 11356, "type": "Practice"}],
        "drivers": [{"driverNumber": 10}, {"driverNumber": 43}],
        "evidenceHashes": {"sessionCatalog": "a" * 64},
    }
    raw = [{"_key": f"{driver}-{i}", "meeting_key": 1293, "lap_number": i + 1,
            "session_key": 11355, "driver_number": driver,
            "date_start": format_utc(cutoff - timedelta(minutes=10)),
            "lap_duration": 120.0, "duration_sector_1": 40.0,
            "duration_sector_2": 40.0, "duration_sector_3": 40.0,
            "is_pit_out_lap": False}
           for driver in (10, 43) for i in range(3)]
    rows = [{
        "recordId": source["_key"], "meetingKey": 1174,
        "sessionKey": 11355, "driverNumber": source["driver_number"],
        "eventTime": format_utc(cutoff - timedelta(minutes=10)),
        "firstSeenAt": format_utc(started),
        "lapEnd": format_utc(cutoff - timedelta(minutes=8)),
        "durationSeconds": 120.0, "isClean": True,
        "sourceEndpoint": "OpenF1Archive/laps", "sourceContentHash": canonical_sha256(source),
    } for source in raw]
    policy_data = policy.model_dump(mode="json")
    data = {"rawRows": raw, "fixture": rows, "policy": policy_data, "identityMetadata": identity}
    names = {"rawRows": "raw_rows.json", "fixture": "fixture.json",
             "policy": "policy.json", "identityMetadata": "identity_metadata.json"}
    for name, filename in names.items():
        (folder / filename).write_text(json.dumps(data[name]), encoding="utf-8")
    manifest = build_manifest(
        identity, import_started_at=started, import_completed_at=completed,
        sporting_cutoff=cutoff, raw_rows=raw, normalized_laps=rows,
        policy=policy_data, query={"collection": "laps", "scope": "meeting/session/driver",
                                   "projection": "whitelist", "timeoutMs": "5000", "limit": "501"},
        counts={"raw": 6, "duplicateMerged": 0, "conflict": 0,
                "excludedIdentity": 0, "excludedInvalid": 0,
                "excludedSportingCutoff": 0, "excludedProxyRule": 0, "exported": 6},
        rules={"version": "engineering-lap-proxy-v1"},
        versions={"exporter": "v1", "cleanRule": "engineering-lap-proxy-v1", "modelMode": "stub"},
        file_hashes={name: canonical_sha256(content) for name, content in data.items()},
    )
    (folder / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return ReplayContext.load(str(folder / "manifest.json"), str(folder / "fixture.json"),
                              str(folder / "policy.json"), model_mode="stub",
                              expected_manifest_hash=manifest_sha256(manifest)), policy


def _request(payload: dict[str, object], policy: SnapshotPolicy) -> PredictionRequestV2:
    question = payload["question"]
    race = payload["raceContext"]
    assert isinstance(question, dict) and isinstance(race, dict)
    question.update({"questionText": policy.question_text, "choiceLimit": 1, "options": [
        {"optionId": key, "optionNo": index, "optionText": text, "points": None, "chance": None}
        for index, (key, text) in enumerate(((18, "Pierre Gasly"), (11059, "Franco Colapinto")), 1)
    ]})
    race.update({"meetingKey": 1174, "sessionKey": None})
    payload["promptVersion"] = policy.prompt_version
    payload["dataCutoff"] = "2026-09-27T07:02:00Z"
    return PredictionRequestV2.model_validate(payload)


def test_replay_binds_snapshot_and_option_to_driver(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    context, policy = _bundle(tmp_path)
    request = _request(request_v2_payload, policy)
    store = InboxStore(tmp_path / "inbox.sqlite")
    store.save(request)
    model = CapturingModel()
    processor = PredictionProcessor(store, load_lap_fixture(str(tmp_path / "fixture.json")),
                                    model, policy, replay_context=context)
    assert asyncio.run(processor.process_once())
    assert model.calls == 1
    assert model.context["predictionTask"]["optionDrivers"] == {"18": 10, "11059": 43}
    event, = store.pending_outbox()
    assert event.outcome_type == "RESULT"
    assert json.loads(event.payload_json)["reasoningSummary"].startswith("HISTORICAL_ENGINEERING_REPLAY[")
    with store._connect() as connection:
        frozen = json.loads(connection.execute("SELECT feature_snapshot_json FROM request_inbox").fetchone()[0])
    assert frozen["manifestHash"] == context.manifest_hash
    assert frozen["sourceFirstSeenStatus"] == "UNKNOWN"


def test_tampered_bundle_is_rejected_before_model(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    context, policy = _bundle(tmp_path)
    request = _request(request_v2_payload, policy)
    store = InboxStore(tmp_path / "inbox.sqlite")
    store.save(request)
    (tmp_path / "fixture.json").write_text("[]", encoding="utf-8")
    model = CapturingModel()
    processor = PredictionProcessor(store, load_lap_fixture(str(tmp_path / "fixture.json")),
                                    model, policy, replay_context=context)
    assert asyncio.run(processor.process_once())
    assert model.calls == 0
    assert json.loads(store.pending_outbox()[0].payload_json)["failureCode"] == "VERSION_UNAVAILABLE"


def test_wrong_deployment_mode_fails_at_load(tmp_path: Path) -> None:
    _bundle(tmp_path)
    with pytest.raises(ValueError, match="model mode"):
        ReplayContext.load(str(tmp_path / "manifest.json"), str(tmp_path / "fixture.json"),
                           str(tmp_path / "policy.json"), model_mode="real",
                           expected_manifest_hash="a" * 64)


def test_bundle_requires_approved_deployment_hash(tmp_path: Path) -> None:
    _bundle(tmp_path)
    with pytest.raises(ValueError, match="approved deployment hash"):
        ReplayContext.load(str(tmp_path / "manifest.json"), str(tmp_path / "fixture.json"),
                           str(tmp_path / "policy.json"), model_mode="stub",
                           expected_manifest_hash="a" * 64)


def test_bundle_rejects_rehashed_fixture_duration_not_derived_from_raw(tmp_path: Path) -> None:
    _bundle(tmp_path)
    fixture_path = tmp_path / "fixture.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    fixture[0]["durationSeconds"] = 90.0
    fixture_path.write_text(json.dumps(fixture), encoding="utf-8")
    path = tmp_path / "manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    digest = canonical_sha256(fixture)
    manifest["fileHashes"]["fixture"] = digest
    manifest["hashes"]["normalizedLaps"] = digest
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="differs from raw lap derivation"):
        ReplayContext.load(str(path), str(fixture_path), str(tmp_path / "policy.json"),
                           model_mode="stub", expected_manifest_hash=manifest_sha256(manifest))


def test_bundle_policy_mapping_mismatch_is_rejected_at_load(tmp_path: Path) -> None:
    _bundle(tmp_path)
    path = tmp_path / "manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    policy_path = tmp_path / "policy.json"
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    policy["option_drivers"] = {"18": 43, "11059": 10}
    policy_path.write_text(json.dumps(policy), encoding="utf-8")
    manifest["policy"] = policy
    manifest["fileHashes"]["policy"] = canonical_sha256(policy)
    manifest["hashes"]["policy"] = canonical_sha256(policy)
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="policy differs from verified identity"):
        ReplayContext.load(str(path), str(tmp_path / "fixture.json"),
                           str(policy_path), model_mode="stub",
                           expected_manifest_hash=manifest_sha256(manifest))


def test_runtime_policy_mapping_mismatch_is_rejected_before_model(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    context, policy = _bundle(tmp_path)
    request = _request(request_v2_payload, policy)
    store = InboxStore(tmp_path / "inbox.sqlite")
    store.save(request)
    wrong_policy = policy.model_copy(update={"option_drivers": {18: 43, 11059: 10}})
    model = CapturingModel()
    processor = PredictionProcessor(store, load_lap_fixture(str(tmp_path / "fixture.json")),
                                    model, wrong_policy, replay_context=context)
    assert asyncio.run(processor.process_once())
    assert model.calls == 0
    assert json.loads(store.pending_outbox()[0].payload_json)["failureCode"] == "UNSUPPORTED_QUESTION"


def test_replaced_manifest_list_is_terminal_without_model_call(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    context, policy = _bundle(tmp_path)
    store = InboxStore(tmp_path / "inbox.sqlite")
    store.save(_request(request_v2_payload, policy))
    (tmp_path / "manifest.json").write_text("[]", encoding="utf-8")
    model = CapturingModel()
    processor = PredictionProcessor(store, load_lap_fixture(str(tmp_path / "fixture.json")),
                                    model, policy, replay_context=context)
    assert asyncio.run(processor.process_once())
    assert model.calls == 0
    assert json.loads(store.pending_outbox()[0].payload_json)["failureCode"] == "VERSION_UNAVAILABLE"


def test_stub_configuration_does_not_require_or_allow_http_model() -> None:
    fields = {
        "prediction_enabled": True,
        "prediction_execution_mode": "HISTORICAL_ENGINEERING_REPLAY",
        "prediction_replay_model_mode": "stub",
        "prediction_replay_manifest_path": "/bundle/manifest.json",
        "prediction_replay_manifest_hash": "a" * 64,
        "prediction_policy_path": "/bundle/policy.json",
        "prediction_laps_path": "/bundle/fixture.json",
    }
    assert Settings(**fields).model_url == ""
    with pytest.raises(ValueError, match="must not configure a model endpoint"):
        Settings(**fields, model_url="https://model.example.test")


def test_local_stub_uses_frozen_lap_medians_without_network(
    tmp_path: Path, request_v2_payload: dict[str, object]
) -> None:
    context, policy = _bundle(tmp_path)
    store = InboxStore(tmp_path / "inbox.sqlite")
    store.save(_request(request_v2_payload, policy))
    processor = PredictionProcessor(store, load_lap_fixture(str(tmp_path / "fixture.json")),
                                    ReplayBaselineModel(), policy, replay_context=context)
    assert asyncio.run(processor.process_once())
    outcome = json.loads(store.pending_outbox()[0].payload_json)
    assert outcome["selectedOptions"][0]["optionId"] == 18
    assert outcome["reasoningSummary"].startswith("HISTORICAL_ENGINEERING_REPLAY[")


def test_local_stub_selects_fastest_registered_option() -> None:
    payload = {
        "predictionTask": {"kind": "HISTORICAL_ENGINEERING_REPLAY",
                           "optionDrivers": {"17": 10, "29": 43}},
        "features": {"drivers": [
            {"driverNumber": 10, "medianLapSeconds": 89.1},
            {"driverNumber": 43, "medianLapSeconds": 88.5},
        ]},
    }
    candidate = asyncio.run(ReplayBaselineModel().predict(json.dumps(payload)))
    assert candidate.option_ids == [29]
    assert candidate.confidence >= 0.5
