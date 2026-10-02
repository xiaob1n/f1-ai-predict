"""真实源的注入式、受限只读回放适配器。"""

from __future__ import annotations

import json
import math
import re
from collections.abc import Iterable, Mapping
from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Any

from f1_predict.replay.export import SOURCE_QUERY_TIMEOUT_MS, _project_raw_row

MYSQL_CAPTURE_QUERY = """
SELECT
    q.id AS question_id,
    q.round_id AS question_round_id,
    q.gameday_id AS gameday_id,
    q.source_question_id AS source_question_id,
    q.question_no AS question_no,
    q.status AS question_status,
    q.latest_snapshot_id AS latest_snapshot_id,
    q.first_seen_at AS first_seen_at,
    s.id AS snapshot_id,
    s.question_id AS snapshot_question_id,
    s.snapshot_no AS snapshot_no,
    s.content_hash AS content_hash,
    s.created_at AS snapshot_created_at,
    JSON_UNQUOTE(JSON_EXTRACT(s.raw_json, '$.Text')) AS raw_text,
    JSON_UNQUOTE(JSON_EXTRACT(s.raw_json, '$.SubText')) AS raw_sub_text,
    JSON_EXTRACT(s.raw_json, '$.OptionTemplateId') AS raw_option_template_id,
    JSON_EXTRACT(s.raw_json, '$.Config.ChoiceLimit') AS raw_choice_limit,
    r.id AS round_id,
    r.season_id AS round_season_id,
    r.round_number AS round_number,
    r.grand_prix_name AS grand_prix_name,
    r.circuit_name AS circuit_name,
    r.country AS country,
    r.locality AS locality,
    r.start_date AS round_start_date,
    r.end_date AS round_end_date,
    r.status AS round_status,
    se.id AS season_id,
    se.year AS season_year,
    se.name AS season_name,
    se.status AS season_status
FROM question AS q
JOIN question_snapshot AS s ON s.id = q.latest_snapshot_id
JOIN `round` AS r ON r.id = q.round_id
JOIN season AS se ON se.id = r.season_id
WHERE q.id = %s
  AND s.id = %s
  AND q.latest_snapshot_id = s.id
  AND s.question_id = q.id
  AND s.snapshot_no = (
      SELECT MAX(latest_s.snapshot_no)
      FROM question_snapshot AS latest_s
      WHERE latest_s.question_id = q.id
  )
LIMIT 2
""".strip()

MYSQL_SESSIONS_QUERY = """
SELECT
    meeting_key,
    session_key,
    session_name,
    session_type,
    gameday_id,
    start_date_utc,
    end_date_utc,
    status
FROM meeting_session
WHERE round_id = %s
ORDER BY session_key
LIMIT 65
""".strip()

MYSQL_OPTIONS_QUERY = """
SELECT option_id, option_no, option_text, points, chance
FROM question_option
WHERE snapshot_id = %s
ORDER BY option_no
LIMIT 3
""".strip()

MYSQL_READ_ONLY_CHECK = """
SELECT @@transaction_read_only AS transaction_read_only,
       @@session.time_zone AS session_time_zone,
       CURRENT_ROLE() AS current_role,
       DATABASE() AS active_database
""".strip()
MYSQL_SHOW_GRANTS = "SHOW GRANTS"
_READ_ONLY_TABLES = frozenset(
    {"season", "round", "meeting_session", "question", "question_snapshot", "question_option"}
)

_MAX_SESSIONS = 64
_CAPTURE_COLUMNS = frozenset(
    {
        "question_id", "question_round_id", "gameday_id", "source_question_id", "question_no",
        "question_status", "latest_snapshot_id", "first_seen_at", "snapshot_id",
        "snapshot_question_id", "snapshot_no", "content_hash", "snapshot_created_at", "raw_text",
        "raw_sub_text", "raw_option_template_id", "raw_choice_limit", "round_id", "round_season_id",
        "round_number", "grand_prix_name", "circuit_name", "country", "locality", "round_start_date",
        "round_end_date", "round_status", "season_id", "season_year", "season_name", "season_status",
    }
)
_SESSION_COLUMNS = frozenset(
    {"meeting_key", "session_key", "session_name", "session_type", "gameday_id", "start_date_utc", "end_date_utc", "status"}
)
_OPTION_COLUMNS = frozenset({"option_id", "option_no", "option_text", "points", "chance"})


class ReplaySourceError(RuntimeError):
    """安全、无连接细节的回放源错误。"""


class MongoLapSource:
    """将注入的 PyMongo collection/database 限制为固定 laps 只读查询。"""

    def __init__(self, collection_or_database: Any) -> None:
        # 仅保存调用方创建的对象；构造时不触发驱动操作。
        self._source = collection_or_database

    def find(
        self,
        collection: str,
        query: Mapping[str, Any],
        *,
        projection: Mapping[str, int],
        max_time_ms: int,
        limit: int,
    ) -> Iterable[Mapping[str, Any]]:
        """只允许 export 模块固定的投影、范围与 501 哨兵查询。"""
        if (
            collection != "laps"
            or not _valid_lap_query(query)
            or dict(projection) != _expected_lap_projection()
            or max_time_ms != SOURCE_QUERY_TIMEOUT_MS
            or limit != 501
        ):
            raise ReplaySourceError("Mongo replay query is outside the approved read scope")

        try:
            target = self._source.get_collection("laps") if _looks_like_database(self._source) else self._source
            cursor = target.find(
                dict(query),
                dict(projection),
                max_time_ms=max_time_ms,
            ).limit(limit)
        except Exception:  # noqa: BLE001 - 屏蔽驱动异常中的连接信息。
            raise ReplaySourceError("Mongo replay query could not be started") from None

        def iter_cursor() -> Iterable[Mapping[str, Any]]:
            try:
                for row in cursor:
                    if not isinstance(row, Mapping):
                        raise ReplaySourceError("Mongo replay returned an invalid row")
                    yield _project_raw_row(row)
            except ReplaySourceError:
                raise
            except Exception:  # noqa: BLE001 - 屏蔽驱动异常中的连接信息。
                raise ReplaySourceError("Mongo replay query failed") from None
            finally:
                try:
                    cursor.close()
                except Exception:  # noqa: BLE001 - 游标清理失败不能报告成功读取。
                    raise ReplaySourceError("Mongo replay cursor cleanup failed safely") from None

        return iter_cursor()


class MySqlReplaySource:
    """使用注入的 DB-API 连接读取单题副本，不创建连接或驱动。"""

    def __init__(self, connection: Any) -> None:
        # 连接的取得、凭据与端点管理留给独立审核的运行时。
        self._connection = connection

    def read_capture(
        self,
        question_id: int,
        snapshot_id: int,
        option_ids: tuple[int, int],
    ) -> dict[str, Any]:
        """在服务器及事务均确认只读后，按固定 SQL 返回单题有限载荷。"""
        if not _positive_int(question_id) or not _positive_int(snapshot_id):
            raise ValueError("question and snapshot IDs must be positive integers")
        if (
            not isinstance(option_ids, tuple)
            or len(option_ids) != 2
            or not all(_positive_int(option_id) for option_id in option_ids)
            or option_ids[0] == option_ids[1]
        ):
            raise ValueError("exactly two unique positive option IDs are required")

        cursor = None
        try:
            cursor = self._connection.cursor()
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute(MYSQL_READ_ONLY_CHECK)
            flags = cursor.fetchone()
            if not _read_only_context_verified(flags):
                raise ReplaySourceError("MySQL connection is not verified transaction-read-only UTC")
            cursor.execute(MYSQL_SHOW_GRANTS)
            if not _select_only_grants_verified(cursor.fetchmany(33)):
                raise ReplaySourceError("MySQL account is not verified as source-table SELECT-only")

            cursor.execute(MYSQL_CAPTURE_QUERY, (question_id, snapshot_id))
            capture_rows = _fetch_mappings(cursor, limit=2, expected_columns=_CAPTURE_COLUMNS)
            if len(capture_rows) != 1:
                raise ReplaySourceError("question or latest snapshot was not found uniquely")
            row = capture_rows[0]
            cursor.execute(MYSQL_SESSIONS_QUERY, (row["round_id"],))
            session_rows = _fetch_mappings(cursor, limit=_MAX_SESSIONS + 1, expected_columns=_SESSION_COLUMNS)
            if not session_rows or len(session_rows) > _MAX_SESSIONS:
                raise ReplaySourceError("round session metadata is missing or exceeds its bound")
            cursor.execute(MYSQL_OPTIONS_QUERY, (snapshot_id,))
            option_rows = _fetch_mappings(cursor, limit=3, expected_columns=_OPTION_COLUMNS)

            result = _build_capture(row, session_rows, option_rows, option_ids)
            return _to_json_safe(result)
        except ReplaySourceError:
            raise
        except Exception:  # noqa: BLE001 - 屏蔽 DB-API 异常中的 SQL 与连接细节。
            raise ReplaySourceError("MySQL replay capture failed safely") from None
        finally:
            cleanup_failed = False
            try:
                self._connection.rollback()
            except Exception:  # noqa: BLE001 - 清理失败不得泄露连接信息。
                cleanup_failed = True
            if cursor is not None:
                try:
                    cursor.close()
                except Exception:  # noqa: BLE001 - 清理失败不得覆盖为成功结果。
                    cleanup_failed = True
            if cleanup_failed:
                raise ReplaySourceError("MySQL replay cleanup failed safely")


def _build_capture(
    row: Mapping[str, Any],
    sessions: list[dict[str, Any]],
    options: list[dict[str, Any]],
    requested_option_ids: tuple[int, int],
) -> dict[str, Any]:
    question_id = _positive_db_int(row.get("question_id"), "question id")
    snapshot_id = _positive_db_int(row.get("snapshot_id"), "snapshot id")
    if (
        row.get("latest_snapshot_id") != snapshot_id
        or row.get("snapshot_question_id") != question_id
        or row.get("question_round_id") != row.get("round_id")
        or row.get("round_season_id") != row.get("season_id")
    ):
        raise ReplaySourceError("question, snapshot, round or season ownership is inconsistent")
    if row.get("raw_text") is None or not isinstance(row.get("raw_text"), str) or not row["raw_text"].strip():
        raise ReplaySourceError("snapshot question text is missing")
    choice_limit = _sql_json_scalar(row.get("raw_choice_limit"))
    if not _positive_db_int(choice_limit, "choice limit") == 1:
        raise ReplaySourceError("snapshot is not a single-choice question")
    if not isinstance(row.get("grand_prix_name"), str) or not row["grand_prix_name"].strip():
        raise ReplaySourceError("round name is missing")
    if not isinstance(row.get("season_name"), str) or not row["season_name"].strip():
        raise ReplaySourceError("season name is missing")

    normalized_sessions = []
    seen_session_keys: set[int] = set()
    for session in sessions:
        key = _positive_db_int(session.get("session_key"), "session key")
        name = session.get("session_name")
        if key in seen_session_keys or not isinstance(name, str) or not name.strip():
            raise ReplaySourceError("session metadata is invalid")
        seen_session_keys.add(key)
        normalized_sessions.append(
            {
                "meetingKey": session.get("meeting_key"),
                "sessionKey": key,
                "sessionName": name,
                "sessionType": session.get("session_type"),
                "gamedayId": session.get("gameday_id"),
                "startDateUtc": _utc_timestamp(session.get("start_date_utc")),
                "endDateUtc": _utc_timestamp(session.get("end_date_utc")),
                "status": session.get("status"),
            }
        )

    if len(options) != 2:
        raise ReplaySourceError("snapshot must contain exactly two options")
    by_option_id: dict[int, dict[str, Any]] = {}
    for option in options:
        option_id = _positive_db_int(option.get("option_id"), "option ID")
        option_text = option.get("option_text")
        if not isinstance(option_text, str) or not option_text.strip():
            raise ReplaySourceError("option text is missing")
        if option_id in by_option_id:
            raise ReplaySourceError("snapshot option IDs are not unique")
        by_option_id[option_id] = {
            "optionId": option_id,
            "optionNo": _nonnegative_db_int(option.get("option_no"), "option number"),
            "optionText": option_text,
            "points": option.get("points"),
            "chance": option.get("chance"),
        }
    if set(by_option_id) != set(requested_option_ids):
        raise ReplaySourceError("snapshot options do not match the requested Feed option IDs")

    snapshot_raw = {
        "Text": row["raw_text"],
        "SubText": row.get("raw_sub_text"),
        "OptionTemplateId": _sql_json_scalar(row.get("raw_option_template_id")),
        "Config": {"ChoiceLimit": choice_limit},
    }
    return {
        "question": {
            "id": question_id,
            "roundId": _positive_db_int(row.get("question_round_id"), "round ID"),
            "gamedayId": _positive_db_int(row.get("gameday_id"), "gameday ID"),
            "sourceQuestionId": _positive_db_int(row.get("source_question_id"), "source question ID"),
            "questionNo": row.get("question_no"),
            "status": row.get("question_status"),
            "latestSnapshotId": snapshot_id,
            "firstSeenAt": _utc_timestamp(row.get("first_seen_at")),
        },
        "snapshot": {
            "id": snapshot_id,
            "questionId": question_id,
            "snapshotNo": _positive_db_int(row.get("snapshot_no"), "snapshot number"),
            "contentHash": _required_text(row.get("content_hash"), "snapshot hash"),
            "createdAt": _utc_timestamp(row.get("snapshot_created_at")),
            "raw": snapshot_raw,
        },
        "season": {
            "id": _positive_db_int(row.get("season_id"), "season ID"),
            "year": _positive_db_int(row.get("season_year"), "season year"),
            "name": row["season_name"],
            "status": row.get("season_status"),
        },
        "round": {
            "id": _positive_db_int(row.get("round_id"), "round ID"),
            "seasonId": _positive_db_int(row.get("round_season_id"), "round season ID"),
            "roundNumber": _positive_db_int(row.get("round_number"), "round number"),
            "grandPrixName": row["grand_prix_name"],
            "circuitName": row.get("circuit_name"),
            "country": row.get("country"),
            "locality": row.get("locality"),
            "startDate": _date_string(row.get("round_start_date")),
            "endDate": _date_string(row.get("round_end_date")),
            "status": row.get("round_status"),
        },
        "sessions": normalized_sessions,
        "options": [by_option_id[option_id] for option_id in requested_option_ids],
    }


def _fetch_mappings(
    cursor: Any, *, limit: int, expected_columns: frozenset[str]
) -> list[dict[str, Any]]:
    rows = cursor.fetchmany(limit)
    names = [item[0] for item in (cursor.description or ())]
    if set(names) != expected_columns or len(names) != len(expected_columns):
        raise ReplaySourceError("database returned invalid capture columns")
    result = []
    for values in rows:
        if isinstance(values, Mapping):
            # 仅复制固定 SQL 列白名单；驱动行对象的额外键一律丢弃。
            result.append({name: values[name] for name in names if name in values})
        else:
            try:
                result.append(dict(zip(names, values, strict=True)))
            except (TypeError, ValueError):
                raise ReplaySourceError("database returned an invalid capture row") from None
    return result


def _read_only_context_verified(value: Any) -> bool:
    if isinstance(value, Mapping):
        transaction_read_only = value.get("transaction_read_only")
        time_zone = value.get("session_time_zone")
        current_role = value.get("current_role")
        database = value.get("active_database")
    elif isinstance(value, (tuple, list)) and len(value) == 4:
        transaction_read_only, time_zone, current_role, database = value
    else:
        return False
    return (
        _flag_true(transaction_read_only)
        and isinstance(time_zone, str)
        and time_zone.upper() in {"+00:00", "UTC", "ETC/UTC"}
        and isinstance(current_role, str)
        and current_role.upper() == "NONE"
        and database == "f1_ai_predict"
    )


def _select_only_grants_verified(rows: Any) -> bool:
    if not isinstance(rows, (tuple, list)) or not rows or len(rows) > 32:
        return False
    granted_tables: set[str] = set()
    for row in rows:
        if isinstance(row, Mapping):
            values = list(row.values())
        elif isinstance(row, (tuple, list)):
            values = list(row)
        else:
            return False
        if len(values) != 1 or not isinstance(values[0], str):
            return False
        grant = values[0].strip()
        if "WITH GRANT OPTION" in grant.upper():
            return False
        if re.fullmatch(r"GRANT\s+USAGE\s+ON\s+\*\.\*\s+TO\s+.+", grant, re.IGNORECASE):
            continue
        match = re.fullmatch(
            r"GRANT\s+SELECT\s+ON\s+`?f1_ai_predict`?\.`?([a-z_]+)`?\s+TO\s+.+",
            grant,
            re.IGNORECASE,
        )
        if match is None:
            return False
        table = match.group(1).lower()
        if table not in _READ_ONLY_TABLES:
            return False
        granted_tables.add(table)
    return granted_tables == _READ_ONLY_TABLES


def _flag_true(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value == 1


def _valid_lap_query(query: Mapping[str, Any]) -> bool:
    if set(query) != {"meeting_key", "session_key", "driver_number"}:
        return False
    if not _positive_int(query.get("meeting_key")):
        return False
    for field in ("session_key", "driver_number"):
        values = query.get(field)
        if (
            not isinstance(values, Mapping)
            or set(values) != {"$in"}
            or not isinstance(values["$in"], (tuple, list))
            or not values["$in"]
            or len(values["$in"]) > _MAX_SESSIONS
            or not all(_positive_int(value) for value in values["$in"])
            or len(set(values["$in"])) != len(values["$in"])
        ):
            return False
    return True


def _expected_lap_projection() -> dict[str, int]:
    from f1_predict.replay.export import RAW_PROJECTION

    return dict(RAW_PROJECTION)


def _looks_like_database(value: Any) -> bool:
    # PyMongo Database 暴露 get_collection；避免导入驱动类型或探测连接。
    return callable(getattr(value, "get_collection", None))


def _sql_json_scalar(value: Any) -> Any:
    if isinstance(value, (bytes, bytearray)):
        value = value.decode("utf-8")
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            # JSON_UNQUOTE 对字符串节点返回原始文本；JSON 数字/布尔值则可正常解析。
            return value
    return value


def _utc_timestamp(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        if value.tzinfo is None or value.utcoffset() is None:
            # 仅在只读门禁确认 session 时区为 UTC 后，为 SQL DATETIME 补时区。
            value = value.replace(tzinfo=UTC)
        return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    raise ReplaySourceError("database timestamp has an unsupported type")


def _date_string(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    raise ReplaySourceError("database date has an unsupported type")


def _to_json_safe(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _to_json_safe(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_to_json_safe(item) for item in value]
    if isinstance(value, tuple):
        return [_to_json_safe(item) for item in value]
    if isinstance(value, Decimal):
        number = float(value)
        if not math.isfinite(number):
            raise ReplaySourceError("database returned a non-finite numeric value")
        return number
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, float) and not math.isfinite(value):
        raise ReplaySourceError("database returned a non-finite numeric value")
    return value


def _positive_db_int(value: Any, field: str) -> int:
    if not _positive_int(value):
        raise ReplaySourceError(f"database {field} is missing or invalid")
    return value


def _nonnegative_db_int(value: Any, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ReplaySourceError(f"database {field} is missing or invalid")
    return value


def _required_text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ReplaySourceError(f"database {field} is missing or invalid")
    return value


def _positive_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0
