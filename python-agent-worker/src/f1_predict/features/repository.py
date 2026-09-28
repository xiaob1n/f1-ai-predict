"""OpenF1 圈速特征的只读存储边界与不可变测试实现。"""

from __future__ import annotations

import json
import math
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, datetime
from itertools import islice
from pathlib import Path
from typing import Protocol

MAX_SESSION_KEYS = 50
MAX_DRIVER_NUMBERS = 30
MAX_LAP_RECORDS = 500
MAX_FIXTURE_BYTES = 5_000_000
MAX_FIXTURE_RECORDS = 20_000
_LAP_RECORD_FIELDS = frozenset(
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


@dataclass(frozen=True, slots=True)
class LapRecord:
    """带可见时间和来源证据的圈速记录。"""

    record_id: str
    meeting_key: int
    session_key: int
    driver_number: int
    event_time: datetime | None
    first_seen_at: datetime | None
    lap_end: datetime | None
    duration_seconds: float | None
    is_clean: bool
    source_endpoint: str
    source_content_hash: str


class LapRepository(Protocol):
    """仅按显式赛事、Session、车手和截止时间读取圈速。"""

    def read_laps(
        self,
        *,
        meeting_key: int,
        session_keys: tuple[int, ...],
        driver_numbers: tuple[int, ...],
        data_cutoff: datetime,
        limit: int,
    ) -> tuple[LapRecord, ...]:
        """读取最多 limit 条候选记录；实现必须在存储查询侧应用限制。"""


@dataclass(frozen=True, slots=True)
class InMemoryLapRepository:
    """不可变内存仓库，供特征测试与离线夹具使用。"""

    records: tuple[LapRecord, ...]

    def __init__(self, records: tuple[LapRecord, ...] | list[LapRecord]) -> None:
        object.__setattr__(self, "records", tuple(records))

    def read_laps(
        self,
        *,
        meeting_key: int,
        session_keys: tuple[int, ...],
        driver_numbers: tuple[int, ...],
        data_cutoff: datetime,
        limit: int,
    ) -> tuple[LapRecord, ...]:
        """按筛选条件返回至多 limit+1 条，供调用端识别越界结果。"""
        if limit < 1 or limit > MAX_LAP_RECORDS:
            raise ValueError("lap result limit is outside the supported bounds")
        if _as_utc(data_cutoff) is None:
            raise ValueError("data_cutoff must be a timezone-aware datetime")
        cutoff = _as_utc(data_cutoff)
        assert cutoff is not None
        session_set = set(session_keys)
        driver_set = set(driver_numbers)
        matches = (
            record
            for record in self.records
            if _matches(record, meeting_key, session_set, driver_set, cutoff)
        )
        return tuple(_take(matches, limit + 1))


@dataclass(frozen=True, slots=True)
class JsonLapRepository:
    """从本地只读 JSON 夹具读取圈速记录。"""

    records: tuple[LapRecord, ...]

    @classmethod
    def from_json_file(cls, path: str | Path) -> JsonLapRepository:
        """加载有字节数和记录数上限的可信本地夹具。"""
        fixture_path = Path(path)
        try:
            with fixture_path.open("rb") as fixture:
                raw = fixture.read(MAX_FIXTURE_BYTES + 1)
        except OSError as error:
            raise ValueError("lap fixture cannot be read") from error
        if len(raw) > MAX_FIXTURE_BYTES:
            raise ValueError("lap fixture exceeds the supported size")
        try:
            parsed = json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError("lap fixture is not valid JSON") from error
        if not isinstance(parsed, list) or len(parsed) > MAX_FIXTURE_RECORDS:
            raise ValueError("lap fixture must be a bounded JSON array")
        records = tuple(_parse_lap_record(item) for item in parsed)
        return cls(records=records)

    def read_laps(
        self,
        *,
        meeting_key: int,
        session_keys: tuple[int, ...],
        driver_numbers: tuple[int, ...],
        data_cutoff: datetime,
        limit: int,
    ) -> tuple[LapRecord, ...]:
        """按会议、Session、车手和 as-of 截止时间只读筛选夹具。"""
        return InMemoryLapRepository(self.records).read_laps(
            meeting_key=meeting_key,
            session_keys=session_keys,
            driver_numbers=driver_numbers,
            data_cutoff=data_cutoff,
            limit=limit,
        )


def _parse_lap_record(value: object) -> LapRecord:
    """验证夹具记录字段及必需的首次可见时间。"""
    if not isinstance(value, dict) or set(value) != _LAP_RECORD_FIELDS:
        raise ValueError("lap fixture record has missing or unknown fields")
    record_id = value["recordId"]
    meeting_key = value["meetingKey"]
    session_key = value["sessionKey"]
    driver_number = value["driverNumber"]
    duration = value["durationSeconds"]
    is_clean = value["isClean"]
    endpoint = value["sourceEndpoint"]
    content_hash = value["sourceContentHash"]
    if not isinstance(record_id, str) or not record_id.strip():
        raise ValueError("lap fixture recordId must not be empty")
    if any(
        not isinstance(item, int) or isinstance(item, bool) or item <= 0
        for item in (meeting_key, session_key, driver_number)
    ):
        raise ValueError("lap fixture keys and driver number must be positive integers")
    if not isinstance(duration, (int, float)) or isinstance(duration, bool):
        raise TypeError("lap fixture durationSeconds must be numeric")
    if not math.isfinite(duration):
        raise ValueError("lap fixture durationSeconds must be finite")
    if not isinstance(is_clean, bool):
        raise TypeError("lap fixture isClean must be boolean")
    if not isinstance(endpoint, str) or not endpoint.strip():
        raise ValueError("lap fixture sourceEndpoint must not be empty")
    if not isinstance(content_hash, str) or not content_hash.strip():
        raise ValueError("lap fixture sourceContentHash must not be empty")
    return LapRecord(
        record_id=record_id,
        meeting_key=meeting_key,
        session_key=session_key,
        driver_number=driver_number,
        event_time=_parse_timestamp(value["eventTime"], "eventTime"),
        first_seen_at=_parse_timestamp(value["firstSeenAt"], "firstSeenAt"),
        lap_end=_parse_timestamp(value["lapEnd"], "lapEnd"),
        duration_seconds=float(duration),
        is_clean=is_clean,
        source_endpoint=endpoint,
        source_content_hash=content_hash,
    )


def _parse_timestamp(value: object, field: str) -> datetime:
    """解析必须带时区的 UTC / ISO 时间戳。"""
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"lap fixture {field} is required")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as error:
        raise ValueError(f"lap fixture {field} is invalid") from error
    normalized = _as_utc(parsed)
    if normalized is None:
        raise ValueError(f"lap fixture {field} must include a timezone")
    return normalized


def _matches(
    record: LapRecord,
    meeting_key: int,
    session_keys: set[int],
    driver_numbers: set[int],
    cutoff: datetime,
) -> bool:
    """检查记录归属及截止时点。"""
    return (
        isinstance(record, LapRecord)
        and isinstance(record.meeting_key, int)
        and not isinstance(record.meeting_key, bool)
        and record.meeting_key == meeting_key
        and isinstance(record.session_key, int)
        and not isinstance(record.session_key, bool)
        and record.session_key in session_keys
        and isinstance(record.driver_number, int)
        and not isinstance(record.driver_number, bool)
        and record.driver_number in driver_numbers
        and _is_as_of(record, cutoff)
    )


def _is_as_of(record: LapRecord, cutoff: datetime) -> bool:
    """内存适配器查询侧同样排除不可证实的截止前记录。"""
    if not isinstance(record, LapRecord):
        return False
    event_time = _as_utc(record.event_time)
    first_seen_at = _as_utc(record.first_seen_at)
    lap_end = _as_utc(record.lap_end)
    return (
        event_time is not None
        and first_seen_at is not None
        and lap_end is not None
        and event_time <= cutoff
        and first_seen_at <= cutoff
        and lap_end <= cutoff
    )


def _as_utc(value: datetime | None) -> datetime | None:
    """将带时区时间规范化为 UTC，拒绝缺失或错误类型。"""
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        return None
    return value.astimezone(UTC)


def _take(records: Iterable[LapRecord], count: int) -> list[LapRecord]:
    """从候选迭代器至多读取 count 项。"""
    return list(islice(records, count))
