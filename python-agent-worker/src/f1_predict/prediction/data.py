"""仅离线已审计的不可变圈速夹具可作为当前运行期数据源。"""

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from f1_predict.features.repository import InMemoryLapRepository, LapRecord


def load_lap_fixture(path: str) -> InMemoryLapRepository:
    """本地 JSON 必须显式带首次可见、事件、圈结束时间和内容哈希。"""
    if not path:
        raise ValueError("an audited lap fixture path is required")
    rows = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(rows, list) or len(rows) > 500:
        raise ValueError("lap fixture exceeds the supported bounds")
    records: list[LapRecord] = []
    for row in rows:
        if not isinstance(row, dict):
            raise TypeError("invalid lap fixture row")
        required = {
            "recordId", "meetingKey", "sessionKey", "driverNumber", "eventTime",
            "firstSeenAt", "lapEnd", "durationSeconds", "isClean", "sourceEndpoint",
            "sourceContentHash",
        }
        if set(row) != required:
            raise ValueError("lap fixture metadata is incomplete")
        if not all(isinstance(row[key], str) and row[key] for key in
                   ("recordId", "eventTime", "firstSeenAt", "lapEnd", "sourceEndpoint", "sourceContentHash")):
            raise ValueError("lap fixture metadata is incomplete")
        records.append(LapRecord(
            record_id=row["recordId"],
            meeting_key=int(row["meetingKey"]),
            session_key=int(row["sessionKey"]),
            driver_number=int(row["driverNumber"]),
            event_time=datetime.fromisoformat(row["eventTime"]),
            first_seen_at=datetime.fromisoformat(row["firstSeenAt"]),
            lap_end=datetime.fromisoformat(row["lapEnd"]),
            duration_seconds=float(row["durationSeconds"]),
            is_clean=row["isClean"] is True,
            source_endpoint=row["sourceEndpoint"],
            source_content_hash=row["sourceContentHash"],
        ))
    return InMemoryLapRepository(records)
