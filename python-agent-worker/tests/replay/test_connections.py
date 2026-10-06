from __future__ import annotations

import pytest

from f1_predict.replay.connections import (
    ConnectionPlan,
    ConnectionRole,
    open_connection,
    wrap_source,
)


def test_connection_plan_has_no_side_effect_until_explicit_open():
    calls = []
    plan = ConnectionPlan(
        role=ConnectionRole.ISOLATED_DATABASE,
        host="127.0.0.1",
        port=13316,
        database="f1_ai_predict",
        project="real-data-mvp-local",
        connector_factory=lambda **kwargs: calls.append(kwargs) or object(),
    )
    assert calls == []
    assert plan.host == "127.0.0.1"


def test_source_plans_wrap_only_preinjected_read_adapters():
    from f1_predict.replay.source import MongoLapSource, MySqlReplaySource

    mysql_plan = ConnectionPlan(
        role=ConnectionRole.SOURCE_MYSQL, host="mysql.source.invalid", port=3306,
        database="f1_ai_predict", project="real-data-mvp-local", connector_factory=lambda **_: None,
    )
    mongo_plan = ConnectionPlan(
        role=ConnectionRole.SOURCE_MONGO, host="mongo.source.invalid", port=27017,
        database="archive_db", project="real-data-mvp-local", connector_factory=lambda **_: None,
        collection="laps",
    )
    assert mongo_plan.database == "archive_db"
    assert mongo_plan.collection == "laps"
    assert isinstance(wrap_source(mysql_plan, object()), MySqlReplaySource)

    selected = []

    class Cursor(list):
        def limit(self, value):
            assert value == 501
            return self

        def close(self):
            pass

    class Collection:
        def find(self, query, projection, *, max_time_ms):
            return Cursor()

    class Database:
        def get_collection(self, name):
            selected.append(name)
            return Collection()

    source = wrap_source(mongo_plan, Database())
    assert isinstance(source, MongoLapSource)
    from f1_predict.replay.export import read_laps_bounded

    assert read_laps_bounded(source, mongo_meeting_key=10, session_keys=(1,), driver_numbers=(44,)) == ()
    assert selected == ["laps"]


def test_source_mongo_connection_uses_archive_database_not_collection_name():
    calls = []
    plan = ConnectionPlan(
        role=ConnectionRole.SOURCE_MONGO, host="mongo.source.invalid", port=27017,
        database="archive_db", project="real-data-mvp-local",
        connector_factory=lambda **kwargs: calls.append(kwargs) or "fake-database", collection="laps",
    )
    assert open_connection(plan, permission=object(), permission_check=lambda *_: True) == "fake-database"
    assert calls == [{"host": "mongo.source.invalid", "port": 27017, "database": "archive_db",
                      "project": "real-data-mvp-local", "role": "source_mongo", "read_only": True}]
    with pytest.raises(ValueError, match="不是 laps 集合名"):
        ConnectionPlan(ConnectionRole.SOURCE_MONGO, "mongo.source.invalid", 27017,
                       "laps", "real-data-mvp-local", lambda **_: None, collection="laps")


def test_open_requires_external_permission_before_factory_invocation():
    calls = []
    plan = ConnectionPlan(
        role=ConnectionRole.ISOLATED_DATABASE,
        host="127.0.0.1",
        port=13316,
        database="f1_ai_predict",
        project="real-data-mvp-local",
        connector_factory=lambda **kwargs: calls.append(kwargs) or object(),
    )
    with pytest.raises(PermissionError):
        open_connection(plan)
    assert calls == []


def test_explicit_open_passes_only_planned_fields_and_hides_credentials():
    calls = []
    secret = "sentinel-private-password"
    plan = ConnectionPlan(
        role=ConnectionRole.ISOLATED_DATABASE,
        host="127.0.0.1",
        port=13316,
        database="f1_ai_predict",
        project="real-data-mvp-local",
        username="user",
        password=secret,
        connector_factory=lambda **kwargs: calls.append(kwargs) or "connected-fake",
    )
    assert secret not in repr(plan)
    assert open_connection(plan, permission=object(), permission_check=lambda *_: True) == "connected-fake"
    assert calls == [{"host": "127.0.0.1", "port": 13316, "database": "f1_ai_predict",
                      "project": "real-data-mvp-local", "role": "isolated_database",
                      "username": "user", "password": secret}]


def test_connector_exception_does_not_disclose_secret():
    secret = "sentinel-private-password"
    plan = ConnectionPlan(
        role=ConnectionRole.ISOLATED_DATABASE, host="127.0.0.1", port=13316,
        database="f1_ai_predict", project="real-data-mvp-local", username="user",
        password=secret,
        connector_factory=lambda **_: (_ for _ in ()).throw(RuntimeError(secret)),
    )
    with pytest.raises(RuntimeError, match="外部连接器操作失败") as error:
        open_connection(plan, permission=object(), permission_check=lambda *_: True)
    assert secret not in str(error.value)


def test_isolated_database_must_use_loopback_and_fixed_identity():
    with pytest.raises(ValueError):
        ConnectionPlan(ConnectionRole.ISOLATED_DATABASE, "db.example", 23306,
                       "f1_ai_predict", "real-data-mvp-local", lambda **_: None)
    with pytest.raises(ValueError):
        ConnectionPlan(ConnectionRole.ISOLATED_DATABASE, "127.0.0.1", 23306,
                       "other", "real-data-mvp-local", lambda **_: None)
