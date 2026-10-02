"""将获准来源捕获为不可执行暂存，并原子封签为受校验回放包。"""

from __future__ import annotations

import json
import os
import shutil
import stat
import tempfile
import uuid
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from f1_predict.prediction.policy import SnapshotPolicy
from f1_predict.replay.export import RAW_FIELDS, _project_raw_row, export_laps
from f1_predict.replay.manifest import (
    CLEAN_RULE_VERSION,
    FILE_NAMES,
    _validate_identity,
    _validate_source_identity,
    build_manifest,
    canonical_json_bytes,
    canonical_sha256,
    format_utc,
    manifest_sha256,
    parse_utc,
)

_STAGING_FILE = "staging.json"
_CAPTURE_DATA_FILE = "capture_data.json"
_STATUS_FILE = "staging_status.json"
_LEGACY_RUNTIME_PATH = Path(__file__).resolve().parents[4] / "tests" / "e2e" / ".runtime"
_SOURCE_FIELDS = {"question", "snapshot", "season", "round", "sessions", "options"}
_IDENTITY_FIELDS = {
    "sourceQuestionId", "sourceSnapshotId", "sourceRoundId", "questionTextHash",
    "optionTextHashes", "optionDrivers", "meetingIdentity", "sessions", "drivers",
    "evidenceHashes",
}


@dataclass(frozen=True, slots=True)
class StagingCapture:
    """不可执行来源捕获；不包含可被运行器加载的 manifest。"""

    path: Path
    source: dict[str, Any]
    identity: dict[str, Any]
    raw_rows: tuple[dict[str, Any], ...]
    sporting_cutoff: datetime
    import_started_at: datetime
    import_completed_at: datetime
    first_seen_at: datetime
    capture_hash: str


@dataclass(frozen=True, slots=True)
class SealedBundle:
    """完成原子发布并由受控回放上下文验证的包。"""

    manifest_path: Path
    manifest_hash: str
    policy: SnapshotPolicy


def capture_staging(
    root: Path,
    source: dict[str, Any],
    raw_rows: list[dict[str, Any]],
    identity: dict[str, Any],
    sporting_cutoff: datetime,
    *,
    clock: Callable[[], datetime],
) -> StagingCapture:
    """在独占私有目录中冻结来源证据；任何错误都不会生成 manifest。"""
    root = Path(root)
    _reject_legacy_runtime_path(root)
    _check_private_directory(root)
    if not isinstance(source, dict) or set(source) != _SOURCE_FIELDS:
        raise ValueError("source must contain exactly the approved source sections")
    _validate_source(source)
    if not isinstance(identity, dict) or set(identity) != _IDENTITY_FIELDS:
        raise ValueError("staging identity must contain exactly source identity fields")
    _validate_source_identity(identity)
    if not isinstance(raw_rows, list) or len(raw_rows) > 500:
        raise ValueError("staging raw rows must not exceed the 500-row bound")
    # 捕获只保留安全白名单字段，封签时复用导出器检查重复冲突与圈速规则。
    if any(not isinstance(row, Mapping) for row in raw_rows):
        raise TypeError("staging raw rows must be mappings")
    if any(not row.keys() <= RAW_FIELDS for row in raw_rows):
        raise ValueError("staging raw rows contain unknown source fields")
    projected_rows = [_project_raw_row(row) for row in raw_rows]
    cutoff = parse_utc(sporting_cutoff, field="sportingCutoff")
    started = parse_utc(clock(), field="importStartedAt")
    first_seen = started
    staging_path = root / f"staging-{uuid.uuid4().hex}"
    os.mkdir(staging_path, 0o700)
    data_payload = {
        "format": "f1-replay-capture-data-v1",
        "source": source,
        "identity": identity,
        "rawRows": projected_rows,
        "sportingCutoff": format_utc(cutoff),
        "importStartedAt": format_utc(started),
        "firstSeenAt": format_utc(first_seen),
    }
    data_durable = False
    try:
        _check_private_directory(staging_path)
        _write_exclusive(
            staging_path / _STATUS_FILE,
            {"format": "f1-replay-staging-status-v1", "state": "CAPTURING", "startedAt": format_utc(started)},
        )
        _write_exclusive(staging_path / _CAPTURE_DATA_FILE, data_payload)
        _fsync_directory(staging_path)
        data_durable = True

        completed = parse_utc(clock(), field="importCompletedAt")
        if started > completed:
            raise ValueError("importStartedAt must not be later than importCompletedAt")
        metadata = {
            "format": "f1-replay-staging-v1",
            "dataHash": canonical_sha256(data_payload),
            "importCompletedAt": format_utc(completed),
        }
        capture_hash = canonical_sha256(metadata)
        metadata["captureHash"] = capture_hash
        _write_exclusive(staging_path / _STAGING_FILE, metadata)
        _write_replace(
            staging_path / _STATUS_FILE,
            {
                "format": "f1-replay-staging-status-v1",
                "state": "READY",
                "startedAt": format_utc(started),
                "completedAt": format_utc(completed),
                "captureHash": capture_hash,
            },
        )
        _fsync_directory(staging_path)
    except BaseException as error:
        try:
            _write_replace(
                staging_path / _STATUS_FILE,
                {
                    "format": "f1-replay-staging-status-v1",
                    "state": "FAILED",
                    "startedAt": format_utc(started),
                    "captureDataPersisted": data_durable,
                    "errorType": type(error).__name__,
                },
            )
            _fsync_directory(staging_path)
        except OSError:
            pass
        raise
    return StagingCapture(
        path=staging_path,
        source=source,
        identity=identity,
        raw_rows=tuple(projected_rows),
        sporting_cutoff=cutoff,
        import_started_at=started,
        import_completed_at=completed,
        first_seen_at=first_seen,
        capture_hash=capture_hash,
    )


def load_staging(path: Path) -> StagingCapture:
    """重新读取并校验不可执行捕获及其捕获哈希。"""
    path = Path(path)
    _reject_legacy_runtime_path(path)
    _check_private_directory(path)
    status_path = path / _STATUS_FILE
    _check_regular_private_file(status_path)
    status = json.loads(status_path.read_text(encoding="utf-8"))
    if (
        not isinstance(status, dict)
        or status.get("format") != "f1-replay-staging-status-v1"
        or status.get("state") != "READY"
    ):
        state = status.get("state", "UNKNOWN") if isinstance(status, dict) else "UNKNOWN"
        raise ValueError(f"staging capture is not ready; state={state}")

    data_path = path / _CAPTURE_DATA_FILE
    file_path = path / _STAGING_FILE
    _check_regular_private_file(data_path)
    _check_regular_private_file(file_path)
    data_payload = json.loads(data_path.read_text(encoding="utf-8"))
    metadata = json.loads(file_path.read_text(encoding="utf-8"))
    if not isinstance(data_payload, dict) or data_payload.get("format") != "f1-replay-capture-data-v1":
        raise ValueError("unsupported replay capture data format")
    expected = metadata.pop("captureHash", None) if isinstance(metadata, dict) else None
    if not isinstance(expected, str) or canonical_sha256(metadata) != expected:
        raise ValueError("staging capture hash mismatch")
    if set(metadata) != {"format", "dataHash", "importCompletedAt"} or metadata["format"] != "f1-replay-staging-v1":
        raise ValueError("staging metadata contains unknown fields")
    if (
        set(status) != {"format", "state", "startedAt", "completedAt", "captureHash"}
        or status["captureHash"] != expected
        or status["completedAt"] != metadata["importCompletedAt"]
    ):
        raise ValueError("staging ready status differs from immutable capture metadata")
    if set(data_payload) != {
        "format", "source", "identity", "rawRows", "sportingCutoff", "importStartedAt", "firstSeenAt",
    }:
        raise ValueError("staging capture data contains unknown fields")
    if metadata["dataHash"] != canonical_sha256(data_payload):
        raise ValueError("staging capture data hash mismatch")
    source, identity, rows = data_payload["source"], data_payload["identity"], data_payload["rawRows"]
    if not isinstance(source, dict) or set(source) != _SOURCE_FIELDS:
        raise ValueError("staging source schema is invalid")
    _validate_source(source)
    if not isinstance(identity, dict) or set(identity) != _IDENTITY_FIELDS:
        raise ValueError("staging identity schema is invalid")
    _validate_source_identity(identity)
    if not isinstance(rows, list) or len(rows) > 500:
        raise ValueError("staging raw rows exceed the supported bound")
    if any(not isinstance(row, dict) or not row.keys() <= RAW_FIELDS for row in rows):
        raise ValueError("staging raw rows contain unknown or invalid fields")
    rows = [_project_raw_row(row) for row in rows]
    times = {
        field: parse_utc(value, field=field)
        for field, value in (
            ("sportingCutoff", data_payload["sportingCutoff"]),
            ("importStartedAt", data_payload["importStartedAt"]),
            ("firstSeenAt", data_payload["firstSeenAt"]),
            ("importCompletedAt", metadata["importCompletedAt"]),
        )
    }
    if times["importStartedAt"] > times["importCompletedAt"] or not (
        times["importStartedAt"] <= times["firstSeenAt"] <= times["importCompletedAt"]
    ):
        raise ValueError("staging observation time is outside its capture interval")
    if status["startedAt"] != format_utc(times["importStartedAt"]):
        raise ValueError("staging ready status start time differs from captured source time")
    return StagingCapture(
        path=path,
        source=source,
        identity=identity,
        raw_rows=tuple(rows),
        sporting_cutoff=times["sportingCutoff"],
        import_started_at=times["importStartedAt"],
        import_completed_at=times["importCompletedAt"],
        first_seen_at=times["firstSeenAt"],
        capture_hash=expected,
    )


def seal_bundle(
    staging: StagingCapture | Path,
    destination: Path,
    id_mapping: dict[str, Any],
    *,
    model_version: str = "replay-baseline-v1",
    minimum_laps: int = 3,
) -> SealedBundle:
    """将来源捕获绑定到真实隔离 ID，在同文件系统原子发布完整包。"""
    capture = load_staging(staging.path if isinstance(staging, StagingCapture) else Path(staging))
    destination = Path(destination)
    _reject_legacy_runtime_path(destination)
    parent = destination.parent
    _check_private_directory(parent)
    if destination.exists() or destination.is_symlink():
        raise FileExistsError("sealed bundle destination already exists")
    required_mapping_fields = {"questionId", "snapshotId", "roundId", "optionIds"}
    if (
        not isinstance(id_mapping, dict)
        or not required_mapping_fields <= set(id_mapping)
        or set(id_mapping) - required_mapping_fields - {"seasonId"}
    ):
        raise ValueError("isolated ID mapping must bind question, snapshot, round and options")
    provenance = {
        "source": capture.source,
        "captureHash": capture.capture_hash,
        "identityHash": canonical_sha256(capture.identity),
    }
    provenance_hash = canonical_sha256(provenance)
    if "seasonId" in id_mapping and (
        not isinstance(id_mapping["seasonId"], int)
        or isinstance(id_mapping["seasonId"], bool)
        or id_mapping["seasonId"] <= 0
    ):
        raise ValueError("isolated seasonId must be positive when supplied")
    manifest_mapping = {field: id_mapping[field] for field in required_mapping_fields}
    identity = {
        **capture.identity,
        "isolatedIdMapping": manifest_mapping,
        "evidenceHashes": {
            **capture.identity["evidenceHashes"],
            "stagingCapture": capture.capture_hash,
            "sourceProvenance": provenance_hash,
        },
    }
    _validate_identity(identity)
    question = capture.source["question"]
    snapshot = capture.source["snapshot"]
    if canonical_sha256(snapshot["raw"]["Text"]) != identity["questionTextHash"]:
        raise ValueError("snapshot question text hash differs from verified source identity")
    source_options = {str(item["optionId"]): item for item in capture.source["options"]}
    if set(source_options) != set(identity["optionTextHashes"]):
        raise ValueError("source option IDs differ from verified identity")
    for source_id, option in source_options.items():
        if canonical_sha256(option["optionText"]) != identity["optionTextHashes"][source_id]:
            raise ValueError("source option text hash differs from verified identity")
    if str(question["id"]) != str(identity["sourceQuestionId"]):
        raise ValueError("source question primary key differs from verified identity")
    source_meeting_keys = {str(item["meetingKey"]) for item in capture.source["sessions"]}
    if (
        str(question["latestSnapshotId"]) != str(snapshot["id"])
        or str(snapshot["questionId"]) != str(question["id"])
        or str(question["roundId"]) != str(capture.source["round"]["id"])
        or str(capture.source["round"]["seasonId"]) != str(capture.source["season"]["id"])
        or source_meeting_keys != {str(identity["meetingIdentity"]["mysqlMeetingKey"])}
    ):
        raise ValueError("source question, snapshot, round, season or meeting relationships are inconsistent")
    if str(capture.source["round"]["id"]) != str(identity["sourceRoundId"]):
        raise ValueError("source round ID differs from verified identity")
    if str(snapshot["id"]) != str(identity["sourceSnapshotId"]):
        raise ValueError("source snapshot ID differs from verified identity")
    actual_options = {int(source_id): int(actual) for source_id, actual in id_mapping["optionIds"].items()}
    policy = SnapshotPolicy(
        question_snapshot_id=id_mapping["snapshotId"],
        question_id=id_mapping["questionId"],
        question_text=snapshot["raw"]["Text"],
        meeting_key=identity["meetingIdentity"]["mysqlMeetingKey"],
        session_keys=tuple(item["sessionKey"] for item in identity["sessions"]),
        option_drivers={actual_options[int(source_id)]: driver for source_id, driver in identity["optionDrivers"].items()},
        minimum_laps=minimum_laps,
        model_version=model_version,
        prompt_version="prompt-h2h-replay-v1",
        feature_version="feature-v1",
    )
    exported = export_laps(
        capture.raw_rows,
        meeting_key=identity["meetingIdentity"]["mysqlMeetingKey"],
        mongo_meeting_key=identity["meetingIdentity"]["mongoMeetingKey"],
        session_keys=tuple(item["sessionKey"] for item in identity["sessions"]),
        driver_numbers=tuple(sorted(set(identity["optionDrivers"].values()))),
        sporting_cutoff=capture.sporting_cutoff,
        import_started_at=capture.import_started_at,
        import_completed_at=capture.import_completed_at,
        first_seen_at=capture.first_seen_at,
        identity_metadata=identity,
    )
    policy_json = policy.model_dump(mode="json")
    query = {
        "collection": "laps",
        "scope": "fixed meeting/session/driver",
        "projection": "fixed lap whitelist",
        "timeoutMs": "5000",
        "limit": "501",
    }
    versions = {"exporter": "1", "cleanRule": CLEAN_RULE_VERSION, "modelMode": "stub"}
    manifest = build_manifest(
        identity,
        import_started_at=capture.import_started_at,
        import_completed_at=capture.import_completed_at,
        sporting_cutoff=capture.sporting_cutoff,
        raw_rows=list(exported.raw_rows),
        normalized_laps=list(exported.normalized_laps),
        query=query,
        counts=exported.counts,
        rules={"version": CLEAN_RULE_VERSION},
        versions=versions,
        policy=policy_json,
        file_hashes={
            "rawRows": canonical_sha256(list(exported.raw_rows)),
            "fixture": canonical_sha256(list(exported.normalized_laps)),
            "policy": canonical_sha256(policy_json),
            "identityMetadata": canonical_sha256(identity),
        },
    )
    _validate_source(capture.source)
    temp_path = Path(tempfile.mkdtemp(prefix=f".{destination.name}-", dir=parent))
    os.chmod(temp_path, 0o700)
    try:
        sidecars = {
            FILE_NAMES["rawRows"]: list(exported.raw_rows),
            FILE_NAMES["fixture"]: list(exported.normalized_laps),
            FILE_NAMES["policy"]: policy_json,
            FILE_NAMES["identityMetadata"]: identity,
            "source_provenance.json": {
                "source": capture.source,
                "captureHash": capture.capture_hash,
                "identityHash": canonical_sha256(capture.identity),
            },
        }
        for name, value in sidecars.items():
            _write_exclusive(temp_path / name, value)
        _write_exclusive(temp_path / "manifest.json", manifest)
        _fsync_directory(temp_path)
        from f1_predict.prediction.replay_context import ReplayContext

        manifest_hash = manifest_sha256(manifest)
        ReplayContext.load(
            str(temp_path / "manifest.json"),
            str(temp_path / FILE_NAMES["fixture"]),
            str(temp_path / FILE_NAMES["policy"]),
            model_mode="stub",
            expected_manifest_hash=manifest_hash,
        )
        os.rename(temp_path, destination)
        _fsync_directory(parent)
    except BaseException:
        shutil.rmtree(temp_path, ignore_errors=True)
        raise
    return SealedBundle(
        manifest_path=destination / "manifest.json",
        manifest_hash=manifest_hash,
        policy=policy,
    )


def _validate_source(source: dict[str, Any]) -> None:
    """拒绝未审阅字段，保证来源侧车不夹带任意对象。"""
    schemas = {
        "question": {"id", "roundId", "gamedayId", "sourceQuestionId", "questionNo", "status", "latestSnapshotId", "firstSeenAt"},
        "snapshot": {"id", "questionId", "snapshotNo", "contentHash", "createdAt", "raw"},
        "season": {"id", "year", "name", "status"},
        "round": {"id", "seasonId", "roundNumber", "grandPrixName", "circuitName", "country", "locality", "startDate", "endDate", "status"},
    }
    for section, fields in schemas.items():
        value = source[section]
        if not isinstance(value, dict) or set(value) != fields:
            raise ValueError(f"source {section} schema is incomplete or contains unknown fields")
    raw = source["snapshot"]["raw"]
    if not isinstance(raw, dict) or set(raw) != {"Text", "SubText", "OptionTemplateId", "Config"}:
        raise ValueError("source snapshot raw schema is invalid")
    if not isinstance(raw["Config"], dict) or set(raw["Config"]) != {"ChoiceLimit"}:
        raise ValueError("source snapshot config schema is invalid")
    if not isinstance(source["sessions"], list) or not isinstance(source["options"], list):
        raise TypeError("source sessions and options must be lists")
    if not 1 <= len(source["sessions"]) <= 64:
        raise ValueError("source session catalog is empty or exceeds its bound")
    session_fields = {
        "meetingKey", "sessionKey", "sessionName", "sessionType", "gamedayId",
        "startDateUtc", "endDateUtc", "status",
    }
    for session in source["sessions"]:
        if not isinstance(session, dict) or set(session) != session_fields:
            raise ValueError("source session schema is incomplete or contains unknown fields")
    if len(source["options"]) != 2:
        raise ValueError("source must contain exactly two options")
    option_fields = {"optionId", "optionNo", "optionText", "points", "chance"}
    for option in source["options"]:
        if not isinstance(option, dict) or set(option) != option_fields:
            raise ValueError("source option schema is incomplete or contains unknown fields")
        if not isinstance(option["optionText"], str) or not option["optionText"].strip():
            raise ValueError("source option text must be non-empty")
    question, snapshot, round_data, season = (
        source["question"], source["snapshot"], source["round"], source["season"]
    )
    if (
        str(question["latestSnapshotId"]) != str(snapshot["id"])
        or str(snapshot["questionId"]) != str(question["id"])
        or str(question["roundId"]) != str(round_data["id"])
        or str(round_data["seasonId"]) != str(season["id"])
    ):
        raise ValueError("source question, snapshot, round and season relationships are inconsistent")
    if not isinstance(raw["Text"], str) or not raw["Text"].strip():
        raise ValueError("source snapshot question text must not be empty")


def _reject_legacy_runtime_path(path: Path) -> None:
    """旧 E2E 运行态目录不得作为捕获或封签位置。"""
    candidate = path.resolve(strict=False)
    legacy = _LEGACY_RUNTIME_PATH.resolve(strict=False)
    if candidate == legacy or legacy in candidate.parents:
        raise ValueError("legacy E2E runtime path is not allowed for replay artifacts")


def _check_private_directory(path: Path) -> None:
    """拒绝链接、非目录、非当前用户所有或对组/其他用户开放的目录。"""
    _reject_legacy_runtime_path(path)
    absolute = path.absolute()
    if any(part.is_symlink() for part in (absolute, *absolute.parents)):
        raise ValueError("replay directory path must not contain symlinks")
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise ValueError("replay path must be a real directory, not a symlink")
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        raise PermissionError("replay directory must be user-owned and private")


def _check_regular_private_file(path: Path) -> None:
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise ValueError("replay file must be a regular non-symlink file")
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        raise PermissionError("replay file must be user-owned and private")


def _write_exclusive(path: Path, value: Any) -> None:
    data = canonical_json_bytes(value)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb", closefd=False) as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        os.close(descriptor)


def _write_replace(path: Path, value: Any) -> None:
    """原子更新私有状态文件，避免故障状态留下半截 JSON。"""
    if path.exists() or path.is_symlink():
        _check_regular_private_file(path)
    temporary = path.with_name(f".{path.name}-{uuid.uuid4().hex}")
    try:
        _write_exclusive(temporary, value)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
