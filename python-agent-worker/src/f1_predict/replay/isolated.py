"""真实单题副本的隔离数据库装载门禁与参数化事务写入。"""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

SCOPE = "isolated-real-data-mvp"
DATABASE = "f1_ai_predict"
SCHEMA_FILES = (
    "001_create_database.sql",
    "002_season_round.sql",
    "003_question.sql",
    "004_prediction.sql",
    "006_sync.sql",
    "007_prediction_request_outbox.sql",
    "008_prediction_outcome.sql",
)
EXPECTED_COLUMNS: dict[str, frozenset[str]] = {
    "season": frozenset({"id", "year", "name", "status", "start_date", "end_date"}),
    "round": frozenset({"id", "season_id", "round_number", "grand_prix_name", "circuit_name", "country", "locality", "start_date", "end_date", "status"}),
    "meeting_session": frozenset({"id", "round_id", "meeting_key", "session_key", "session_name", "session_type", "gameday_id", "start_date_utc", "end_date_utc", "status"}),
    "question": frozenset({"id", "round_id", "gameday_id", "source_question_id", "question_no", "question_text", "sub_text", "option_template_id", "choice_limit", "status", "content_hash", "latest_snapshot_id", "first_seen_at"}),
    "question_snapshot": frozenset({"id", "question_id", "snapshot_no", "content_hash", "raw_json", "snapshot_reason", "created_at"}),
    "question_option": frozenset({"id", "snapshot_id", "option_no", "option_id", "option_text", "points", "chance", "is_answer"}),
    "prediction_batch": frozenset({"id", "round_id", "batch_no", "status", "question_count"}),
    "prediction_job": frozenset({"id", "prediction_job_id", "message_id", "batch_id", "question_id", "question_snapshot_id", "status"}),
    "prediction_result": frozenset({"id", "job_id", "question_id", "question_snapshot_id", "confidence", "source_data_cutoff", "model", "prompt_version", "feature_version", "generated_at", "model_version", "embedding_version", "retriever_version"}),
    "prediction_result_item": frozenset({"id", "result_id", "option_id", "position"}),
    "prediction_evidence": frozenset({"id", "result_id", "source_name", "source_url", "published_at", "source_type", "first_seen_at", "event_time", "document_id", "chunk_id"}),
    "sync_record": frozenset({"id", "source_type", "source_url", "gameday_id", "content_hash", "status"}),
    "feed_raw_payload": frozenset({"id", "source_type", "source_url", "gameday_id", "content_hash", "raw_json"}),
    "prediction_request_outbox": frozenset({"id", "prediction_job_id", "message_id", "payload_json", "status", "attempts", "next_attempt_at"}),
    "prediction_outcome_receipt": frozenset({"id", "job_id", "message_id", "outcome_type", "payload_sha256", "received_at"}),
    "prediction_failure": frozenset({"id", "job_id", "message_id", "failure_code", "summary", "attempt", "generated_at", "schema_version"}),
    "prediction_outcome_quarantine": frozenset({"id", "message_id", "body_sha256", "reason_code", "raw_body", "received_at", "replay_status", "replayed_at"}),
}
EXPECTED_TABLES = frozenset(
    {
        "season", "round", "meeting_session", "question", "question_snapshot", "question_option",
        "prediction_batch", "prediction_job", "prediction_result", "prediction_result_item",
        "prediction_evidence", "sync_record", "feed_raw_payload", "prediction_request_outbox",
        "prediction_outcome_receipt", "prediction_failure", "prediction_outcome_quarantine",
    }
)
REQUIRED_UNIQUE_KEYS = {
    "prediction_job": {"uk_job_prediction_id"},
    "prediction_request_outbox": {"uk_outbox_prediction_job", "uk_outbox_message"},
    "prediction_outcome_receipt": {"uk_outcome_receipt_job", "uk_outcome_receipt_message"},
}
REQUIRED_UNIQUE_INDEX_COLUMNS = {
    ("prediction_job", "uk_job_prediction_id"): ("prediction_job_id",),
    ("prediction_request_outbox", "uk_outbox_prediction_job"): ("prediction_job_id",),
    ("prediction_request_outbox", "uk_outbox_message"): ("message_id",),
    ("prediction_outcome_receipt", "uk_outcome_receipt_job"): ("job_id",),
    ("prediction_outcome_receipt", "uk_outcome_receipt_message"): ("message_id",),
}
REQUIRED_CONSTRAINTS = {
    "prediction_request_outbox": {"fk_outbox_prediction_job"},
    "prediction_outcome_receipt": {"fk_outcome_receipt_job", "chk_outcome_receipt_type"},
    "prediction_result": {"chk_prediction_result_confidence"},
    "prediction_outcome_quarantine": {"chk_outcome_quarantine_body_size", "chk_outcome_quarantine_replay_status"},
}
_HASH = re.compile(r"[0-9a-fA-F]{64}\Z")


def schema_script_plan(root: str | Path) -> tuple[tuple[str, str], ...]:
    """只列出允许审阅的固定脚本名和内容摘要，不执行脚本或调用外部进程。"""
    directory = Path(root)
    return tuple(
        (filename, hashlib.sha256((directory / filename).read_bytes()).hexdigest())
        for filename in SCHEMA_FILES
    )


@dataclass(frozen=True, slots=True)
class TargetIdentity:
    """由受信任运行时核验的目标资源身份；字段自身不是所有权证明。"""

    scope: str
    project: str
    service: str
    host: str
    port: int
    database: str
    source_resource_id: str
    target_resource_id: str
    schema_review_id: str
    schema_reviewer: str
    schema_reviewed_at: str
    schema_script_hashes: Mapping[str, str]


class IsolatedLoader:
    """仅向经过调用方核验、结构吻合的隔离 MySQL 装入最小题目副本。"""

    def __init__(
        self,
        connection: Any,
        target_identity: TargetIdentity,
        *,
        schema_root: str | Path,
    ) -> None:
        self._connection = connection
        self._target = target_identity
        self._schema_root = Path(schema_root)
        self.last_audit: dict[str, Any] | None = None
        self._validate_target()

    def load(self, source: dict[str, Any], identity: dict[str, Any]) -> dict[str, Any]:
        """以单个短事务装载；不一致、非空冲突或无法证明提交结果时拒绝。"""
        normalized = _validate_source(source, identity)
        self._validate_schema()
        cursor = self._connection.cursor()
        try:
            self._assert_source_tables_empty_or_replay(cursor, normalized)
            mapping = self._load_transaction(cursor, normalized)
            self._verify_final_counts(cursor, normalized)
            self._connection.commit()
            self.last_audit = _build_local_audit(normalized)
            return mapping
        except Exception:
            try:
                self._connection.rollback()
            except Exception:  # noqa: BLE001, S110
                pass
            # COMMIT 响应丢失时只接受数据库读回的完整一致结果，不盲目重写。
            read_cursor = None
            try:
                read_cursor = self._connection.cursor()
                mapping = self._read_complete_mapping(read_cursor, normalized)
                if mapping is not None:
                    self.last_audit = _build_local_audit(normalized)
                    return mapping
            except Exception:  # noqa: BLE001, S110
                pass
            finally:
                if read_cursor is not None:
                    try:
                        read_cursor.close()
                    except Exception:  # noqa: BLE001, S110
                        pass
            raise
        finally:
            try:
                cursor.close()
            except Exception:  # noqa: BLE001, S110
                pass

    def _validate_target(self) -> None:
        target = self._target
        if not isinstance(target, TargetIdentity):
            raise TypeError("target identity must be a concrete TargetIdentity")
        required = (target.project, target.service, target.schema_review_id, target.schema_reviewer, target.schema_reviewed_at)
        if target.scope != SCOPE or not all(isinstance(value, str) and value.strip() for value in required):
            raise ValueError("target identity scope and review evidence are required")
        if target.database != DATABASE or target.host not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("target must be the dedicated loopback f1_ai_predict database")
        if not isinstance(target.port, int) or isinstance(target.port, bool) or not 1 <= target.port <= 65535:
            raise ValueError("target port must be valid")
        resource_ids = (target.source_resource_id, target.target_resource_id)
        if (
            not all(isinstance(value, str) and value.strip() for value in resource_ids)
            or target.source_resource_id == target.target_resource_id
        ):
            raise ValueError("source and isolated target resource identities must be distinct")
        try:
            reviewed_at = datetime.fromisoformat(target.schema_reviewed_at)
        except ValueError as error:
            raise ValueError("schema review timestamp must be timezone-aware") from error
        if reviewed_at.tzinfo is None or reviewed_at.utcoffset() is None:
            raise ValueError("schema review timestamp must be timezone-aware")
        plan = schema_script_plan(self._schema_root)
        expected = dict(plan)
        if dict(target.schema_script_hashes) != expected:
            raise ValueError("reviewed schema hashes do not match the approved script plan")

    def _validate_schema(self) -> None:
        cursor = self._connection.cursor()
        try:
            cursor.execute(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                "WHERE TABLE_SCHEMA = %s AND TABLE_TYPE = 'BASE TABLE'",
                (DATABASE,),
            )
            tables = {str(row[0]) for row in cursor.fetchall()}
            if tables != EXPECTED_TABLES:
                raise ValueError("target schema tables differ from the approved isolated schema")
            cursor.execute(
                "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
                "WHERE TABLE_SCHEMA = %s",
                (DATABASE,),
            )
            columns: dict[str, set[str]] = {}
            for table, column in cursor.fetchall():
                columns.setdefault(str(table), set()).add(str(column))
            for table, required in EXPECTED_COLUMNS.items():
                if not required <= columns.get(table, set()):
                    raise ValueError(f"target schema is missing required columns for {table}")
            cursor.execute(
                "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, COLUMN_NAME, SEQ_IN_INDEX FROM information_schema.STATISTICS "
                "WHERE TABLE_SCHEMA = %s AND NON_UNIQUE = 0 ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX",
                (DATABASE,),
            )
            unique_keys: dict[str, set[str]] = {}
            index_columns: dict[tuple[str, str], list[tuple[int, str]]] = {}
            for table, name, _, column, sequence in cursor.fetchall():
                table_name, index_name = str(table), str(name)
                unique_keys.setdefault(table_name, set()).add(index_name)
                index_columns.setdefault((table_name, index_name), []).append((int(sequence), str(column)))
            for table, names in REQUIRED_UNIQUE_KEYS.items():
                if not names <= unique_keys.get(table, set()):
                    raise ValueError(f"target schema is missing required unique keys for {table}")
            for index, columns_expected in REQUIRED_UNIQUE_INDEX_COLUMNS.items():
                actual = tuple(column for _, column in sorted(index_columns.get(index, [])))
                if actual != columns_expected:
                    raise ValueError(f"target schema unique key columns differ for {index[1]}")
            cursor.execute(
                "SELECT TABLE_NAME, CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                "WHERE CONSTRAINT_SCHEMA = %s AND CONSTRAINT_TYPE IN ('CHECK', 'FOREIGN KEY')",
                (DATABASE,),
            )
            constraints: dict[str, set[str]] = {}
            for table, name in cursor.fetchall():
                constraints.setdefault(str(table), set()).add(str(name))
            for table, names in REQUIRED_CONSTRAINTS.items():
                if not names <= constraints.get(table, set()):
                    raise ValueError(f"target schema is missing required constraints for {table}")
        finally:
            try:
                cursor.close()
            except Exception:  # noqa: BLE001, S110
                pass

    def _assert_source_tables_empty_or_replay(self, cursor: Any, data: dict[str, Any]) -> None:
        # 只允许本题自然键对应的既有行；其他业务表必须保持空白。
        expected = {table: 0 for table in EXPECTED_TABLES}
        expected.update({
            "season": 1,
            "round": 1,
            "meeting_session": len(data["sessions"]),
            "question": 1,
            "question_snapshot": 1,
            "question_option": len(data["options"]),
        })
        counts = {}
        for table, expected_count in expected.items():
            cursor.execute(f"SELECT COUNT(*) FROM `{table}`")
            row = cursor.fetchone()
            if row is None:
                raise ValueError("cannot verify isolated target table state")
            counts[table] = int(row[0])
            if counts[table] not in (0, expected_count):
                raise ValueError(f"non-empty isolated {table} table is not an exact replay")
        imported_tables = ("season", "round", "meeting_session", "question", "question_snapshot", "question_option")
        if any(counts[table] for table in imported_tables) and any(
            counts[table] != expected[table] for table in imported_tables
        ):
            raise ValueError("partially imported isolated target cannot be resumed")

    def _verify_final_counts(self, cursor: Any, data: dict[str, Any]) -> None:
        expected = {table: 0 for table in EXPECTED_TABLES}
        expected.update(
            {
                "season": 1,
                "round": 1,
                "meeting_session": len(data["sessions"]),
                "question": 1,
                "question_snapshot": 1,
                "question_option": len(data["options"]),
            }
        )
        for table, count in expected.items():
            cursor.execute(f"SELECT COUNT(*) FROM `{table}`")
            row = cursor.fetchone()
            if row is None or int(row[0]) != count:
                raise ValueError(f"target {table} table contains unrelated rows")

    def _load_transaction(self, cursor: Any, data: dict[str, Any]) -> dict[str, Any]:
        # 整个 seed、赛事目录与题目树只在本短事务内写入。
        season_id = self._insert_or_match(cursor, "season", ("year",), (data["season"]["year"],),
            "SELECT id, name, status, start_date, end_date FROM season WHERE year = %s",
            (data["season"]["year"],), (data["season"]["name"], data["season"]["status"], None, None),
            "INSERT INTO season (year, name, status) VALUES (%s, %s, %s)",
            (data["season"]["year"], data["season"]["name"], data["season"]["status"]))
        round_data = data["round"]
        round_id = self._insert_or_match(cursor, "round", (), (),
            "SELECT id, grand_prix_name, circuit_name, country, locality, start_date, end_date, status FROM `round` WHERE season_id = %s AND round_number = %s",
            (season_id, round_data["roundNumber"]),
            (round_data["grandPrixName"], round_data["circuitName"], round_data["country"], round_data["locality"], round_data["startDate"], round_data["endDate"], round_data["status"]),
            "INSERT INTO `round` (season_id, round_number, grand_prix_name, circuit_name, country, locality, start_date, end_date, status) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            (season_id, round_data["roundNumber"], round_data["grandPrixName"], round_data["circuitName"], round_data["country"], round_data["locality"], round_data["startDate"], round_data["endDate"], round_data["status"]))
        for session in data["sessions"]:
            self._insert_or_match(cursor, "meeting_session", (), (),
                "SELECT id, round_id, meeting_key, session_name, session_type, gameday_id, start_date_utc, end_date_utc, status FROM meeting_session WHERE session_key = %s",
                (session["sessionKey"],),
                (round_id, session["meetingKey"], session["sessionName"], session["sessionType"], session["gamedayId"], session["startDateUtc"], session["endDateUtc"], session["status"]),
                "INSERT INTO meeting_session (round_id, meeting_key, session_key, session_name, session_type, gameday_id, start_date_utc, end_date_utc, status) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                (round_id, session["meetingKey"], session["sessionKey"], session["sessionName"], session["sessionType"], session["gamedayId"], _mysql_datetime(session["startDateUtc"]), _mysql_datetime(session["endDateUtc"]), session["status"]))
        q, snap = data["question"], data["snapshot"]
        question_id = self._insert_or_match(cursor, "question", (), (),
            "SELECT id, round_id, question_no, question_text, sub_text, option_template_id, choice_limit, status, content_hash, first_seen_at FROM question WHERE gameday_id = %s AND source_question_id = %s",
            (q["gamedayId"], q["sourceQuestionId"]),
            (round_id, q["questionNo"], snap["raw"]["Text"], snap["raw"]["SubText"], snap["raw"]["OptionTemplateId"], snap["raw"]["Config"]["ChoiceLimit"], "OPEN", snap["contentHash"], q["firstSeenAt"]),
            "INSERT INTO question (round_id, gameday_id, source_question_id, question_no, question_text, sub_text, option_template_id, choice_limit, status, content_hash, first_seen_at, last_synced_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,'OPEN',%s,%s,%s)",
            (round_id, q["gamedayId"], q["sourceQuestionId"], q["questionNo"], snap["raw"]["Text"], snap["raw"]["SubText"], snap["raw"]["OptionTemplateId"], snap["raw"]["Config"]["ChoiceLimit"], snap["contentHash"], _mysql_datetime(q["firstSeenAt"]), _mysql_datetime(q["firstSeenAt"])))
        raw_json = json.dumps(snap["raw"], ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        snapshot_id = self._insert_or_match(cursor, "question_snapshot", (), (),
            "SELECT id, content_hash, raw_json, snapshot_reason, created_at FROM question_snapshot WHERE question_id = %s AND snapshot_no = %s",
            (question_id, snap["snapshotNo"]), (snap["contentHash"], raw_json, "INITIAL", snap["createdAt"]),
            "INSERT INTO question_snapshot (question_id, snapshot_no, content_hash, raw_json, snapshot_reason, created_at) VALUES (%s,%s,%s,%s,%s,%s)",
            (question_id, snap["snapshotNo"], snap["contentHash"], raw_json, "INITIAL", _mysql_datetime(snap["createdAt"])))
        self._match_or_insert_options(cursor, snapshot_id, data["options"])
        cursor.execute("UPDATE question SET latest_snapshot_id = %s WHERE id = %s AND latest_snapshot_id IS NULL", (snapshot_id, question_id))
        cursor.execute("SELECT latest_snapshot_id, status FROM question WHERE id = %s", (question_id,))
        row = cursor.fetchone()
        if row is None or int(row[0]) != snapshot_id or row[1] != "OPEN":
            raise ValueError("target question does not match the imported open snapshot")
        return {"questionId": question_id, "snapshotId": snapshot_id, "roundId": round_id, "optionIds": self._option_mapping(cursor, snapshot_id, data["options"])}

    def _insert_or_match(self, cursor: Any, table: str, _keys: tuple[Any, ...], _unused: tuple[Any, ...], select_sql: str, select_params: tuple[Any, ...], expected: tuple[Any, ...], insert_sql: str, insert_params: tuple[Any, ...]) -> int:
        cursor.execute(select_sql, select_params)
        row = cursor.fetchone()
        if row is not None:
            if tuple(_normalize_db(v) for v in row[1:]) != tuple(_normalize_db(v) for v in expected):
                raise ValueError(f"existing {table} row conflicts with imported source content")
            return int(row[0])
        cursor.execute(insert_sql, insert_params)
        identifier = getattr(cursor, "lastrowid", None)
        if not isinstance(identifier, int) or identifier <= 0:
            raise ValueError(f"database did not return an actual {table} id")
        cursor.execute(select_sql, select_params)
        row = cursor.fetchone()
        if row is None or int(row[0]) != identifier or tuple(_normalize_db(v) for v in row[1:]) != tuple(_normalize_db(v) for v in expected):
            raise ValueError(f"inserted {table} row failed database readback")
        return identifier

    def _match_or_insert_options(self, cursor: Any, snapshot_id: int, options: list[dict[str, Any]]) -> None:
        for index, option in enumerate(options):
            expected = (option["optionId"], option["optionText"], option["points"], option["chance"], 0)
            cursor.execute("SELECT option_id, option_text, points, chance, is_answer FROM question_option WHERE snapshot_id = %s AND option_no = %s", (snapshot_id, index))
            row = cursor.fetchone()
            if row is not None:
                if tuple(_normalize_db(v) for v in row) != tuple(_normalize_db(v) for v in expected):
                    raise ValueError("existing option conflicts with imported source content")
                continue
            cursor.execute("INSERT INTO question_option (snapshot_id, option_no, option_id, option_text, points, chance, is_answer) VALUES (%s,%s,%s,%s,%s,%s,0)", (snapshot_id, index, option["optionId"], option["optionText"], option["points"], option["chance"]))
            actual_pk = getattr(cursor, "lastrowid", None)
            cursor.execute("SELECT id, option_id, option_text, points, chance, is_answer FROM question_option WHERE snapshot_id = %s AND option_no = %s", (snapshot_id, index))
            readback = cursor.fetchone()
            if not isinstance(actual_pk, int) or readback is None or int(readback[0]) != actual_pk or tuple(_normalize_db(v) for v in readback[1:]) != tuple(_normalize_db(v) for v in expected):
                raise ValueError("inserted option failed database readback")

    def _option_mapping(self, cursor: Any, snapshot_id: int, options: list[dict[str, Any]]) -> dict[str, int]:
        mapping = {}
        for index, option in enumerate(options):
            cursor.execute("SELECT option_id FROM question_option WHERE snapshot_id = %s AND option_no = %s", (snapshot_id, index))
            row = cursor.fetchone()
            if row is None or row[0] is None:
                raise ValueError("target option is missing its Feed optionId")
            mapping[str(option["optionId"])] = int(row[0])
        return mapping

    def _read_complete_mapping(self, cursor: Any, data: dict[str, Any]) -> dict[str, Any] | None:
        try:
            self._verify_final_counts(cursor, data)
            season, round_data = data["season"], data["round"]
            cursor.execute("SELECT id, name, status FROM season WHERE year = %s", (season["year"],))
            season_row = cursor.fetchone()
            if season_row is None or tuple(season_row[1:]) != (season["name"], season["status"]):
                return None
            season_id = int(season_row[0])
            cursor.execute("SELECT id, grand_prix_name, circuit_name, country, locality, start_date, end_date, status FROM `round` WHERE season_id = %s AND round_number = %s", (season_id, round_data["roundNumber"]))
            round_row = cursor.fetchone()
            expected_round = (round_data["grandPrixName"], round_data["circuitName"], round_data["country"], round_data["locality"], round_data["startDate"], round_data["endDate"], round_data["status"])
            if round_row is None or tuple(_normalize_db(v) for v in round_row[1:]) != tuple(_normalize_db(v) for v in expected_round):
                return None
            round_id = int(round_row[0])
            for session in data["sessions"]:
                cursor.execute("SELECT round_id, meeting_key, session_name, session_type, gameday_id, start_date_utc, end_date_utc, status FROM meeting_session WHERE session_key = %s", (session["sessionKey"],))
                session_row = cursor.fetchone()
                expected_session = (round_id, session["meetingKey"], session["sessionName"], session["sessionType"], session["gamedayId"], _mysql_datetime(session["startDateUtc"]), _mysql_datetime(session["endDateUtc"]), session["status"])
                if session_row is None or tuple(_normalize_db(v) for v in session_row) != tuple(_normalize_db(v) for v in expected_session):
                    return None
            q = data["question"]
            cursor.execute("SELECT id, latest_snapshot_id, round_id, question_no, question_text, sub_text, option_template_id, choice_limit, status, content_hash, first_seen_at FROM question WHERE gameday_id = %s AND source_question_id = %s", (q["gamedayId"], q["sourceQuestionId"]))
            row = cursor.fetchone()
            expected_question = (None, round_id, q["questionNo"], data["snapshot"]["raw"]["Text"], data["snapshot"]["raw"]["SubText"], data["snapshot"]["raw"]["OptionTemplateId"], data["snapshot"]["raw"]["Config"]["ChoiceLimit"], "OPEN", data["snapshot"]["contentHash"], _mysql_datetime(q["firstSeenAt"]))
            if row is None or tuple(_normalize_db(v) for v in (row[2], *row[3:])) != tuple(_normalize_db(v) for v in expected_question[1:]):
                return None
            question_id, snapshot_id = int(row[0]), int(row[1])
            if int(row[2]) != round_id:
                return None
            cursor.execute("SELECT content_hash, raw_json FROM question_snapshot WHERE id = %s AND question_id = %s", (snapshot_id, question_id))
            snapshot = cursor.fetchone()
            expected_raw = json.dumps(data["snapshot"]["raw"], ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            if snapshot is None or snapshot[0] != data["snapshot"]["contentHash"] or _json_value(snapshot[1]) != json.loads(expected_raw):
                return None
            for index, option in enumerate(data["options"]):
                cursor.execute("SELECT option_id, option_text, points, chance, is_answer FROM question_option WHERE snapshot_id = %s AND option_no = %s", (snapshot_id, index))
                option_row = cursor.fetchone()
                expected_option = (option["optionId"], option["optionText"], option["points"], option["chance"], 0)
                if option_row is None or tuple(_normalize_db(v) for v in option_row) != tuple(_normalize_db(v) for v in expected_option):
                    return None
            option_ids = self._option_mapping(cursor, snapshot_id, data["options"])
            return {"questionId": question_id, "snapshotId": snapshot_id, "roundId": round_id, "optionIds": option_ids}
        except Exception:  # noqa: BLE001
            return None


def _validate_source(source: Mapping[str, Any], identity: Mapping[str, Any]) -> dict[str, Any]:
    """限定实际装载的数据字段，避免未知源字段进入 SQL 或 JSON。"""
    if not isinstance(source, Mapping) or set(source) != {"question", "snapshot", "season", "round", "sessions", "options"}:
        raise ValueError("source must contain exactly question, snapshot, season, round, sessions and options")
    if not isinstance(identity, Mapping):
        raise TypeError("source identity evidence must be a mapping")
    q, snap, season, round_data = (source[k] for k in ("question", "snapshot", "season", "round"))
    _exact_keys(q, {"id", "roundId", "gamedayId", "sourceQuestionId", "questionNo", "status", "latestSnapshotId", "firstSeenAt"}, "question")
    _exact_keys(snap, {"id", "questionId", "snapshotNo", "contentHash", "createdAt", "raw"}, "snapshot")
    _exact_keys(season, {"id", "year", "name", "status"}, "season")
    _exact_keys(round_data, {"id", "seasonId", "roundNumber", "grandPrixName", "circuitName", "country", "locality", "startDate", "endDate", "status"}, "round")
    _positive(q["id"], "question.id")
    _positive(snap["id"], "snapshot.id")
    _positive(season["id"], "season.id")
    _positive(round_data["id"], "round.id")
    _required_text(q["status"], "question.status")
    if q["latestSnapshotId"] != snap["id"] or snap["questionId"] != q["id"] or q["roundId"] != round_data["id"] or round_data["seasonId"] != season["id"]:
        raise ValueError("source question, snapshot, round and season references are inconsistent")
    if not isinstance(snap["contentHash"], str) or _HASH.fullmatch(snap["contentHash"]) is None:
        raise ValueError("snapshot contentHash must be a SHA-256 hex digest")
    raw = snap["raw"]
    _exact_keys(raw, {"Text", "SubText", "OptionTemplateId", "Config"}, "snapshot.raw")
    _exact_keys(raw["Config"], {"ChoiceLimit"}, "snapshot.raw.Config")
    _required_text(raw["Text"], "snapshot.raw.Text")
    _positive(q["gamedayId"], "gamedayId")
    _positive(q["sourceQuestionId"], "sourceQuestionId")
    _positive(snap["snapshotNo"], "snapshotNo")
    sessions = source["sessions"]
    if not isinstance(sessions, list) or not 1 <= len(sessions) <= 64:
        raise ValueError("sessions must contain between one and 64 entries")
    session_fields = {"meetingKey", "sessionKey", "sessionName", "sessionType", "gamedayId", "startDateUtc", "endDateUtc", "status"}
    session_keys: set[int] = set()
    for item in sessions:
        _exact_keys(item, session_fields, "session")
        session_key = _positive(item["sessionKey"], "sessionKey")
        if session_key in session_keys:
            raise ValueError("sessionKey values must be unique")
        session_keys.add(session_key)
    options = source["options"]
    if not isinstance(options, list) or len(options) != 2:
        raise ValueError("single-choice source must contain exactly two options")
    choice_limit = raw["Config"]["ChoiceLimit"]
    if isinstance(choice_limit, bool) or not isinstance(choice_limit, int) or choice_limit != 1:
        raise ValueError("single-choice source must have ChoiceLimit equal to one")
    option_fields = {"optionId", "optionNo", "optionText", "points", "chance"}
    option_ids: set[int] = set()
    for expected_no, option in enumerate(options):
        _exact_keys(option, option_fields, "option")
        option_id = _positive(option["optionId"], "optionId")
        if option_id in option_ids:
            raise ValueError("Feed optionId values must be unique")
        option_ids.add(option_id)
        option_no = option["optionNo"]
        if isinstance(option_no, bool) or not isinstance(option_no, int) or option_no != expected_no:
            raise ValueError("optionNo must be unique and match the source option order")
    # 身份证据中的 question id 是源 MySQL 主键；Feed ID 仍由 sourceQuestionId 字段承载。
    for key, expected in (("sourceQuestionId", q["id"]), ("sourceSnapshotId", snap["id"]), ("sourceRoundId", round_data["id"])):
        if _positive(identity.get(key), f"identity.{key}") != expected:
            raise ValueError(f"identity evidence does not match {key}")
    return {"question": dict(q), "snapshot": dict(snap), "season": dict(season), "round": dict(round_data), "sessions": [dict(x) for x in sessions], "options": [dict(x) for x in options]}


def _build_local_audit(data: Mapping[str, Any]) -> dict[str, Any]:
    """保留仅本地 staging 使用的源状态与内容摘要，不写入目标数据库。"""
    question = data["question"]
    snapshot = data["snapshot"]
    return {
        "sourceQuestionId": question["id"],
        "sourceFeedQuestionId": question["sourceQuestionId"],
        "sourceStatus": question["status"],
        "sourceSnapshotId": snapshot["id"],
        "sourceSnapshotHash": snapshot["contentHash"],
    }


def _exact_keys(value: Any, keys: set[str], label: str) -> None:
    if not isinstance(value, Mapping) or set(value) != keys:
        raise ValueError(f"{label} has unknown or missing fields")


def _required_text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be a non-empty string")
    return value


def _positive(value: Any, name: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ValueError(f"{name} must be a positive integer")
    return value


def _normalize_db(value: Any) -> Any:
    if isinstance(value, datetime):
        return value.astimezone(UTC).replace(tzinfo=None) if value.tzinfo else value
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, str) and value.endswith("Z"):
        parsed = datetime.fromisoformat(value)
        return parsed.astimezone(UTC).replace(tzinfo=None)
    return value


def _mysql_datetime(value: Any) -> datetime | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        parsed = value
    elif isinstance(value, str):
        parsed = datetime.fromisoformat(value)
    else:
        raise TypeError("database timestamps must be timezone-aware date-times")
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("database timestamps must include a timezone")
    return parsed.astimezone(UTC).replace(tzinfo=None)


def _json_value(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return value
    return value
