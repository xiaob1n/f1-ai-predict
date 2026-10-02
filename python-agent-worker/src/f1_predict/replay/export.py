"""真实数据回放的有界只读导出与纯函数规范化。"""

from __future__ import annotations

import math
import re
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import datetime, timedelta
from ipaddress import ip_address
from typing import Any, Protocol

from f1_predict.replay.manifest import (
    MAX_LAPS,
    canonical_json_bytes,
    canonical_sha256,
    format_utc,
    parse_utc,
)

MAX_SOURCE_ROWS = MAX_LAPS + 1
SOURCE_QUERY_TIMEOUT_MS = 5_000
SOURCE_ALIAS = "OpenF1Archive/laps"
SOURCE_METADATA_ALIAS_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
SOURCE_METADATA_RECORD_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
SOURCE_METADATA_HASH_PATTERN = re.compile(r"[0-9a-fA-F]{64}")
SOURCE_METADATA_FIELDS = frozenset({"sourceAlias", "sourceRecordId", "sourceContentHash"})
RAW_FIELDS = frozenset(
    {
        "_key",
        "meeting_key",
        "session_key",
        "driver_number",
        "lap_number",
        "date_start",
        "lap_duration",
        "duration_sector_1",
        "duration_sector_2",
        "duration_sector_3",
        "is_pit_out_lap",
        "source_metadata",
    }
)
RAW_PROJECTION = {field: 1 for field in RAW_FIELDS}
RAW_PROJECTION["_id"] = 0
FIXTURE_FIELDS = frozenset(
    {
        "recordId",
        "meetingKey",
        "sessionKey",
        "driverNumber",
        "eventTime",
        "firstSeenAt",
        "lapEnd",
        "durationSeconds",
        "isClean",
        "sourceEndpoint",
        "sourceContentHash",
    }
)


class ReadOnlyLapSource(Protocol):
    """只读源客户端边界；具体适配器必须将参数下推到数据库查询。"""

    def find(
        self,
        collection: str,
        query: Mapping[str, Any],
        *,
        projection: Mapping[str, int],
        max_time_ms: int,
        limit: int,
    ) -> Iterable[Mapping[str, Any]]:
        """按过滤、投影、超时和 limit 返回文档。"""


@dataclass(frozen=True, slots=True)
class ExportResult:
    """可审计导出的原始投影、11字段夹具和数量对账。"""

    raw_rows: tuple[dict[str, Any], ...]
    normalized_laps: tuple[dict[str, Any], ...]
    counts: dict[str, int]


def read_laps_bounded(
    source: ReadOnlyLapSource,
    *,
    mongo_meeting_key: int,
    session_keys: tuple[int, ...],
    driver_numbers: tuple[int, ...],
    query_timeout_ms: int = SOURCE_QUERY_TIMEOUT_MS,
) -> tuple[dict[str, Any], ...]:
    """通过注入的只读客户端，执行固定投影、范围过滤与 501 哨兵读取。"""
    if not _positive_int(mongo_meeting_key):
        raise ValueError("mongoMeetingKey must be a positive integer")
    if not session_keys or not all(_positive_int(key) for key in session_keys):
        raise ValueError("session keys must be non-empty positive integers")
    if not driver_numbers or not all(_positive_int(number) for number in driver_numbers):
        raise ValueError("driver numbers must be non-empty positive integers")
    if not _positive_int(query_timeout_ms):
        raise ValueError("query timeout must be a positive integer")
    query = {
        "meeting_key": mongo_meeting_key,
        "session_key": {"$in": list(session_keys)},
        "driver_number": {"$in": list(driver_numbers)},
    }
    cursor = source.find(
        "laps",
        query,
        projection=dict(RAW_PROJECTION),
        max_time_ms=query_timeout_ms,
        limit=MAX_SOURCE_ROWS,
    )
    rows = []
    for row in cursor:
        if len(rows) == MAX_SOURCE_ROWS:
            break
        if not isinstance(row, Mapping):
            raise TypeError("source lap row must be a mapping")
        rows.append(_project_raw_row(row))
    if len(rows) > MAX_LAPS:
        raise ValueError("source query exceeded the 500-row export limit")
    return tuple(rows)


def export_laps(
    rows: Iterable[Mapping[str, Any]],
    *,
    meeting_key: int,
    mongo_meeting_key: int,
    session_keys: tuple[int, ...],
    driver_numbers: tuple[int, ...],
    sporting_cutoff: datetime,
    import_started_at: datetime,
    import_completed_at: datetime,
    first_seen_at: datetime,
    identity_metadata: Mapping[str, Any],
    source_alias: str = SOURCE_ALIAS,
) -> ExportResult:
    """纯函数规范化有限原始记录；身份缺失、冲突或越界时不出成功包。"""
    from f1_predict.replay.manifest import _validate_source_identity

    _validate_source_identity(identity_metadata)
    if not all(_positive_int(value) for value in (meeting_key, mongo_meeting_key)):
        raise ValueError("meeting keys must be positive integers")
    if (
        not session_keys
        or len(set(session_keys)) != len(session_keys)
        or not all(_positive_int(value) for value in session_keys)
    ):
        raise ValueError("session keys must be non-empty positive integers")
    if (
        not driver_numbers
        or len(set(driver_numbers)) != len(driver_numbers)
        or not all(_positive_int(value) for value in driver_numbers)
    ):
        raise ValueError("driver numbers must be non-empty positive integers")
    if source_alias != SOURCE_ALIAS:
        raise ValueError("source alias must match the fixed source endpoint")
    meeting_identity = identity_metadata["meetingIdentity"]
    if (
        meeting_identity.get("mysqlMeetingKey") != meeting_key
        or meeting_identity.get("mongoMeetingKey") != mongo_meeting_key
    ):
        raise ValueError("export meeting keys differ from verified identity metadata")
    verified_sessions = {item["sessionKey"] for item in identity_metadata["sessions"]}
    if set(session_keys) != verified_sessions:
        raise ValueError("export session keys differ from verified identity metadata")
    verified_drivers = set(identity_metadata["optionDrivers"].values())
    if set(driver_numbers) != verified_drivers:
        raise ValueError("export driver numbers differ from verified option mapping")
    cutoff = parse_utc(sporting_cutoff, field="sportingCutoff")
    started = parse_utc(import_started_at, field="importStartedAt")
    completed = parse_utc(import_completed_at, field="importCompletedAt")
    observed = parse_utc(first_seen_at, field="firstSeenAt")
    if started > completed or observed < started or observed > completed:
        raise ValueError("firstSeenAt must be observed during the import window")

    raw_rows: list[dict[str, Any]] = []
    normalized: list[dict[str, Any]] = []
    seen: dict[tuple[str, int, int, int, str], bytes] = {}
    counts = {
        "raw": 0,
        "duplicateMerged": 0,
        "conflict": 0,
        "excludedIdentity": 0,
        "excludedInvalid": 0,
        "excludedSportingCutoff": 0,
        "excludedProxyRule": 0,
        "exported": 0,
    }
    for row in rows:
        counts["raw"] += 1
        if counts["raw"] > MAX_SOURCE_ROWS:
            raise ValueError("source rows exceed the 501-row sentinel bound")
        if counts["raw"] == MAX_SOURCE_ROWS:
            raise ValueError("501-row sentinel: source query exceeded the 500-row export limit")
        if not isinstance(row, Mapping):
            raise TypeError("source lap row must be a mapping")
        raw = _project_raw_row(row)
        key = _dedupe_key(raw, source_alias)
        content_bytes = canonical_json_bytes(raw)
        previous = seen.get(key)
        if previous is not None:
            if previous != content_bytes:
                counts["conflict"] += 1
                raise ValueError("conflicting source rows share the same lap identity")
            counts["duplicateMerged"] += 1
            continue
        seen[key] = content_bytes
        raw_rows.append(raw)
        if not _matches_identity(raw, mongo_meeting_key, set(session_keys), set(driver_numbers)):
            counts["excludedIdentity"] += 1
            continue
        try:
            event_time = parse_utc(raw.get("date_start"), field="date_start")
            duration = _finite_positive(raw.get("lap_duration"), "lap_duration")
            exact_lap_end = event_time + timedelta(seconds=duration)
        except (TypeError, ValueError, OverflowError):
            counts["excludedInvalid"] += 1
            continue
        if event_time > cutoff or exact_lap_end > cutoff:
            counts["excludedSportingCutoff"] += 1
            continue
        try:
            normalized_row = _normalize_lap(
                raw,
                meeting_key=meeting_key,
                observed=observed,
                source_alias=source_alias,
            )
        except (TypeError, ValueError, OverflowError):
            counts["excludedInvalid"] += 1
            continue
        if not normalized_row["isClean"]:
            counts["excludedProxyRule"] += 1
            continue
        normalized.append(normalized_row)

    if counts["conflict"]:
        raise ValueError("source duplicate conflict prevents package creation")
    if len(raw_rows) > MAX_LAPS:
        raise ValueError("source rows exceed the supported 500-row bound")
    raw_rows.sort(key=lambda item: _dedupe_key(item, source_alias))
    normalized.sort(key=lambda item: (item["sessionKey"], item["driverNumber"], item["eventTime"], item["recordId"]))
    counts["exported"] = len(normalized)
    return ExportResult(tuple(raw_rows), tuple(normalized), counts)


def _normalize_lap(
    row: Mapping[str, Any],
    *,
    meeting_key: int,
    observed: datetime,
    source_alias: str,
) -> dict[str, Any]:
    event_time = parse_utc(row.get("date_start"), field="date_start")
    duration = _finite_positive(row.get("lap_duration"), "lap_duration")
    lap_end = event_time + timedelta(seconds=duration)
    seconds = tuple(
        _finite_positive(row.get(field), field)
        for field in ("duration_sector_1", "duration_sector_2", "duration_sector_3")
    )
    is_out_lap = row.get("is_pit_out_lap")
    if not isinstance(is_out_lap, bool):
        raise TypeError("is_pit_out_lap must be boolean")
    is_clean = not is_out_lap and abs(sum(seconds) - duration) <= 0.01
    key = row.get("_key")
    if key is None or not str(key).strip():
        raise ValueError("source lap _key is required")
    return {
        "recordId": str(key),
        "meetingKey": meeting_key,
        "sessionKey": _required_positive_int(row.get("session_key"), "session_key"),
        "driverNumber": _required_positive_int(row.get("driver_number"), "driver_number"),
        "eventTime": format_utc(event_time),
        "firstSeenAt": format_utc(observed),
        "lapEnd": format_utc(lap_end),
        "durationSeconds": duration,
        "isClean": is_clean,
        "sourceEndpoint": source_alias,
        "sourceContentHash": canonical_sha256(dict(row)),
    }


def _project_raw_row(row: Mapping[str, Any]) -> dict[str, Any]:
    """仅保留白名单字段，并转换 BSON 日期/键为稳定 JSON 值。"""
    projected: dict[str, Any] = {}
    for field in RAW_FIELDS:
        if field not in row:
            continue
        value = row[field]
        if field == "date_start" and value is not None:
            parsed_time = parse_utc(value, field=field)
            if parsed_time.microsecond % 1000:
                raise ValueError("date_start must have millisecond precision")
            if isinstance(value, str):
                fraction = re.search(r"[.,](\d+)", value)
                if fraction and any(digit != "0" for digit in fraction.group(1)[3:]):
                    raise ValueError("date_start must have millisecond precision")
            projected[field] = format_utc(parsed_time)
        elif field == "_key" and value is not None:
            projected[field] = str(value)
        elif field == "source_metadata":
            if not isinstance(value, Mapping):
                raise ValueError("source_metadata must be a mapping")
            if not value.keys() <= SOURCE_METADATA_FIELDS:
                raise ValueError("source_metadata contains unsupported fields")
            # 来源元数据仅接受限长安全标识，避免凭据、URI 或内网地址进入导出包。
            safe_metadata: dict[str, str] = {}
            for key, pattern in (
                ("sourceAlias", SOURCE_METADATA_ALIAS_PATTERN),
                ("sourceRecordId", SOURCE_METADATA_RECORD_ID_PATTERN),
            ):
                if key not in value:
                    continue
                item = value[key]
                if not isinstance(item, str) or pattern.fullmatch(item) is None:
                    raise ValueError(f"source_metadata {key} has an unsafe format")
                try:
                    ip_address(item)
                except ValueError:
                    safe_metadata[key] = item
                else:
                    raise ValueError(f"source_metadata {key} must not contain an IP address")
            if "sourceContentHash" in value:
                content_hash = value["sourceContentHash"]
                if (
                    not isinstance(content_hash, str)
                    or SOURCE_METADATA_HASH_PATTERN.fullmatch(content_hash) is None
                ):
                    raise ValueError("source_metadata sourceContentHash must be a SHA-256 hex digest")
                safe_metadata["sourceContentHash"] = content_hash.lower()
            projected[field] = safe_metadata
        else:
            projected[field] = value
    return projected


def _dedupe_key(row: Mapping[str, Any], source_alias: str) -> tuple[str, int, int, int, str]:
    key = row.get("_key")
    if key is None or not str(key).strip():
        raise ValueError("source lap _key is required")
    return (
        source_alias,
        _required_positive_int(row.get("session_key"), "session_key"),
        _required_positive_int(row.get("driver_number"), "driver_number"),
        _required_positive_int(row.get("lap_number"), "lap_number"),
        str(key),
    )


def _matches_identity(
    row: Mapping[str, Any], mongo_meeting_key: int, sessions: set[int], drivers: set[int]
) -> bool:
    return (
        row.get("meeting_key") == mongo_meeting_key
        and row.get("session_key") in sessions
        and row.get("driver_number") in drivers
    )


def _required_positive_int(value: Any, field: str) -> int:
    if not _positive_int(value):
        raise ValueError(f"{field} must be a positive integer")
    return value


def _positive_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _finite_positive(value: Any, field: str) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise TypeError(f"{field} must be numeric")
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise ValueError(f"{field} must be finite and positive")
    return number
