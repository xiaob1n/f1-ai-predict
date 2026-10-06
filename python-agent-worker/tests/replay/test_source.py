from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Any

import pytest

from f1_predict.replay.export import RAW_PROJECTION, read_laps_bounded
from f1_predict.replay.source import (
    MYSQL_CAPTURE_QUERY,
    MYSQL_OPTIONS_QUERY,
    MYSQL_READ_ONLY_CHECK,
    MYSQL_SESSIONS_QUERY,
    MYSQL_SHOW_GRANTS,
    MongoLapSource,
    MySqlReplaySource,
    ReplaySourceError,
)


class FakeMongoCursor:
    def __init__(self, rows: list[dict[str, Any]]) -> None:
        self.rows = rows
        self.requested_limit: int | None = None
        self.closed = False

    def limit(self, value: int) -> FakeMongoCursor:
        self.requested_limit = value
        return self

    def __iter__(self):
        return iter(self.rows[: self.requested_limit])

    def close(self) -> None:
        self.closed = True


class FakeMongoCollection:
    def __init__(self, rows: list[dict[str, Any]]) -> None:
        self.rows = rows
        self.find_calls: list[tuple[Any, ...]] = []
        self.cursor: FakeMongoCursor | None = None

    def find(self, query, projection, *, max_time_ms):
        self.find_calls.append((query, projection, max_time_ms))
        self.cursor = FakeMongoCursor(self.rows)
        return self.cursor


class FakeMongoDatabase:
    def __init__(self, collection: FakeMongoCollection) -> None:
        self.collection = collection
        self.item_calls: list[str] = []

    def get_collection(self, name: str) -> FakeMongoCollection:
        self.item_calls.append(name)
        if name != "laps":
            raise AssertionError("unexpected collection")
        return self.collection


class FakeCursor:
    def __init__(self, connection: FakeConnection) -> None:
        self.connection = connection
        self.description = None
        self.rows: list[Any] = []
        self.closed = False

    def execute(self, sql: str, params=None) -> None:
        self.connection.executions.append((sql, params))
        if sql == "START TRANSACTION READ ONLY":
            self.description = []
            self.rows = []
        elif sql == MYSQL_READ_ONLY_CHECK:
            self.description = [(name,) for name in ("transaction_read_only", "session_time_zone", "current_role", "active_database")]
            self.rows = [self.connection.flags]
        elif sql == MYSQL_SHOW_GRANTS:
            self.description = [("Grants for replay_reader@%",)]
            self.rows = self.connection.grants[:]
        elif sql == MYSQL_CAPTURE_QUERY:
            schema = self.connection.capture[0] if self.connection.capture else capture_row()
            self.description = [(key,) for key in schema if key != "ignored_secret"]
            self.rows = [{key: value for key, value in row.items() if key != "ignored_secret"} for row in self.connection.capture]
        elif sql == MYSQL_SESSIONS_QUERY:
            schema = self.connection.sessions[0] if self.connection.sessions else {
                "meeting_key": None, "session_key": None, "session_name": None, "session_type": None,
                "gameday_id": None, "start_date_utc": None, "end_date_utc": None, "status": None,
            }
            self.description = [(key,) for key in schema if key != "ignored_secret"]
            self.rows = [{key: value for key, value in row.items() if key != "ignored_secret"} for row in self.connection.sessions]
        elif sql == MYSQL_OPTIONS_QUERY:
            schema = self.connection.options[0] if self.connection.options else {
                "option_id": None, "option_no": None, "option_text": None, "points": None, "chance": None,
            }
            self.description = [(key,) for key in schema if key != "is_answer"]
            self.rows = [{key: value for key, value in row.items() if key != "is_answer"} for row in self.connection.options]
        else:
            raise AssertionError("unexpected SQL")

    def fetchone(self):
        return self.rows.pop(0) if self.rows else None

    def fetchmany(self, size: int):
        rows, self.rows = self.rows[:size], self.rows[size:]
        return rows

    def close(self) -> None:
        self.closed = True


class FakeConnection:
    def __init__(self) -> None:
        self.flags: tuple[Any, ...] = (1, "+00:00", "NONE", "f1_ai_predict")
        self.grants = [("GRANT USAGE ON *.* TO `replay_reader`@`%`",)] + [
            (f"GRANT SELECT ON `f1_ai_predict`.`{table}` TO `replay_reader`@`%`",)
            for table in ("season", "round", "meeting_session", "question", "question_snapshot", "question_option")
        ]
        self.capture = [capture_row()]
        self.sessions = [
            {
                "meeting_key": 1293,
                "session_key": 11355,
                "session_name": "Practice 1",
                "session_type": "Practice",
                "gameday_id": 77,
                "start_date_utc": datetime(2026, 9, 5, 12),  # noqa: DTZ001 - 模拟 MySQL DATETIME。
                "end_date_utc": datetime(2026, 9, 5, 13),  # noqa: DTZ001 - 模拟 MySQL DATETIME。
                "status": "FINISHED",
                "ignored_secret": "not trusted",
            }
        ]
        self.options = [
            {"option_id": 18, "option_no": 0, "option_text": "Driver A", "points": 10, "chance": Decimal("0.2500"), "is_answer": 1},
            {"option_id": 11059, "option_no": 1, "option_text": "Driver B", "points": 5, "chance": Decimal("0.7500"), "is_answer": 0},
        ]
        self.executions: list[tuple[str, Any]] = []
        self.rollbacks = 0
        self.cursor_obj: FakeCursor | None = None

    def cursor(self) -> FakeCursor:
        self.cursor_obj = FakeCursor(self)
        return self.cursor_obj

    def rollback(self) -> None:
        self.rollbacks += 1


def capture_row() -> dict[str, Any]:
    return {
        "question_id": 12,
        "question_round_id": 31,
        "gameday_id": 77,
        "source_question_id": 9001,
        "question_no": 1,
        "question_status": "OPEN",
        "latest_snapshot_id": 45,
        "first_seen_at": datetime(2026, 9, 1, 10),  # noqa: DTZ001 - 模拟 MySQL DATETIME。
        "snapshot_id": 45,
        "snapshot_question_id": 12,
        "snapshot_no": 2,
        "content_hash": "a" * 64,
        "snapshot_created_at": datetime(2026, 9, 1, 11),  # noqa: DTZ001 - 模拟 MySQL DATETIME。
        "raw_text": "Who will lead?",
        "raw_sub_text": None,
        "raw_option_template_id": "3",
        "raw_choice_limit": "1",
        "round_id": 31,
        "round_season_id": 8,
        "round_number": 16,
        "grand_prix_name": "Example Grand Prix",
        "circuit_name": "Example Circuit",
        "country": "Exampleland",
        "locality": "Example City",
        "round_start_date": datetime(2026, 9, 4),  # noqa: DTZ001 - 模拟 MySQL DATE 转换值。
        "round_end_date": datetime(2026, 9, 6),  # noqa: DTZ001 - 模拟 MySQL DATE 转换值。
        "round_status": "FINISHED",
        "season_id": 8,
        "season_year": 2026,
        "season_name": "2026 Championship",
        "season_status": "IN_PROGRESS",
        "ignored_secret": "not trusted",
    }


def test_mongo_adapter_is_lazy_and_forwards_only_fixed_bounded_find() -> None:
    collection = FakeMongoCollection([{"_key": "lap-1", "password": "drop"}])
    database = FakeMongoDatabase(collection)
    source = MongoLapSource(database)
    assert database.item_calls == []
    assert collection.find_calls == []

    rows = read_laps_bounded(
        source,
        mongo_meeting_key=1293,
        session_keys=(11355,),
        driver_numbers=(10, 43),
    )

    query, projection, max_time_ms = collection.find_calls[0]
    assert database.item_calls == ["laps"]
    assert query == {
        "meeting_key": 1293,
        "session_key": {"$in": [11355]},
        "driver_number": {"$in": [10, 43]},
    }
    assert projection == RAW_PROJECTION
    assert max_time_ms == 5000
    assert collection.cursor is not None and collection.cursor.requested_limit == 501
    assert collection.cursor.closed
    assert rows[0] == {"_key": "lap-1"}
    assert "password" not in rows[0]


@pytest.mark.parametrize(
    "kwargs",
    [
        {"collection": "question", "query": {"meeting_key": 1293, "session_key": {"$in": [1]}, "driver_number": {"$in": [2]}}},
        {"collection": "laps", "query": {"meeting_key": 1293, "session_key": {"$in": [1]}, "driver_number": {"$in": [2]}, "is_answer": 1}},
        {"collection": "laps", "query": {"meeting_key": 1293, "session_key": {"$in": [True]}, "driver_number": {"$in": [2]}}},
    ],
)
def test_mongo_adapter_rejects_unapproved_collection_or_query(kwargs) -> None:
    collection = FakeMongoCollection([])
    source = MongoLapSource(collection)
    with pytest.raises(ReplaySourceError):
        source.find(
            kwargs["collection"],
            kwargs["query"],
            projection=RAW_PROJECTION,
            max_time_ms=5000,
            limit=501,
        )
    assert collection.find_calls == []


def test_mongo_adapter_rejects_projection_timeout_and_limit_changes() -> None:
    collection = FakeMongoCollection([])
    source = MongoLapSource(collection)
    query = {"meeting_key": 1293, "session_key": {"$in": [1]}, "driver_number": {"$in": [2]}}
    for projection, timeout, limit in (({"is_answer": 1}, 5000, 501), (RAW_PROJECTION, 0, 501), (RAW_PROJECTION, 5000, 500)):
        with pytest.raises(ReplaySourceError):
            source.find("laps", query, projection=projection, max_time_ms=timeout, limit=limit)
    assert collection.find_calls == []


def test_mongo_adapter_closes_cursor_when_iteration_fails() -> None:
    class BrokenCursor(FakeMongoCursor):
        def __iter__(self):
            raise RuntimeError("secret endpoint failure")

    class BrokenCollection(FakeMongoCollection):
        def find(self, query, projection, *, max_time_ms):
            self.cursor = BrokenCursor([])
            return self.cursor

    collection = BrokenCollection([])
    source = MongoLapSource(collection)
    with pytest.raises(ReplaySourceError, match="query failed") as error:
        list(source.find(
            "laps",
            {"meeting_key": 1293, "session_key": {"$in": [1]}, "driver_number": {"$in": [2]}},
            projection=RAW_PROJECTION,
            max_time_ms=5000,
            limit=501,
        ))
    assert "secret endpoint" not in str(error.value)
    assert collection.cursor is not None and collection.cursor.closed


def test_mysql_adapter_returns_explicit_allowlisted_capture_and_closes_read_transaction() -> None:
    connection = FakeConnection()
    source = MySqlReplaySource(connection)

    result = source.read_capture(12, 45, (18, 11059))

    assert set(result) == {"question", "snapshot", "season", "round", "sessions", "options"}
    assert result["question"]["firstSeenAt"] == "2026-09-01T10:00:00.000Z"
    assert result["snapshot"]["raw"] == {
        "Text": "Who will lead?",
        "SubText": None,
        "OptionTemplateId": 3,
        "Config": {"ChoiceLimit": 1},
    }
    assert result["sessions"][0]["startDateUtc"] == "2026-09-05T12:00:00.000Z"
    assert result["options"][0]["chance"] == 0.25
    assert all("ignored_secret" not in row for key in ("question", "snapshot", "season", "round") for row in [result[key]])
    executed_sql = [sql for sql, _ in connection.executions]
    assert executed_sql[0] == "START TRANSACTION READ ONLY"
    assert MYSQL_READ_ONLY_CHECK in executed_sql
    assert MYSQL_SHOW_GRANTS in executed_sql
    assert MYSQL_CAPTURE_QUERY in executed_sql
    assert MYSQL_SESSIONS_QUERY in executed_sql
    assert MYSQL_OPTIONS_QUERY in executed_sql
    assert all("is_answer" not in sql.lower() for sql in executed_sql)
    assert "select s.raw_json" not in MYSQL_CAPTURE_QUERY.lower()
    assert "json_extract(s.raw_json, '$.text')" in MYSQL_CAPTURE_QUERY.lower()
    assert (12, 45) in [params for _, params in connection.executions]
    assert connection.rollbacks == 1
    assert connection.cursor_obj is not None and connection.cursor_obj.closed


@pytest.mark.parametrize(
    "flags",
    [
        (0, "+00:00", "NONE", "f1_ai_predict"),
        (True, "+00:00", "NONE", "f1_ai_predict"),
        (1, "SYSTEM", "NONE", "f1_ai_predict"),
        (1, "+00:00", "`unsafe_role`@`%`", "f1_ai_predict"),
        (1, "+00:00", "NONE", "other_database"),
    ],
)
def test_mysql_fails_closed_without_read_only_transaction_utc_or_principal_context(flags) -> None:
    connection = FakeConnection()
    connection.flags = flags

    with pytest.raises(ReplaySourceError, match="transaction-read-only UTC"):
        MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))

    assert [sql for sql, _ in connection.executions] == ["START TRANSACTION READ ONLY", MYSQL_READ_ONLY_CHECK]
    assert connection.rollbacks == 1
    assert connection.cursor_obj is not None and connection.cursor_obj.closed


@pytest.mark.parametrize(
    "grants",
    [
        [("GRANT USAGE ON *.* TO `reader`@`%`",)],
        [("GRANT SELECT, INSERT ON `f1_ai_predict`.`question` TO `reader`@`%`",)],
        [("GRANT SELECT ON `f1_ai_predict`.* TO `reader`@`%`",)],
        [("GRANT SELECT ON `other_db`.`question` TO `reader`@`%`",)],
        [("GRANT SELECT ON `f1_ai_predict`.`question` TO `reader`@`%` WITH GRANT OPTION",)],
        [("GRANT `read_role`@`%` TO `reader`@`%`",)],
    ],
)
def test_mysql_rejects_grants_that_are_not_exact_source_table_select_only(grants) -> None:
    connection = FakeConnection()
    connection.grants = grants

    with pytest.raises(ReplaySourceError, match="SELECT-only"):
        MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))

    assert MYSQL_SHOW_GRANTS in [sql for sql, _ in connection.executions]
    assert MYSQL_CAPTURE_QUERY not in [sql for sql, _ in connection.executions]
    assert connection.rollbacks == 1
    assert connection.cursor_obj is not None and connection.cursor_obj.closed


def test_mysql_select_only_account_works_without_global_read_only_server_flags() -> None:
    connection = FakeConnection()
    # 只验证事务级 READ ONLY 与服务器 SELECT-only grants，不依赖全局只读副本变量。
    result = MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))
    assert result["question"]["id"] == 12
    assert MYSQL_SHOW_GRANTS in [sql for sql, _ in connection.executions]


def test_mysql_cleanup_failures_never_return_successful_capture() -> None:
    class RollbackFailureConnection(FakeConnection):
        def rollback(self) -> None:
            self.rollbacks += 1
            raise RuntimeError("mysql://secret-host/user:password")

    connection = RollbackFailureConnection()
    with pytest.raises(ReplaySourceError, match="cleanup failed safely") as error:
        MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))
    assert "secret-host" not in str(error.value)
    assert "password" not in str(error.value)
    assert connection.cursor_obj is not None and connection.cursor_obj.closed

    class CloseFailureCursor(FakeCursor):
        def close(self) -> None:
            raise RuntimeError("cursor close secret")

    class CloseFailureConnection(FakeConnection):
        def cursor(self) -> FakeCursor:
            self.cursor_obj = CloseFailureCursor(self)
            return self.cursor_obj

    connection = CloseFailureConnection()
    with pytest.raises(ReplaySourceError, match="cleanup failed safely") as error:
        MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))
    assert "cursor close secret" not in str(error.value)
    assert connection.rollbacks == 1


@pytest.mark.parametrize(
    "question_id,snapshot_id,option_ids",
    [(True, 45, (18, 11059)), (12, 0, (18, 11059)), (12, 45, (18, 18)), (12, 45, (18,))],
)
def test_mysql_rejects_invalid_ids_before_opening_cursor(question_id, snapshot_id, option_ids) -> None:
    connection = FakeConnection()
    with pytest.raises(ValueError):
        MySqlReplaySource(connection).read_capture(question_id, snapshot_id, option_ids)
    assert connection.executions == []
    assert connection.rollbacks == 0


def test_mysql_rejects_foreign_or_nonlatest_snapshot_and_rolls_back() -> None:
    for updates in (
        {"snapshot_question_id": 999},
        {"latest_snapshot_id": 46},
        {"snapshot_no": 1},
        {"round_season_id": 999},
    ):
        connection = FakeConnection()
        connection.capture[0].update(updates)
        if updates == {"snapshot_no": 1}:
            # SQL 的 MAX(snapshot_no) 条件应让数据库不返回该非最新记录。
            connection.capture = []
        with pytest.raises(ReplaySourceError):
            MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))
        assert connection.rollbacks == 1
        assert connection.cursor_obj is not None and connection.cursor_obj.closed


def test_mysql_requires_exact_matching_two_feed_options_and_single_choice() -> None:
    connection = FakeConnection()
    connection.options[0]["option_id"] = 19
    with pytest.raises(ReplaySourceError, match="do not match"):
        MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))
    assert connection.rollbacks == 1

    connection = FakeConnection()
    connection.capture[0]["raw_choice_limit"] = "2"
    with pytest.raises(ReplaySourceError, match="single-choice"):
        MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))
    assert connection.rollbacks == 1


def test_mysql_applies_fixed_session_and_option_bounds() -> None:
    connection = FakeConnection()
    connection.sessions = [
        {
            "meeting_key": 1293,
            "session_key": index + 1,
            "session_name": "Practice",
            "session_type": "Practice",
            "gameday_id": 77,
            "start_date_utc": None,
            "end_date_utc": None,
            "status": "FINISHED",
        }
        for index in range(65)
    ]
    with pytest.raises(ReplaySourceError, match="exceeds its bound"):
        MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))
    session_call = next((sql, params) for sql, params in connection.executions if sql == MYSQL_SESSIONS_QUERY)
    assert "LIMIT 65" in session_call[0]
    assert session_call[1] == (31,)
    assert connection.rollbacks == 1

    connection = FakeConnection()
    connection.options.append({"option_id": 42, "option_no": 2, "option_text": "Third"})
    with pytest.raises(ReplaySourceError, match="exactly two"):
        MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))
    assert "LIMIT 3" in MYSQL_OPTIONS_QUERY
    assert connection.rollbacks == 1


def test_mysql_errors_do_not_expose_database_secrets_and_all_paths_cleanup() -> None:
    class SecretFailureConnection(FakeConnection):
        def cursor(self):
            raise RuntimeError("mysql://user:pass@internal-db/private?token=secret")

    connection = SecretFailureConnection()
    with pytest.raises(ReplaySourceError) as error:
        MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))
    assert "internal-db" not in str(error.value)
    assert "token" not in str(error.value)
    assert connection.rollbacks == 1

    connection = FakeConnection()
    connection.capture = []
    with pytest.raises(ReplaySourceError, match="not found uniquely"):
        MySqlReplaySource(connection).read_capture(12, 45, (18, 11059))
    assert connection.rollbacks == 1
    assert connection.cursor_obj is not None and connection.cursor_obj.closed
