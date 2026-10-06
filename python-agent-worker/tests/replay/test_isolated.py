"""隔离装载器安全门禁的 Fake DBAPI 测试。"""

from __future__ import annotations

import json
from copy import deepcopy
from dataclasses import replace
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest

from f1_predict.prediction.replay_context import ReplayContext
from f1_predict.replay.bundle import capture_staging, seal_bundle
from f1_predict.replay.isolated import (
    EXPECTED_COLUMNS,
    EXPECTED_TABLES,
    REQUIRED_CONSTRAINTS,
    REQUIRED_UNIQUE_INDEX_COLUMNS,
    SCHEMA_FILES,
    IsolatedLoader,
    TargetIdentity,
    _normalize_row,
    schema_script_plan,
)
from f1_predict.replay.manifest import canonical_sha256


class FakeCursor:
    """仅按固定 information_schema 查询与计数查询返回结果的离线替身。"""

    def __init__(self, connection: FakeConnection) -> None:
        self.connection = connection
        self.rows: list[tuple[Any, ...]] = []
        self.lastrowid = None
        self.executed: list[str] = []
        self.closed = False

    def execute(self, sql: str, params: tuple[Any, ...] | None = None) -> None:
        self.executed.append(sql)
        self.connection.executed.append(sql)
        if "information_schema.TABLES" in sql:
            self.rows = [(table,) for table in EXPECTED_TABLES]
        elif "information_schema.COLUMNS" in sql:
            self.rows = [(table, col) for table, names in EXPECTED_COLUMNS.items() for col in names]
        elif "information_schema.STATISTICS" in sql:
            self.rows = [
                (table, name, 0, column, position)
                for (table, name), columns in REQUIRED_UNIQUE_INDEX_COLUMNS.items()
                for position, column in enumerate(columns, 1)
            ]
        elif "information_schema.TABLE_CONSTRAINTS" in sql:
            self.rows = [(table, name) for table, names in REQUIRED_CONSTRAINTS.items() for name in names]
        elif sql.startswith("SELECT COUNT(*) FROM"):
            table = sql.split("`")[1]
            self.rows = [(self.connection.counts.get(table, 0),)]
        else:
            raise AssertionError(f"unexpected Fake DBAPI SQL: {sql}")

    def fetchall(self) -> list[tuple[Any, ...]]:
        return self.rows

    def fetchone(self) -> tuple[Any, ...] | None:
        return self.rows[0] if self.rows else None

    def close(self) -> None:
        self.closed = True


class FakeConnection:
    """可记录事务动作、但从不连接真实 MySQL 的 DBAPI 替身。"""

    def __init__(self, *, counts: dict[str, int] | None = None) -> None:
        self.counts = counts or {}
        self.executed: list[str] = []
        self.commits = 0
        self.rollbacks = 0

    def cursor(self) -> FakeCursor:
        return FakeCursor(self)

    def commit(self) -> None:
        self.commits += 1

    def rollback(self) -> None:
        self.rollbacks += 1


class FakeImportConnection(FakeConnection):
    """模拟已审核 schema 中最小导入所需的 SQL 与实际自增主键。"""

    def __init__(self, *, ambiguous_commit: bool = False) -> None:
        super().__init__()
        self.rows = {name: [] for name in EXPECTED_TABLES}
        self.committed_rows = deepcopy(self.rows)
        self.next_id = 900
        self.commit_error: Exception | None = (
            OSError("commit response lost after durable commit") if ambiguous_commit else None
        )
        self.persist_on_commit = True
        self.commit_change: tuple[str, str, Any] | None = None
        self.rollback_error: Exception | None = None
        self.execute_error: tuple[str, Exception] | None = None
        self.final_count_mismatch: str | None = None
        self.cursors: list[FakeImportCursor] = []

    def cursor(self) -> FakeImportCursor:
        cursor = FakeImportCursor(self)
        self.cursors.append(cursor)
        return cursor

    def allocate(self) -> int:
        self.next_id += 7
        return self.next_id

    def commit(self) -> None:
        self.commits += 1
        if self.persist_on_commit:
            self.committed_rows = deepcopy(self.rows)
            if self.commit_change is not None:
                # 模拟持久化后、恢复读回前目标元数据发生变化。
                table, field, value = self.commit_change
                self.committed_rows[table][0][field] = value
        if self.commit_error is not None:
            error, self.commit_error = self.commit_error, None
            raise error

    def rollback(self) -> None:
        self.rollbacks += 1
        if self.rollback_error is not None:
            raise self.rollback_error
        # 回读只看到已提交数据，不把本地事务中的写入误当作持久化结果。
        self.rows = deepcopy(self.committed_rows)


class FakeImportCursor(FakeCursor):
    def execute(self, sql: str, params: tuple[Any, ...] | None = None) -> None:
        params = params or ()
        if self.connection.execute_error is not None:
            prefix, error = self.connection.execute_error
            if sql.startswith(prefix):
                self.executed.append(sql)
                self.connection.executed.append(sql)
                self.connection.execute_error = None
                raise error
        if "information_schema." in sql:
            return super().execute(sql, params)
        self.executed.append(sql)
        self.connection.executed.append(sql)
        tables = self.connection.rows
        self.lastrowid = None
        if sql.startswith("SELECT COUNT(*) FROM"):
            table = sql.split("`")[1]
            count = len(tables[table])
            if self.connection.final_count_mismatch == table and any(
                statement.startswith("UPDATE question SET latest_snapshot_id")
                for statement in self.executed
            ):
                count += 1
            self.rows = [(count,)]
        elif sql.startswith("SELECT id, name, status"):
            found = next((r for r in tables["season"] if r["year"] == params[0]), None)
            values = tuple(found[k] for k in ("id", "name", "status", "start_date", "end_date")) if found else None
            self.rows = [values[:3]] if values and "start_date" not in sql else ([values] if values else [])
        elif sql.startswith("INSERT INTO season"):
            item_id = self.connection.allocate()
            tables["season"].append({"id": item_id, "year": params[0], "name": params[1], "status": params[2], "start_date": None, "end_date": None})
            self.lastrowid = item_id
            self.rows = []
        elif "FROM `round` WHERE season_id" in sql:
            row = next((r for r in tables["round"] if (r["season_id"], r["round_number"]) == params), None)
            self.rows = []
            if row:
                self.rows = [tuple(row[k] for k in ("id", "grand_prix_name", "circuit_name", "country", "locality", "start_date", "end_date", "status"))]
        elif sql.startswith("INSERT INTO `round`"):
            item_id = self.connection.allocate()
            keys = ("season_id", "round_number", "grand_prix_name", "circuit_name", "country", "locality", "start_date", "end_date", "status")
            tables["round"].append({"id": item_id, **dict(zip(keys, params, strict=True))})
            self.lastrowid = item_id
            self.rows = []
        elif "FROM meeting_session WHERE session_key" in sql:
            row = next((r for r in tables["meeting_session"] if r["session_key"] == params[0]), None)
            if row and sql.startswith("SELECT round_id"):
                self.rows = [tuple(row[k] for k in ("round_id", "meeting_key", "session_name", "session_type", "gameday_id", "start_date_utc", "end_date_utc", "status"))]
            else:
                self.rows = [tuple(row[k] for k in ("id", "round_id", "meeting_key", "session_name", "session_type", "gameday_id", "start_date_utc", "end_date_utc", "status"))] if row else []
        elif sql.startswith("INSERT INTO meeting_session"):
            item_id = self.connection.allocate()
            keys = ("round_id", "meeting_key", "session_key", "session_name", "session_type", "gameday_id", "start_date_utc", "end_date_utc", "status")
            tables["meeting_session"].append({"id": item_id, **dict(zip(keys, params, strict=True))})
            self.lastrowid = item_id
            self.rows = []
        elif "FROM question WHERE gameday_id" in sql:
            row = next((r for r in tables["question"] if (r["gameday_id"], r["source_question_id"]) == params), None)
            if not row:
                self.rows = []
            elif "latest_snapshot_id" in sql:
                self.rows = [tuple(row[k] for k in ("id", "latest_snapshot_id", "round_id", "question_no", "question_text", "sub_text", "option_template_id", "choice_limit", "status", "content_hash", "first_seen_at"))]
            else:
                self.rows = [tuple(row[k] for k in ("id", "round_id", "question_no", "question_text", "sub_text", "option_template_id", "choice_limit", "status", "content_hash", "first_seen_at"))]
        elif sql.startswith("INSERT INTO question ("):
            item_id = self.connection.allocate()
            keys = ("round_id", "gameday_id", "source_question_id", "question_no", "question_text", "sub_text", "option_template_id", "choice_limit", "content_hash", "first_seen_at", "last_synced_at")
            values = dict(zip(keys, params, strict=True))
            tables["question"].append({"id": item_id, **values, "status": "OPEN", "latest_snapshot_id": None})
            self.lastrowid = item_id
            self.rows = []
        elif "FROM question_snapshot WHERE id" in sql:
            row = next((r for r in tables["question_snapshot"] if (r["id"], r["question_id"]) == params), None)
            columns = sql.removeprefix("SELECT ").split(" FROM", 1)[0].split(", ")
            self.rows = [tuple(row[column] for column in columns)] if row else []
        elif "FROM question_snapshot WHERE question_id" in sql:
            row = next((r for r in tables["question_snapshot"] if (r["question_id"], r["snapshot_no"]) == params), None)
            columns = sql.removeprefix("SELECT ").split(" FROM", 1)[0].split(", ")
            self.rows = [tuple(row[column] for column in columns)] if row else []
        elif sql.startswith("INSERT INTO question_snapshot"):
            item_id = self.connection.allocate()
            keys = ("question_id", "snapshot_no", "content_hash", "raw_json", "snapshot_reason", "created_at")
            tables["question_snapshot"].append({"id": item_id, **dict(zip(keys, params, strict=True))})
            self.lastrowid = item_id
            self.rows = []
        elif sql.startswith("SELECT option_id, option_text"):
            row = next((r for r in tables["question_option"] if (r["snapshot_id"], r["option_no"]) == params), None)
            self.rows = [tuple(row[k] for k in ("option_id", "option_text", "points", "chance", "is_answer"))] if row else []
        elif sql.startswith("SELECT id, option_id"):
            row = next((r for r in tables["question_option"] if (r["snapshot_id"], r["option_no"]) == params), None)
            self.rows = [tuple(row[k] for k in ("id", "option_id", "option_text", "points", "chance", "is_answer"))] if row else []
        elif sql.startswith("SELECT option_id FROM question_option"):
            row = next((r for r in tables["question_option"] if (r["snapshot_id"], r["option_no"]) == params), None)
            self.rows = [(row["option_id"],)] if row else []
        elif sql.startswith("INSERT INTO question_option"):
            item_id = self.connection.allocate()
            keys = ("snapshot_id", "option_no", "option_id", "option_text", "points", "chance")
            tables["question_option"].append({"id": item_id, **dict(zip(keys, params, strict=True)), "is_answer": 0})
            self.lastrowid = item_id
            self.rows = []
        elif sql.startswith("UPDATE question SET latest_snapshot_id"):
            snapshot_id, question_id = params
            row = next(r for r in tables["question"] if r["id"] == question_id)
            if row["latest_snapshot_id"] is None:
                row["latest_snapshot_id"] = snapshot_id
            self.rows = []
        elif sql.startswith("SELECT latest_snapshot_id, status FROM question"):
            row = next((r for r in tables["question"] if r["id"] == params[0]), None)
            self.rows = [(row["latest_snapshot_id"], row["status"])] if row else []
        else:
            raise AssertionError(f"unexpected Fake import SQL: {sql}")


def _schema_dir(tmp_path: Path) -> Path:
    for name in SCHEMA_FILES:
        (tmp_path / name).write_text(f"-- {name}\n", encoding="utf-8")
    (tmp_path / "005_answer_scoring.sql").write_text("MUST NOT PLAN", encoding="utf-8")
    (tmp_path / "099_unknown.sql").write_text("MUST NOT PLAN", encoding="utf-8")
    return tmp_path


def _target(schema_dir: Path) -> TargetIdentity:
    return TargetIdentity(
        scope="isolated-real-data-mvp",
        project="real-data-mvp-2026-09",
        service="mysql-mvp-oneoff",
        host="127.0.0.1",
        port=13316,
        database="f1_ai_predict",
        source_resource_id="source-feed-prod:questions",
        target_resource_id="docker-volume:mvp_mysql_01",
        schema_review_id="review-2026-09-30-01",
        schema_reviewer="operator-approval-record-17",
        schema_reviewed_at="2026-09-30T10:00:00Z",
        schema_script_hashes=dict(schema_script_plan(schema_dir)),
    )


def _source() -> tuple[dict[str, Any], dict[str, Any]]:
    return (
        {
            "question": {
                "id": 71,
                "roundId": 31,
                "gamedayId": 1174,
                "sourceQuestionId": 902,
                "questionNo": 1,
                "status": "2",
                "latestSnapshotId": 81,
                "firstSeenAt": "2026-09-01T00:00:00Z",
            },
            "snapshot": {
                "id": 81,
                "questionId": 71,
                "snapshotNo": 1,
                "contentHash": "a" * 64,
                "createdAt": "2026-09-01T00:00:00Z",
                "raw": {"Text": "Who wins?", "SubText": None, "OptionTemplateId": 1, "Config": {"ChoiceLimit": 1}},
            },
            "season": {"id": 20, "year": 2026, "name": "2026 Championship", "status": "IN_PROGRESS"},
            "round": {
                "id": 31,
                "seasonId": 20,
                "roundNumber": 15,
                "grandPrixName": "Example Grand Prix",
                "circuitName": "Example Circuit",
                "country": "Exampleland",
                "locality": "Example City",
                "startDate": "2026-09-01",
                "endDate": "2026-09-03",
                "status": "SCHEDULED",
            },
            "sessions": [
                {"meetingKey": 1293, "sessionKey": 1001, "sessionName": "Practice 1", "sessionType": "Practice", "gamedayId": 1174, "startDateUtc": "2026-09-01T00:00:00Z", "endDateUtc": "2026-09-01T01:00:00Z", "status": "FINISHED"},
                {"meetingKey": 1293, "sessionKey": 1002, "sessionName": "Qualifying", "sessionType": "Qualifying", "gamedayId": 1174, "startDateUtc": "2026-09-02T00:00:00Z", "endDateUtc": "2026-09-02T01:00:00Z", "status": "SCHEDULED"},
            ],
            "options": [
                {"optionId": 4401, "optionNo": 0, "optionText": "Driver A", "points": 10, "chance": 0.5},
                {"optionId": 4402, "optionNo": 1, "optionText": "Driver B", "points": 5, "chance": 0.5},
            ],
        },
        {"sourceQuestionId": 71, "sourceSnapshotId": 81, "sourceRoundId": 31},
    )


def test_schema_plan_is_fixed_and_hashes_only_approved_scripts(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    plan = schema_script_plan(schema_dir)
    assert tuple(name for name, _ in plan) == SCHEMA_FILES
    assert all(len(digest) == 64 for _, digest in plan)
    assert "005_answer_scoring.sql" not in dict(plan)
    assert "099_unknown.sql" not in dict(plan)


def test_target_requires_concrete_loopback_project_and_distinct_resource_ids(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    target = _target(schema_dir)
    invalid = replace(target, target_resource_id=target.source_resource_id)
    with pytest.raises(ValueError, match="distinct"):
        IsolatedLoader(FakeConnection(), invalid, schema_root=schema_dir)


def test_schema_mismatch_fails_before_any_business_dml(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    connection.cursor = lambda: FakeCursorWithoutRequiredUnique(connection)  # type: ignore[method-assign]
    with pytest.raises(ValueError, match="unique keys"):
        loader.load(source, identity)
    assert not any(sql.startswith(("INSERT", "UPDATE", "DELETE")) for sql in connection.executed)
    assert connection.commits == 0


class FakeCursorWithoutRequiredUnique(FakeCursor):
    def execute(self, sql: str, params: tuple[Any, ...] | None = None) -> None:
        if "information_schema.STATISTICS" in sql:
            self.executed.append(sql)
            self.connection.executed.append(sql)
            self.rows = []
            return
        super().execute(sql, params)


def test_partial_business_database_is_blocked_and_rolled_back(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeConnection(counts={"question": 2})
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    with pytest.raises(ValueError, match="not an exact replay"):
        loader.load(source, identity)
    assert connection.rollbacks == 1
    assert connection.commits == 0
    assert not any(sql.startswith(("INSERT", "UPDATE", "DELETE")) for sql in connection.executed)


def test_unknown_source_fields_are_rejected_without_sql(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeConnection()
    source, identity = _source()
    source["question"]["answer"] = "must not be imported"
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    with pytest.raises(ValueError, match="unknown or missing"):
        loader.load(source, identity)
    assert connection.executed == []


@pytest.mark.parametrize(
    "invalid_case",
    (
        "too-many-sessions",
        "duplicate-session-key",
        "wrong-option-count",
        "duplicate-option-id",
        "duplicate-option-no",
        "wrong-choice-limit",
    ),
)
def test_invalid_source_cardinality_and_options_fail_before_sql(
    tmp_path: Path, invalid_case: str
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeConnection()
    source, identity = _source()
    if invalid_case == "too-many-sessions":
        source["sessions"] = [
            {**source["sessions"][0], "sessionKey": 2000 + index}
            for index in range(65)
        ]
    elif invalid_case == "duplicate-session-key":
        source["sessions"][1]["sessionKey"] = source["sessions"][0]["sessionKey"]
    elif invalid_case == "wrong-option-count":
        source["options"].pop()
    elif invalid_case == "duplicate-option-id":
        source["options"][1]["optionId"] = source["options"][0]["optionId"]
    elif invalid_case == "duplicate-option-no":
        source["options"][1]["optionNo"] = source["options"][0]["optionNo"]
    elif invalid_case == "wrong-choice-limit":
        source["snapshot"]["raw"]["Config"]["ChoiceLimit"] = 2

    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    with pytest.raises(ValueError):
        loader.load(source, identity)
    assert connection.executed == []


def test_loader_never_executes_schema_scripts(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeConnection(counts={"question": 2})
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    with pytest.raises(ValueError):
        loader.load(source, identity)
    assert not any("CREATE TABLE" in sql or "ALTER TABLE" in sql for sql in connection.executed)


def test_mvp_templates_keep_isolated_loopback_and_required_secret_gates() -> None:
    repo_root = Path(__file__).resolve().parents[3]
    compose = (repo_root / "deploy/real-data-mvp/compose.yaml").read_text(encoding="utf-8")
    application = (repo_root / "deploy/real-data-mvp/application-real-data-mvp.yaml").read_text(encoding="utf-8")
    for variable in ("MVP_MYSQL_ROOT_PASSWORD", "MVP_MYSQL_USER", "MVP_MYSQL_PASSWORD", "MVP_RABBIT_USER", "MVP_RABBIT_PASSWORD"):
        assert f"${{{variable}:?" in compose
    for variable in ("MVP_MYSQL_USER", "MVP_MYSQL_PASSWORD", "MVP_RABBIT_USER", "MVP_RABBIT_PASSWORD"):
        assert f"${{{variable}}}" in application
        assert f"${{{variable}:" not in application
    assert '"127.0.0.1:13316:3306"' in compose
    assert '"127.0.0.1:15683:5672"' in compose
    assert "org.f1predict.scope: isolated-real-data-mvp" in compose
    project_reference = "real-data-mvp-${MVP_PROJECT_NAME:?"
    assert compose.count(project_reference) == 3
    assert "name: " + project_reference in compose
    assert "org.f1predict.project: " + project_reference in compose
    assert "real_data_mvp_mysql" in compose and "real_data_mvp_rabbit" in compose
    assert "name:" not in compose.split("volumes:\n", 1)[1]
    assert "virtual-host: /f1predict-mvp" in application
    assert "enabled: false" in application
    assert "publisher-confirm-type: correlated" in application
    assert "publisher-returns: true" in application
    assert "enabled: true" in application
    assert "base-url: http://127.0.0.1:1" in application
    assert "question-status-mapping" not in application


def test_loader_returns_database_allocated_ids_and_feed_option_ids(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)

    mapping = loader.load(source, identity)

    assert mapping == {
        "questionId": 935,
        "snapshotId": 942,
        "roundId": 914,
        "optionIds": {"4401": 4401, "4402": 4402},
    }
    assert mapping["questionId"] != source["question"]["id"]
    assert mapping["optionIds"]["4401"] == 4401
    assert connection.commits == 1
    assert connection.rows["question"][0]["source_question_id"] == 902
    assert connection.rows["question"][0]["status"] == "OPEN"
    assert source["question"]["id"] != source["question"]["sourceQuestionId"]
    assert loader.last_audit == {
        "sourceQuestionId": 71,
        "sourceFeedQuestionId": 902,
        "sourceStatus": "2",
        "sourceSnapshotId": 81,
        "sourceSnapshotHash": "a" * 64,
    }


def test_loader_source_captures_and_seals_without_rewriting_source_status(tmp_path: Path) -> None:
    schema_root = tmp_path / "schema"
    schema_root.mkdir()
    _schema_dir(schema_root)
    source, loader_identity = _source()
    connection = FakeImportConnection()
    loader = IsolatedLoader(connection, _target(schema_root), schema_root=schema_root)

    mapping = loader.load(source, loader_identity)

    assert mapping["questionId"] != source["question"]["id"]
    assert source["question"]["id"] != source["question"]["sourceQuestionId"]
    assert source["question"]["status"] == "2"
    assert source["question"]["firstSeenAt"] == "2026-09-01T00:00:00Z"
    assert connection.rows["question"][0]["source_question_id"] == 902
    assert loader.last_audit == {
        "sourceQuestionId": 71,
        "sourceFeedQuestionId": 902,
        "sourceStatus": "2",
        "sourceSnapshotId": 81,
        "sourceSnapshotHash": "a" * 64,
    }

    bundle_identity = {
        "sourceQuestionId": source["question"]["id"],
        "sourceSnapshotId": source["snapshot"]["id"],
        "sourceRoundId": source["round"]["id"],
        "questionTextHash": canonical_sha256(source["snapshot"]["raw"]["Text"]),
        "optionTextHashes": {
            str(option["optionId"]): canonical_sha256(option["optionText"])
            for option in source["options"]
        },
        "optionDrivers": {"4401": 10, "4402": 43},
        "meetingIdentity": {"mysqlMeetingKey": 1293, "mongoMeetingKey": 1293},
        "sessions": [{"sessionKey": 1001, "type": "Practice"}],
        "drivers": [
            {"driverNumber": 10, "name": "Driver A"},
            {"driverNumber": 43, "name": "Driver B"},
        ],
        "evidenceHashes": {"sessionCatalog": "c" * 64},
    }
    event_start = datetime(2026, 9, 1, 0, 0, tzinfo=UTC)
    raw_rows = [
        {
            "_key": f"lap-{driver_number}-{lap_number}",
            "meeting_key": 1293,
            "session_key": 1001,
            "driver_number": driver_number,
            "lap_number": lap_number,
            "date_start": event_start + timedelta(seconds=(lap_number - 1) * 90),
            "lap_duration": 87.0,
            "duration_sector_1": 29.0,
            "duration_sector_2": 29.0,
            "duration_sector_3": 29.0,
            "is_pit_out_lap": False,
        }
        for driver_number in (10, 43)
        for lap_number in (1, 2, 3)
    ]
    clocks = iter(
        (
            datetime(2026, 9, 1, 0, 10, tzinfo=UTC),
            datetime(2026, 9, 1, 0, 11, tzinfo=UTC),
        )
    )
    capture = capture_staging(
        tmp_path,
        source,
        raw_rows,
        bundle_identity,
        datetime(2026, 9, 1, 0, 5, tzinfo=UTC),
        clock=lambda: next(clocks),
    )
    sealed = seal_bundle(capture, tmp_path / "sealed", mapping)
    provenance = json.loads((sealed.manifest_path.parent / "source_provenance.json").read_text())
    assert provenance["source"]["question"]["status"] == "2"
    assert provenance["source"]["question"]["firstSeenAt"] == "2026-09-01T00:00:00Z"

    context = ReplayContext.load(
        str(sealed.manifest_path),
        str(sealed.manifest_path.parent / "fixture.json"),
        str(sealed.manifest_path.parent / "policy.json"),
        model_mode="stub",
        expected_manifest_hash=sealed.manifest_hash,
    )
    assert context.isolated_question_id == mapping["questionId"]
    assert context.isolated_snapshot_id == mapping["snapshotId"]


def test_ambiguous_commit_recovers_only_after_complete_database_readback(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection(ambiguous_commit=True)
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)

    mapping = loader.load(source, identity)

    assert mapping["questionId"] == 935
    assert mapping["snapshotId"] == 942
    assert mapping["optionIds"] == {"4401": 4401, "4402": 4402}
    assert connection.commits == 1
    assert connection.rollbacks == 1
    assert len(connection.cursors) == 3
    assert all(cursor.closed for cursor in connection.cursors)


def test_existing_exact_import_is_recovered_without_duplicate_dml(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    first = loader.load(source, identity)
    insert_count = sum(sql.startswith("INSERT") for sql in connection.executed)

    replayed = loader.load(source, identity)

    assert replayed == first
    assert sum(sql.startswith("INSERT") for sql in connection.executed) == insert_count
    assert connection.commits == 2


def test_existing_source_conflict_is_not_overwritten(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    loader.load(source, identity)
    inserts_before = sum(sql.startswith("INSERT") for sql in connection.executed)
    changed, _ = _source()
    changed["snapshot"]["contentHash"] = "b" * 64

    with pytest.raises(ValueError, match="conflicts"):
        loader.load(changed, identity)

    assert sum(sql.startswith("INSERT") for sql in connection.executed) == inserts_before
    assert connection.rollbacks == 1


@pytest.mark.parametrize(
    ("field", "conflict"),
    (
        ("created_at", datetime(2026, 9, 1, 1, 0, tzinfo=UTC)),
        ("snapshot_reason", "CONTENT_CHANGED"),
        ("snapshot_no", 2),
    ),
)
def test_existing_snapshot_metadata_conflict_is_not_recovered(
    tmp_path: Path, field: str, conflict: Any
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    loader.load(source, identity)
    connection.rows["question_snapshot"][0][field] = conflict
    connection.committed_rows = deepcopy(connection.rows)
    rows_before = deepcopy(connection.rows)
    cursors_before = len(connection.cursors)
    statements_before = len(connection.executed)

    with pytest.raises(ValueError):
        loader.load(source, identity)

    assert connection.commits == 1
    assert connection.rollbacks == 1
    assert connection.rows == rows_before
    assert len(connection.cursors) == cursors_before + 2
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is None
    # 编号冲突可能先尝试插入，但必须回滚，且不能再写一次以挽救失败。
    assert sum(
        sql.startswith("INSERT") for sql in connection.executed[statements_before:]
    ) == (3 if field == "snapshot_no" else 0)


@pytest.mark.parametrize("failure", ("value-error", "os-error", "final-count"))
def test_precommit_failure_preserves_error_without_readback_recovery(
    tmp_path: Path, failure: str
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    loader.load(source, identity)
    rows_before = deepcopy(connection.rows)
    cursors_before = len(connection.cursors)
    statements_before = len(connection.executed)
    error = None
    if failure == "final-count":
        connection.final_count_mismatch = "season"
        error_type, message = ValueError, "contains unrelated rows"
    else:
        error_type = ValueError if failure == "value-error" else OSError
        error = error_type("precommit database operation failed")
        message = str(error)
        connection.execute_error = ("UPDATE question SET latest_snapshot_id", error)

    with pytest.raises(error_type, match=message) as caught:
        loader.load(source, identity)

    if error is not None:
        assert caught.value is error
    assert connection.commits == 1
    assert connection.rollbacks == 1
    assert connection.rows == rows_before
    assert len(connection.cursors) == cursors_before + 2
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is None
    assert [
        sql for sql in connection.executed[statements_before:]
        if sql.startswith(("INSERT", "UPDATE", "DELETE"))
    ] == ["UPDATE question SET latest_snapshot_id = %s WHERE id = %s AND latest_snapshot_id IS NULL"]


@pytest.mark.parametrize("durable", (False, True))
def test_commit_error_with_failed_rollback_never_recovers_local_rows(
    tmp_path: Path, durable: bool
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    error = OSError("commit result is unknown")
    connection.commit_error = error
    connection.persist_on_commit = durable
    connection.rollback_error = OSError("cannot end local transaction")

    with pytest.raises(OSError, match="commit result is unknown") as caught:
        loader.load(source, identity)

    assert caught.value is error
    assert connection.commits == 1
    assert connection.rollbacks == 1
    assert len(connection.cursors) == 2
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is None
    assert bool(connection.committed_rows["question"]) is durable


@pytest.mark.parametrize("failure", ("source-validation", "schema-query"))
def test_failed_validation_clears_previous_success_audit(
    tmp_path: Path, failure: str
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    loader.load(source, identity)
    assert loader.last_audit is not None
    statements_before = len(connection.executed)
    if failure == "source-validation":
        source["question"]["answer"] = "must not be imported"
        error_type, message = ValueError, "unknown or missing"
    else:
        error_type, message = OSError, "schema query failed"
        connection.execute_error = ("SELECT TABLE_NAME FROM information_schema.TABLES", OSError(message))

    with pytest.raises(error_type, match=message):
        loader.load(source, identity)

    assert loader.last_audit is None
    assert all(cursor.closed for cursor in connection.cursors)
    assert connection.commits == 1
    assert not any(
        sql.startswith(("INSERT", "UPDATE", "DELETE"))
        for sql in connection.executed[statements_before:]
    )


def test_commit_error_without_durable_rows_propagates_original_error(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    error = OSError("commit was not persisted")
    connection.commit_error = error
    connection.persist_on_commit = False

    with pytest.raises(OSError, match="commit was not persisted") as caught:
        loader.load(source, identity)

    assert caught.value is error
    assert connection.commits == 1
    assert connection.rollbacks == 1
    assert not any(connection.rows.values())
    assert len(connection.cursors) == 3
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is None
    assert sum(sql.startswith("INSERT") for sql in connection.executed) == 8


@pytest.mark.parametrize(
    ("table", "field", "conflict"),
    (
        ("question_snapshot", "snapshot_no", 2),
        ("question_snapshot", "snapshot_reason", "CONTENT_CHANGED"),
        ("question_snapshot", "created_at", datetime(2026, 9, 1, 1, 0, tzinfo=UTC)),
        ("question_snapshot", "content_hash", "b" * 64),
        ("question_snapshot", "raw_json", '{"Text":"different content"}'),
        ("season", "start_date", date(2026, 1, 1)),
        ("season", "end_date", date(2026, 12, 31)),
    ),
)
def test_commit_error_with_conflicting_durable_metadata_is_not_recovered(
    tmp_path: Path, table: str, field: str, conflict: Any
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    loader.load(source, identity)
    cursors_before = len(connection.cursors)
    statements_before = len(connection.executed)
    error = OSError("commit response lost after durable commit")
    connection.commit_error = error
    connection.commit_change = (table, field, conflict)

    with pytest.raises(OSError, match="commit response lost") as caught:
        loader.load(source, identity)

    assert caught.value is error
    assert connection.commits == 2
    assert connection.rollbacks == 1
    assert connection.rows[table][0][field] == conflict
    assert len(connection.cursors) == cursors_before + 3
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is None
    assert [
        sql for sql in connection.executed[statements_before:]
        if sql.startswith(("INSERT", "UPDATE", "DELETE"))
    ] == ["UPDATE question SET latest_snapshot_id = %s WHERE id = %s AND latest_snapshot_id IS NULL"]


@pytest.mark.parametrize(
    ("field", "equivalent"),
    (
        ("raw_json", ' {"Text": "Who wins?", "SubText": null, "OptionTemplateId": 1, "Config": {"ChoiceLimit": 1}} '),
        ("raw_json", {"Text": "Who wins?", "SubText": None, "OptionTemplateId": 1, "Config": {"ChoiceLimit": 1}}),
        ("created_at", datetime.fromisoformat("2026-09-01T08:00:00+08:00")),
    ),
)
def test_exact_replay_accepts_semantic_json_and_equivalent_utc_time(
    tmp_path: Path, field: str, equivalent: Any
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    mapping = loader.load(source, identity)
    connection.rows["question_snapshot"][0][field] = equivalent
    connection.committed_rows = deepcopy(connection.rows)
    inserts_before = sum(sql.startswith("INSERT") for sql in connection.executed)

    assert loader.load(source, identity) == mapping

    assert sum(sql.startswith("INSERT") for sql in connection.executed) == inserts_before
    assert connection.commits == 2
    assert connection.rollbacks == 0
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is not None


@pytest.mark.parametrize(
    ("field", "equivalent"),
    (
        ("raw_json", ' {"Text": "Who wins?", "SubText": null, "OptionTemplateId": 1, "Config": {"ChoiceLimit": 1}} '),
        ("raw_json", {"Text": "Who wins?", "SubText": None, "OptionTemplateId": 1, "Config": {"ChoiceLimit": 1}}),
        ("created_at", datetime.fromisoformat("2026-09-01T08:00:00+08:00")),
    ),
)
def test_ambiguous_durable_commit_accepts_semantic_json_and_equivalent_utc_time(
    tmp_path: Path, field: str, equivalent: Any
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection(ambiguous_commit=True)
    connection.commit_change = ("question_snapshot", field, equivalent)
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)

    assert loader.load(source, identity) == {
        "questionId": 935,
        "snapshotId": 942,
        "roundId": 914,
        "optionIds": {"4401": 4401, "4402": 4402},
    }

    assert connection.commits == 1
    assert connection.rollbacks == 1
    assert len(connection.cursors) == 3
    assert all(cursor.closed for cursor in connection.cursors)
    assert sum(sql.startswith("INSERT") for sql in connection.executed) == 8
    assert loader.last_audit == {
        "sourceQuestionId": 71,
        "sourceFeedQuestionId": 902,
        "sourceStatus": "2",
        "sourceSnapshotId": 81,
        "sourceSnapshotHash": "a" * 64,
    }


@pytest.mark.parametrize("field", ("start_date", "end_date"))
def test_existing_season_dates_conflict_is_not_recovered(
    tmp_path: Path, field: str
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    loader.load(source, identity)
    connection.rows["season"][0][field] = date(2026, 1, 1)
    connection.committed_rows = deepcopy(connection.rows)
    statements_before = len(connection.executed)
    cursors_before = len(connection.cursors)

    with pytest.raises(ValueError, match="existing season row conflicts"):
        loader.load(source, identity)

    assert connection.commits == 1
    assert connection.rollbacks == 1
    assert len(connection.cursors) == cursors_before + 2
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is None
    assert not any(
        sql.startswith(("INSERT", "UPDATE", "DELETE"))
        for sql in connection.executed[statements_before:]
    )


def test_failed_commit_readback_preserves_original_error_and_closes_cursors(tmp_path: Path) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    error = OSError("commit response lost after durable commit")
    connection.commit_error = error
    connection.execute_error = (
        "SELECT id, latest_snapshot_id, round_id", OSError("readback query failed")
    )

    with pytest.raises(OSError, match="commit response lost") as caught:
        loader.load(source, identity)

    assert caught.value is error
    assert connection.execute_error is None
    assert connection.commits == 1
    assert connection.rollbacks == 1
    assert len(connection.cursors) == 3
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is None
    assert sum(sql.startswith("INSERT") for sql in connection.executed) == 8


@pytest.mark.parametrize("mode", ("replay", "commit-recovery"))
@pytest.mark.parametrize("field", ("choice-limit", "option-template"))
@pytest.mark.parametrize("representation", ("text", "mapping"))
def test_snapshot_json_boolean_number_conflict_is_rejected(
    tmp_path: Path, mode: str, field: str, representation: str
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    loader.load(source, identity)
    raw = deepcopy(source["snapshot"]["raw"])
    if field == "choice-limit":
        raw["Config"]["ChoiceLimit"] = True
    else:
        raw["OptionTemplateId"] = True
    conflict = json.dumps(raw) if representation == "text" else raw
    cursors_before = len(connection.cursors)
    statements_before = len(connection.executed)
    if mode == "replay":
        connection.rows["question_snapshot"][0]["raw_json"] = conflict
        connection.committed_rows = deepcopy(connection.rows)
        error_type, message = ValueError, "existing question_snapshot row conflicts"
        error = None
    else:
        error = OSError("commit response lost with conflicting JSON")
        connection.commit_error = error
        connection.commit_change = ("question_snapshot", "raw_json", conflict)
        error_type, message = OSError, "conflicting JSON"

    with pytest.raises(error_type, match=message) as caught:
        loader.load(source, identity)

    if error is not None:
        assert caught.value is error
    assert connection.commits == (1 if mode == "replay" else 2)
    assert connection.rollbacks == 1
    assert connection.rows["question_snapshot"][0]["raw_json"] == conflict
    assert len(connection.cursors) == cursors_before + (2 if mode == "replay" else 3)
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is None
    dml = [sql for sql in connection.executed[statements_before:]
           if sql.startswith(("INSERT", "UPDATE", "DELETE"))]
    assert dml == ([] if mode == "replay" else [
        "UPDATE question SET latest_snapshot_id = %s WHERE id = %s AND latest_snapshot_id IS NULL"
    ])


@pytest.mark.parametrize("mode", ("replay", "commit-recovery"))
@pytest.mark.parametrize("representation", ("text", "mapping"))
def test_snapshot_json_finite_number_representations_remain_equivalent(
    tmp_path: Path, mode: str, representation: str
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    mapping = loader.load(source, identity)
    raw = deepcopy(source["snapshot"]["raw"])
    raw["Config"]["ChoiceLimit"] = 1.0
    raw["OptionTemplateId"] = 1.0
    equivalent = json.dumps(raw, indent=2) if representation == "text" else raw
    if mode == "replay":
        connection.rows["question_snapshot"][0]["raw_json"] = equivalent
        connection.committed_rows = deepcopy(connection.rows)
    else:
        connection.commit_error = OSError("commit response lost after durable commit")
        connection.commit_change = ("question_snapshot", "raw_json", equivalent)
    inserts_before = sum(sql.startswith("INSERT") for sql in connection.executed)

    assert loader.load(source, identity) == mapping

    assert connection.commits == 2
    assert connection.rollbacks == (0 if mode == "replay" else 1)
    assert sum(sql.startswith("INSERT") for sql in connection.executed) == inserts_before
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is not None


@pytest.mark.parametrize("mode", ("replay", "commit-recovery"))
@pytest.mark.parametrize("representation", ("text", "mapping"))
@pytest.mark.parametrize("invalid_number", (float("nan"), float("inf"), float("-inf")))
def test_snapshot_json_nonfinite_numbers_are_rejected(
    tmp_path: Path, mode: str, representation: str, invalid_number: float
) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    loader.load(source, identity)
    raw = deepcopy(source["snapshot"]["raw"])
    raw["OptionTemplateId"] = invalid_number
    invalid = json.dumps(raw) if representation == "text" else raw
    if mode == "replay":
        connection.rows["question_snapshot"][0]["raw_json"] = invalid
        connection.committed_rows = deepcopy(connection.rows)
        error_type, message = ValueError, "JSON"
        error = None
    else:
        error = OSError("commit response lost with nonfinite JSON")
        connection.commit_error = error
        connection.commit_change = ("question_snapshot", "raw_json", invalid)
        error_type, message = OSError, "nonfinite JSON"

    with pytest.raises(error_type, match=message) as caught:
        loader.load(source, identity)

    if error is not None:
        assert caught.value is error
    assert connection.commits == (1 if mode == "replay" else 2)
    assert connection.rollbacks == 1
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is None


@pytest.mark.parametrize(
    ("left", "right"),
    (
        (True, 1),
        (False, 0),
        ("1", 1),
        (None, "null"),
        ([], {}),
        ({"x": None}, {}),
        ([True], [1]),
        ({"x": [False]}, {"x": [0]}),
        ({"x": "true"}, {"x": True}),
    ),
)
def test_snapshot_json_normalization_preserves_nested_types(left: Any, right: Any) -> None:
    # 辅助比较覆盖 JSON 种类；公开装载两路径由上面的真实 Fake SQL 回归守护。
    def normalized(value: Any) -> tuple[Any, ...]:
        return _normalize_row("question_snapshot", (1, "a" * 64, {"value": value}, "INITIAL", None))

    assert normalized(left) != normalized(right)
    assert normalized(right) != normalized(left)


@pytest.mark.parametrize("mode", ("replay", "commit-recovery"))
def test_snapshot_json_numeric_precision_conflict_is_rejected(tmp_path: Path, mode: str) -> None:
    schema_dir = _schema_dir(tmp_path)
    connection = FakeImportConnection()
    source, identity = _source()
    loader = IsolatedLoader(connection, _target(schema_dir), schema_root=schema_dir)
    loader.load(source, identity)
    conflict = json.dumps(source["snapshot"]["raw"]).replace(
        '"ChoiceLimit": 1', '"ChoiceLimit": 1.000000000000000000000001'
    )
    if mode == "replay":
        connection.rows["question_snapshot"][0]["raw_json"] = conflict
        connection.committed_rows = deepcopy(connection.rows)
        error_type, message = ValueError, "existing question_snapshot row conflicts"
        error = None
    else:
        error = OSError("commit response lost with numeric precision conflict")
        connection.commit_error = error
        connection.commit_change = ("question_snapshot", "raw_json", conflict)
        error_type, message = OSError, "numeric precision conflict"

    with pytest.raises(error_type, match=message) as caught:
        loader.load(source, identity)

    if error is not None:
        assert caught.value is error
    assert connection.commits == (1 if mode == "replay" else 2)
    assert connection.rollbacks == 1
    assert all(cursor.closed for cursor in connection.cursors)
    assert loader.last_audit is None


@pytest.mark.parametrize(
    ("left", "right", "equivalent"),
    (
        ('{"v":1}', '{"v":1.0}', True),
        ('{"v":10000000000000000000001}', '{"v":10000000000000000000001.0}', True),
        ('{"v":1}', '{"v":1.000000000000000000000001}', False),
        ('{"v":9007199254740992}', '{"v":9007199254740993.0}', False),
    ),
)
def test_snapshot_json_numeric_precision_comparison(
    left: str, right: str, equivalent: bool
) -> None:
    left_row = _normalize_row("question_snapshot", (1, "a" * 64, left, "INITIAL", None))
    right_row = _normalize_row("question_snapshot", (1, "a" * 64, right, "INITIAL", None))
    assert (left_row == right_row) is equivalent
