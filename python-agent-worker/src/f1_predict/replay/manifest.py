"""历史单题回放清单的规范化、哈希和身份门禁。"""

from __future__ import annotations

import hashlib
import json
import math
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Any

MANIFEST_MODE = "HISTORICAL_ENGINEERING_REPLAY"
MANIFEST_VERSION = "replay-manifest-v1"
CANONICAL_VERSION = "canonical-json-v1"
MAX_LAPS = 500
CLEAN_RULE_VERSION = "engineering-lap-proxy-v1"
FILE_NAMES = {
    "rawRows": "raw_rows.json",
    "fixture": "fixture.json",
    "policy": "policy.json",
    "identityMetadata": "identity_metadata.json",
}

_SOURCE_IDENTITY_FIELDS = frozenset(
    {
        "sourceQuestionId",
        "sourceSnapshotId",
        "sourceRoundId",
        "questionTextHash",
        "optionTextHashes",
        "optionDrivers",
        "meetingIdentity",
        "sessions",
        "drivers",
        "evidenceHashes",
    }
)
_REQUIRED_IDENTITY_FIELDS = _SOURCE_IDENTITY_FIELDS | {"isolatedIdMapping"}


def canonical_json_bytes(value: Any) -> bytes:
    """序列化稳定的 UTF-8 JSON，拒绝 NaN/Infinity 等非标准值。"""
    try:
        text = json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    except (TypeError, ValueError) as error:
        raise ValueError("value cannot be encoded as canonical JSON") from error
    return text.encode("utf-8")


def canonical_sha256(value: Any) -> str:
    """计算 canonical-json-v1 的 SHA-256。"""
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def format_utc(value: datetime) -> str:
    """将时区时间格式化为固定精度的 UTC Z 时间戳。"""
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_utc(value: datetime | str, *, field: str) -> datetime:
    """解析并规范化有时区时间，拒绝本地/无时区时间。"""
    if isinstance(value, str):
        try:
            value = datetime.fromisoformat(value)
        except ValueError as error:
            raise ValueError(f"{field} must be a valid timestamp") from error
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{field} must be timezone-aware")
    return value.astimezone(UTC)


def build_manifest(
    identity_metadata: Mapping[str, Any],
    *,
    import_started_at: datetime,
    import_completed_at: datetime,
    sporting_cutoff: datetime,
    raw_rows: list[Mapping[str, Any]],
    normalized_laps: list[Mapping[str, Any]],
    query: Mapping[str, Any],
    counts: Mapping[str, Any],
    rules: Mapping[str, Any],
    versions: Mapping[str, Any],
    policy: Mapping[str, Any],
    file_hashes: Mapping[str, str] | None = None,
) -> dict[str, Any]:
    """构造回放清单；缺少赛事身份与来源证据时拒绝生成可执行清单。"""
    missing = _REQUIRED_IDENTITY_FIELDS.difference(identity_metadata)
    if missing:
        raise ValueError(f"replay identity metadata is incomplete: {', '.join(sorted(missing))}")
    _validate_identity(identity_metadata)
    if not isinstance(rules, Mapping) or not isinstance(rules.get("version"), str) or not rules["version"]:
        raise ValueError("cleaning rules must declare a version")
    if versions.get("cleanRule") != rules["version"]:
        raise ValueError("clean-rule version must match the declared rules version")
    if len(raw_rows) > MAX_LAPS + 1:
        raise ValueError("raw lap rows exceed the 501-row sentinel bound")
    if len(raw_rows) > MAX_LAPS:
        raise ValueError("raw lap rows exceed the supported 500-row bound")
    if len(normalized_laps) > MAX_LAPS:
        raise ValueError("normalized lap rows exceed the supported 500-row bound")
    if not isinstance(counts, Mapping):
        raise TypeError("export counts must be a mapping")
    duplicate_count = counts.get("duplicateMerged")
    raw_count = counts.get("raw")
    exported_count = counts.get("exported")
    if (
        not isinstance(duplicate_count, int)
        or isinstance(duplicate_count, bool)
        or duplicate_count < 0
        or not isinstance(raw_count, int)
        or isinstance(raw_count, bool)
        or raw_count - duplicate_count != len(raw_rows)
        or exported_count != len(normalized_laps)
        or counts.get("conflict") != 0
    ):
        raise ValueError("export counts do not match the raw and normalized row totals")

    started = parse_utc(import_started_at, field="importStartedAt")
    completed = parse_utc(import_completed_at, field="importCompletedAt")
    cutoff = parse_utc(sporting_cutoff, field="sportingCutoff")
    if started > completed:
        raise ValueError("importStartedAt must not be later than importCompletedAt")
    if any(not isinstance(value, str) or not value for value in query.values()):
        raise ValueError("query scope and projection must be explicit strings")

    expected_file_hashes = {
        "rawRows": canonical_sha256(raw_rows),
        "fixture": canonical_sha256(normalized_laps),
        "policy": canonical_sha256(dict(policy)),
        "identityMetadata": canonical_sha256(dict(identity_metadata)),
    }
    supplied_file_hashes = dict(file_hashes or {})
    if set(supplied_file_hashes) != set(expected_file_hashes):
        raise ValueError("fileHashes must include rawRows, fixture, policy and identityMetadata")
    for name, expected in expected_file_hashes.items():
        digest = supplied_file_hashes[name]
        if not _is_sha256(digest) or digest.lower() != expected:
            raise ValueError(f"file hash does not match canonical {name} content")
    actual_hashes = {
        "rawRows": expected_file_hashes["rawRows"],
        "normalizedLaps": expected_file_hashes["fixture"],
        "query": canonical_sha256(dict(query)),
        "identityMetadata": expected_file_hashes["identityMetadata"],
        "policy": expected_file_hashes["policy"],
    }

    manifest = {
        "manifestVersion": MANIFEST_VERSION,
        "canonicalVersion": CANONICAL_VERSION,
        "mode": MANIFEST_MODE,
        "packageId": canonical_sha256(
            {
                "identityMetadata": dict(identity_metadata),
                "importStartedAt": format_utc(started),
                "importCompletedAt": format_utc(completed),
                "rawRowsHash": actual_hashes["rawRows"],
            }
        ),
        "generator": {"name": "f1-predict-replay-export", "version": versions.get("exporter")},
        "query": dict(query),
        "time": {
            "unit": "UTC; durationSeconds in seconds; BSON Date normalized to milliseconds",
            "sportingCutoff": format_utc(cutoff),
            "importStartedAt": format_utc(started),
            "importCompletedAt": format_utc(completed),
            "sourceFirstSeenStatus": "UNKNOWN",
        },
        "identity": dict(identity_metadata),
        "counts": dict(counts),
        "rules": dict(rules),
        "versions": dict(versions),
        "modelMode": versions.get("modelMode"),
        "answerAndQualifyingDataExcluded": True,
        "files": dict(FILE_NAMES),
        "policy": dict(policy),
        "fileHashes": expected_file_hashes,
        "hashes": actual_hashes,
    }
    validate_manifest(manifest)
    return manifest


def manifest_sha256(manifest: Mapping[str, Any]) -> str:
    """返回不含自引用字段的清单 canonical hash。"""
    if not isinstance(manifest, Mapping):
        raise TypeError("manifest must be a mapping")
    return canonical_sha256(dict(manifest))


def validate_manifest(manifest: Mapping[str, Any]) -> None:
    """验证 manifest 的关键模式、边界、身份和哈希字段。"""
    if not isinstance(manifest, Mapping):
        raise ValueError("manifest JSON top level must be an object")  # noqa: TRY004
    if (
        manifest.get("mode") != MANIFEST_MODE
        or manifest.get("manifestVersion") != MANIFEST_VERSION
        or manifest.get("canonicalVersion") != CANONICAL_VERSION
    ):
        raise ValueError("unsupported replay manifest mode or version")
    generator = manifest.get("generator")
    if not isinstance(generator, Mapping) or any(
        not isinstance(generator.get(field), str) or not generator[field]
        for field in ("name", "version")
    ):
        raise ValueError("manifest generator metadata is incomplete")
    identity = manifest.get("identity")
    if not isinstance(identity, Mapping):
        raise TypeError("replay identity metadata must be a mapping")
    _validate_identity(identity)
    counts = manifest.get("counts")
    if not isinstance(counts, Mapping):
        raise TypeError("replay counts must be a mapping")
    count_fields = {
        "raw",
        "duplicateMerged",
        "conflict",
        "excludedIdentity",
        "excludedInvalid",
        "excludedSportingCutoff",
        "excludedProxyRule",
        "exported",
    }
    if set(counts) != count_fields:
        raise ValueError("manifest counts are incomplete or contain unknown fields")
    if any(
        not isinstance(value, int) or isinstance(value, bool) or value < 0
        for value in counts.values()
    ):
        raise ValueError("manifest counts must be non-negative integers")
    exported = counts["exported"]
    if exported > MAX_LAPS or counts["raw"] > MAX_LAPS:
        raise ValueError("manifest counts exceed the supported 500-row bounds")
    if counts["raw"] != sum(
        counts[field]
        for field in (
            "duplicateMerged",
            "excludedIdentity",
            "excludedInvalid",
            "excludedSportingCutoff",
            "excludedProxyRule",
            "exported",
        )
    ):
        raise ValueError("manifest row counts do not reconcile")
    if counts["conflict"] != 0:
        raise ValueError("manifest cannot describe a successful export with conflicts")
    time = manifest.get("time")
    if not isinstance(time, Mapping):
        raise TypeError("replay time metadata must be a mapping")
    parse_utc(time.get("sportingCutoff"), field="sportingCutoff")
    started = parse_utc(time.get("importStartedAt"), field="importStartedAt")
    completed = parse_utc(time.get("importCompletedAt"), field="importCompletedAt")
    if started > completed:
        raise ValueError("importStartedAt must not be later than importCompletedAt")
    if time.get("sourceFirstSeenStatus") != "UNKNOWN":
        raise ValueError("source historical first-seen status must remain UNKNOWN")
    if time.get("unit") != "UTC; durationSeconds in seconds; BSON Date normalized to milliseconds":
        raise ValueError("manifest time unit is unsupported")
    query = manifest.get("query")
    query_fields = {"collection", "scope", "projection", "timeoutMs", "limit"}
    if (
        not isinstance(query, Mapping)
        or set(query) != query_fields
        or not all(isinstance(value, str) and value for value in query.values())
        or query["collection"] != "laps"
        or query["limit"] != "501"
        or not query["timeoutMs"].isdigit()
        or int(query["timeoutMs"]) <= 0
    ):
        raise ValueError("manifest bounded query scope, projection and timeout are incomplete")
    versions = manifest.get("versions")
    if not isinstance(versions, Mapping) or not all(
        isinstance(versions.get(field), str) and versions[field]
        for field in ("exporter", "cleanRule", "modelMode")
    ):
        raise ValueError("manifest version metadata is incomplete")
    if manifest.get("modelMode") != versions["modelMode"]:
        raise ValueError("manifest model mode differs from version metadata")
    if generator["version"] != versions["exporter"]:
        raise ValueError("manifest exporter version is inconsistent")
    rules = manifest.get("rules")
    if (
        not isinstance(rules, Mapping)
        or not isinstance(rules.get("version"), str)
        or not rules["version"]
        or rules["version"] != versions.get("cleanRule")
        or rules["version"] != CLEAN_RULE_VERSION
    ):
        raise ValueError("manifest cleaning rule version is missing or inconsistent")
    policy = manifest.get("policy")
    if not isinstance(policy, Mapping) or not policy:
        raise ValueError("manifest policy file content is missing")
    files = manifest.get("files")
    if files != FILE_NAMES:
        raise ValueError("manifest replay filenames are not canonical")
    if manifest.get("answerAndQualifyingDataExcluded") is not True:
        raise ValueError("manifest must exclude answers and qualifying data")
    hashes = manifest.get("hashes")
    required_hashes = {"rawRows", "normalizedLaps", "query", "identityMetadata", "policy"}
    if not isinstance(hashes, Mapping) or set(hashes) != required_hashes:
        raise ValueError("manifest hashes are incomplete")
    if not all(_is_sha256(value) for value in hashes.values()):
        raise ValueError("manifest hashes must be SHA-256 hex digests")
    file_hashes = manifest.get("fileHashes")
    required_file_hashes = {"rawRows", "fixture", "policy", "identityMetadata"}
    if not isinstance(file_hashes, Mapping) or set(file_hashes) != required_file_hashes:
        raise ValueError("manifest fileHashes are incomplete")
    if not all(_is_sha256(value) for value in file_hashes.values()):
        raise ValueError("manifest fileHashes must be SHA-256 hex digests")
    if file_hashes["rawRows"] != hashes.get("rawRows"):
        raise ValueError("rawRows file hash does not match manifest hash")
    if file_hashes["identityMetadata"] != hashes.get("identityMetadata"):
        raise ValueError("identity metadata file hash does not match manifest hash")
    if file_hashes["fixture"] != hashes.get("normalizedLaps"):
        raise ValueError("fixture file hash does not match manifest hash")
    if file_hashes["policy"] != canonical_sha256(dict(policy)):
        raise ValueError("policy file hash does not match manifest policy content")
    if file_hashes["policy"] != hashes.get("policy"):
        raise ValueError("policy file hash does not match manifest hash")
    if hashes.get("query") != canonical_sha256(dict(query)):
        raise ValueError("query hash does not match manifest query")
    if hashes.get("identityMetadata") != canonical_sha256(dict(identity)):
        raise ValueError("identity metadata hash does not match manifest identity")
    if manifest.get("packageId") != canonical_sha256(
        {
            "identityMetadata": dict(identity),
            "importStartedAt": format_utc(started),
            "importCompletedAt": format_utc(completed),
            "rawRowsHash": hashes["rawRows"],
        }
    ):
        raise ValueError("manifest packageId does not match its frozen content")


def _validate_source_identity(identity: Mapping[str, Any]) -> None:
    """校验尚未绑定隔离数据库 ID 的来源身份元数据。"""
    allowed_fields = _SOURCE_IDENTITY_FIELDS | {"isolatedIdMapping"}
    if not isinstance(identity, Mapping) or not identity.keys() <= allowed_fields:
        raise ValueError("source identity contains unsupported fields")
    _validate_identity(
        identity,
        require_isolated_mapping="isolatedIdMapping" in identity,
    )


def _validate_identity(
    identity: Mapping[str, Any], *, require_isolated_mapping: bool = True
) -> None:
    """只校验身份字段形状和哈希语法，不认证来源或外部核验结论。"""
    required_fields = _REQUIRED_IDENTITY_FIELDS if require_isolated_mapping else _SOURCE_IDENTITY_FIELDS
    allowed_fields = required_fields if require_isolated_mapping else _SOURCE_IDENTITY_FIELDS | {"isolatedIdMapping"}
    if not isinstance(identity, Mapping) or not identity.keys() <= allowed_fields:
        raise ValueError("replay identity contains unsupported fields")
    missing = required_fields.difference(identity)
    if missing:
        raise ValueError(f"replay identity metadata is incomplete: {', '.join(sorted(missing))}")
    for field in ("sourceQuestionId", "sourceSnapshotId", "sourceRoundId"):
        value = identity[field]
        if isinstance(value, bool) or not isinstance(value, (str, int)) or not str(value).strip():
            raise ValueError(f"{field} must be present")
    for field in ("questionTextHash",):
        if not _is_sha256(identity[field]):
            raise ValueError(f"{field} must be a SHA-256 hex digest")
    for field in ("optionTextHashes", "evidenceHashes"):
        value = identity[field]
        if not isinstance(value, Mapping) or not value:
            raise ValueError(f"{field} must contain source evidence")
        if not all(isinstance(key, str) and key and _is_sha256(digest) for key, digest in value.items()):
            raise ValueError(f"{field} must contain SHA-256 hex digests")
    option_drivers = identity["optionDrivers"]
    if not isinstance(option_drivers, Mapping) or not option_drivers:
        raise ValueError("optionDrivers must map every option to a driver number")
    if set(option_drivers) != set(identity["optionTextHashes"]):
        raise ValueError("optionDrivers must match optionTextHashes IDs")
    if any(
        not isinstance(driver, int) or isinstance(driver, bool) or driver <= 0
        for driver in option_drivers.values()
    ):
        raise ValueError("optionDrivers must contain positive driver numbers")
    for field in ("meetingIdentity", "sessions", "drivers"):
        value = identity[field]
        if not isinstance(value, (Mapping, list)) or not value:
            raise ValueError(f"{field} must contain identity evidence metadata")
    meeting_identity = identity["meetingIdentity"]
    if (
        not isinstance(meeting_identity, Mapping)
        or not _positive_integer(meeting_identity.get("mysqlMeetingKey"))
        or not _positive_integer(meeting_identity.get("mongoMeetingKey"))
    ):
        raise ValueError("meetingIdentity must contain both positive source meeting keys")
    sessions = identity["sessions"]
    allowed_session_types = {"Practice", "Practice 1", "Practice 2", "Practice 3"}
    if not isinstance(sessions, list) or any(
        not isinstance(session, Mapping)
        or not _positive_integer(session.get("sessionKey"))
        or session.get("type") not in allowed_session_types
        for session in sessions
    ):
        raise ValueError("sessions must list unique positive practice session metadata")
    if len({session["sessionKey"] for session in sessions}) != len(sessions):
        raise ValueError("session identity metadata contains duplicate keys")
    drivers = identity["drivers"]
    if not isinstance(drivers, list) or any(
        not isinstance(driver, Mapping)
        or not isinstance(driver.get("driverNumber"), int)
        or isinstance(driver.get("driverNumber"), bool)
        or driver["driverNumber"] <= 0
        for driver in drivers
    ):
        raise ValueError("drivers must list positive driverNumber metadata")
    if {driver["driverNumber"] for driver in drivers} != set(option_drivers.values()):
        raise ValueError("driver metadata must match optionDrivers values")
    if require_isolated_mapping:
        id_mapping = identity["isolatedIdMapping"]
        if not isinstance(id_mapping, Mapping) or set(id_mapping) - {
            "questionId", "snapshotId", "roundId", "seasonId", "optionIds"
        } or any(
            not isinstance(id_mapping.get(field), int)
            or isinstance(id_mapping.get(field), bool)
            or id_mapping[field] <= 0
            for field in ("questionId", "snapshotId")
        ):
            raise ValueError("isolatedIdMapping must bind positive questionId and snapshotId values")
        for optional_id in ("roundId", "seasonId"):
            if optional_id in id_mapping and (
                not isinstance(id_mapping[optional_id], int)
                or isinstance(id_mapping[optional_id], bool)
                or id_mapping[optional_id] <= 0
            ):
                raise ValueError(f"isolatedIdMapping.{optional_id} must be positive when present")
        isolated_options = id_mapping.get("optionIds")
        if (
            not isinstance(isolated_options, Mapping)
            or set(isolated_options) != set(identity["optionTextHashes"])
            or any(
                not isinstance(option_id, int) or isinstance(option_id, bool) or option_id <= 0
                for option_id in isolated_options.values()
            )
            or len(set(isolated_options.values())) != len(isolated_options)
        ):
            raise ValueError("isolatedIdMapping.optionIds must map source options to unique isolated IDs")
    _reject_non_finite(identity)


def _reject_non_finite(value: Any) -> None:
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("identity metadata contains a non-finite number")
    if isinstance(value, Mapping):
        for item in value.values():
            _reject_non_finite(item)
    elif isinstance(value, (list, tuple)):
        for item in value:
            _reject_non_finite(item)


def _positive_integer(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _is_sha256(value: Any) -> bool:
    if not isinstance(value, str) or len(value) != 64:
        return False
    try:
        int(value, 16)
    except ValueError:
        return False
    return True
