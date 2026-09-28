"""防止未来数据泄漏的确定性圈速特征构建。"""

from __future__ import annotations

import hashlib
import json
import math
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from statistics import median

from f1_predict.features.repository import (
    MAX_DRIVER_NUMBERS,
    MAX_LAP_RECORDS,
    MAX_SESSION_KEYS,
    LapRecord,
    LapRepository,
)


class FeatureStatus(StrEnum):
    """特征快照状态。"""

    READY = "READY"
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"


@dataclass(frozen=True, slots=True)
class FeatureQuery:
    """特征构建允许的有限查询参数。"""

    meeting_key: int
    session_keys: tuple[int, ...]
    driver_numbers: tuple[int, ...]
    data_cutoff: datetime
    feature_version: str
    prediction_job_id: str | None = None
    max_records: int = MAX_LAP_RECORDS
    min_clean_laps_per_driver: int = 1

    def __post_init__(self) -> None:
        if not isinstance(self.meeting_key, int) or isinstance(self.meeting_key, bool) or self.meeting_key <= 0:
            raise ValueError("meeting_key must be positive")
        if not isinstance(self.session_keys, tuple):
            raise TypeError("session_keys must be an immutable tuple")
        if not self.session_keys or len(self.session_keys) > MAX_SESSION_KEYS:
            raise ValueError("session key count is outside the supported bounds")
        if any(
            not isinstance(key, int) or isinstance(key, bool) or key <= 0
            for key in self.session_keys
        ) or len(set(self.session_keys)) != len(self.session_keys):
            raise ValueError("session keys must be unique positive integers")
        if not isinstance(self.driver_numbers, tuple):
            raise TypeError("driver_numbers must be an immutable tuple")
        if not self.driver_numbers or len(self.driver_numbers) > MAX_DRIVER_NUMBERS:
            raise ValueError("driver number count is outside the supported bounds")
        if any(
            not isinstance(number, int)
            or isinstance(number, bool)
            or number <= 0
            for number in self.driver_numbers
        ) or len(set(self.driver_numbers)) != len(self.driver_numbers):
            raise ValueError("driver numbers must be unique positive integers")
        if _utc(self.data_cutoff) is None:
            raise ValueError("data_cutoff must be a timezone-aware datetime")
        if not isinstance(self.feature_version, str) or not self.feature_version.strip():
            raise ValueError("feature_version must not be empty")
        if self.prediction_job_id is not None and (
            not isinstance(self.prediction_job_id, str)
            or not self.prediction_job_id.strip()
        ):
            raise ValueError("prediction_job_id must not be empty")
        if not isinstance(self.max_records, int) or isinstance(self.max_records, bool):
            raise TypeError("max_records must be an integer")
        if not 1 <= self.max_records <= MAX_LAP_RECORDS:
            raise ValueError("max_records is outside the supported bounds")
        if not 1 <= self.min_clean_laps_per_driver <= self.max_records:
            raise ValueError("minimum clean laps is outside the supported bounds")


@dataclass(frozen=True, slots=True)
class DriverFeature:
    """单个车手的干净圈统计。"""

    driver_number: int
    clean_lap_count: int
    median_lap_seconds: float


@dataclass(frozen=True, slots=True)
class SourceEvidence:
    """特征引用的原始来源记录。"""

    record_id: str
    source_endpoint: str
    event_time: datetime
    first_seen_at: datetime
    lap_end: datetime
    source_content_hash: str


@dataclass(frozen=True, slots=True)
class FeatureSnapshot:
    """不可变的特征结果及可持久化 JSON。"""

    status: FeatureStatus
    reason: str | None
    feature_version: str
    data_cutoff: datetime
    drivers: tuple[DriverFeature, ...]
    evidence: tuple[SourceEvidence, ...]
    snapshot_hash: str
    frozen_json: str


@dataclass(frozen=True, slots=True)
class FeatureBuilder:
    """通过只读仓库构建不可变预测特征。"""

    repository: LapRepository

    def build(
        self,
        meeting_key: int,
        session_keys: Sequence[int],
        driver_numbers: Sequence[int],
        data_cutoff: datetime,
        feature_version: str,
        prediction_job_id: str | None = None,
        *,
        max_records: int = MAX_LAP_RECORDS,
        min_clean_laps_per_driver: int = 1,
    ) -> FeatureSnapshot:
        """按受限范围读取数据并构建冻结特征快照。"""
        if len(session_keys) > MAX_SESSION_KEYS:
            raise ValueError("session key count is outside the supported bounds")
        if len(driver_numbers) > MAX_DRIVER_NUMBERS:
            raise ValueError("driver number count is outside the supported bounds")
        query = FeatureQuery(
            meeting_key=meeting_key,
            session_keys=tuple(session_keys),
            driver_numbers=tuple(driver_numbers),
            data_cutoff=data_cutoff,
            feature_version=feature_version,
            prediction_job_id=prediction_job_id,
            max_records=max_records,
            min_clean_laps_per_driver=min_clean_laps_per_driver,
        )
        return build_features(query, self.repository)


def build_features(query: FeatureQuery, repository: LapRepository) -> FeatureSnapshot:
    """读取有限候选圈速，校验双重可见时点并生成冻结统计。"""
    records = repository.read_laps(
        meeting_key=query.meeting_key,
        session_keys=query.session_keys,
        driver_numbers=query.driver_numbers,
        data_cutoff=query.data_cutoff,
        limit=query.max_records,
    )
    cutoff = _utc(query.data_cutoff)
    assert cutoff is not None
    if len(records) > query.max_records:
        return _snapshot(query, FeatureStatus.INSUFFICIENT_DATA, "REPOSITORY_RESULT_LIMIT_EXCEEDED")

    unique_records: dict[str, LapRecord] = {}
    duplicate_ids: set[str] = set()
    for record in records:
        if not isinstance(record, LapRecord):
            continue
        if not isinstance(record.record_id, str) or not record.record_id:
            continue
        if record.record_id in unique_records:
            duplicate_ids.add(record.record_id)
        else:
            unique_records[record.record_id] = record
    for duplicate_id in duplicate_ids:
        unique_records.pop(duplicate_id, None)

    eligible = tuple(
        sorted(
            (
                record
                for record in unique_records.values()
                if _is_eligible(record, query, cutoff)
            ),
            key=lambda record: (record.driver_number, record.session_key, record.record_id),
        )
    )
    grouped: dict[int, list[float]] = {number: [] for number in query.driver_numbers}
    for record in eligible:
        assert record.duration_seconds is not None
        grouped[record.driver_number].append(record.duration_seconds)

    if any(
        len(grouped[number]) < query.min_clean_laps_per_driver
        for number in query.driver_numbers
    ):
        return _snapshot(query, FeatureStatus.INSUFFICIENT_DATA, "MINIMUM_CLEAN_LAPS_NOT_MET")

    driver_features = tuple(
        DriverFeature(
            driver_number=number,
            clean_lap_count=len(grouped[number]),
            median_lap_seconds=median(grouped[number]),
        )
        for number in sorted(query.driver_numbers)
    )
    evidence = tuple(
        SourceEvidence(
            record_id=record.record_id,
            source_endpoint=record.source_endpoint,
            event_time=_utc(record.event_time),  # type: ignore[arg-type]
            first_seen_at=_utc(record.first_seen_at),  # type: ignore[arg-type]
            lap_end=_utc(record.lap_end),  # type: ignore[arg-type]
            source_content_hash=record.source_content_hash,
        )
        for record in eligible
    )
    body = _payload(query, FeatureStatus.READY, None, driver_features, evidence)
    frozen_json = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    snapshot_hash = hashlib.sha256(frozen_json.encode("utf-8")).hexdigest()
    return FeatureSnapshot(
        status=FeatureStatus.READY,
        reason=None,
        feature_version=query.feature_version,
        data_cutoff=cutoff,
        drivers=driver_features,
        evidence=evidence,
        snapshot_hash=snapshot_hash,
        frozen_json=frozen_json,
    )


def _is_eligible(record: LapRecord, query: FeatureQuery, cutoff: datetime) -> bool:
    if (
        not record.record_id
        or record.meeting_key != query.meeting_key
        or not isinstance(record.meeting_key, int)
        or isinstance(record.meeting_key, bool)
        or record.session_key not in query.session_keys
        or not isinstance(record.session_key, int)
        or isinstance(record.session_key, bool)
        or record.driver_number not in query.driver_numbers
        or not isinstance(record.driver_number, int)
        or isinstance(record.driver_number, bool)
        or record.is_clean is not True
        or not isinstance(record.source_endpoint, str)
        or not record.source_endpoint.strip()
        or not isinstance(record.source_content_hash, str)
        or not record.source_content_hash.strip()
    ):
        return False
    event_time = _utc(record.event_time)
    first_seen_at = _utc(record.first_seen_at)
    lap_end = _utc(record.lap_end)
    duration = record.duration_seconds
    if event_time is None or first_seen_at is None or lap_end is None:
        return False
    if (
        isinstance(duration, bool)
        or not isinstance(duration, (int, float))
        or not math.isfinite(duration)
        or duration <= 0
    ):
        return False
    # 同时核对声明的结束时间与按圈速推算的结束时间，取更保守的时点。
    try:
        computed_end = event_time + timedelta(seconds=duration)
    except OverflowError:
        return False
    return (
        event_time <= cutoff
        and first_seen_at <= cutoff
        and lap_end >= event_time
        and lap_end <= cutoff
        and computed_end <= cutoff
        and (computed_end - lap_end).total_seconds() <= 0.001
    )


def _utc(value: datetime | None) -> datetime | None:
    if (
        not isinstance(value, datetime)
        or value.tzinfo is None
        or value.utcoffset() is None
    ):
        return None
    return value.astimezone(UTC)


def _snapshot(
    query: FeatureQuery,
    status: FeatureStatus,
    reason: str,
) -> FeatureSnapshot:
    cutoff = _utc(query.data_cutoff)
    assert cutoff is not None
    payload = _payload(query, status, reason, (), ())
    frozen_json = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return FeatureSnapshot(
        status=status,
        reason=reason,
        feature_version=query.feature_version,
        data_cutoff=cutoff,
        drivers=(),
        evidence=(),
        snapshot_hash=hashlib.sha256(frozen_json.encode("utf-8")).hexdigest(),
        frozen_json=frozen_json,
    )


def _payload(
    query: FeatureQuery,
    status: FeatureStatus,
    reason: str | None,
    drivers: tuple[DriverFeature, ...],
    evidence: tuple[SourceEvidence, ...],
) -> dict[str, object]:
    cutoff = _utc(query.data_cutoff)
    assert cutoff is not None
    return {
        "predictionJobId": query.prediction_job_id,
        "featureVersion": query.feature_version,
        "dataCutoff": _serialize_time(cutoff),
        "meetingKey": query.meeting_key,
        "sessionKeys": sorted(query.session_keys),
        "status": status.value,
        "reason": reason,
        "drivers": [
            {
                "driverNumber": item.driver_number,
                "cleanLapCount": item.clean_lap_count,
                "medianLapSeconds": item.median_lap_seconds,
            }
            for item in drivers
        ],
        "sourceEvidence": [
            {
                "recordId": item.record_id,
                "sourceEndpoint": item.source_endpoint,
                "eventTime": _serialize_time(item.event_time),
                "firstSeenAt": _serialize_time(item.first_seen_at),
                "lapEnd": _serialize_time(item.lap_end),
                "sourceContentHash": item.source_content_hash,
            }
            for item in evidence
        ],
    }


def _serialize_time(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")
