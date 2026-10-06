from __future__ import annotations

from f1_predict.replay.runtime import RuntimeAuthorization, RuntimeTarget


class FakeSystem:
    def __init__(self):
        self.calls = []

    def resource_identity(self):
        self.calls.append(("resource_identity",))
        return {
            "project": "real-data-mvp-local", "scope": "isolated-real-data-mvp",
            "dockerContext": "local", "database": "f1_ai_predict", "sourceShared": False,
            "services": {
                "mysql": {"project": "real-data-mvp-local", "service": "mysql",
                          "scope": "isolated-real-data-mvp", "bindings": [{"host": "127.0.0.1", "port": 13316}]},
                "rabbitmq": {"project": "real-data-mvp-local", "service": "rabbitmq",
                             "scope": "isolated-real-data-mvp", "bindings": [
                                 {"host": "127.0.0.1", "port": 15683},
                                 {"host": "127.0.0.1", "port": 15682}]},
            },
        }

    def table_count(self):
        self.calls.append(("table_count",))
        return 0


def test_runtime_target_does_not_probe_until_public_method_is_called():
    fake = FakeSystem()
    auth = RuntimeAuthorization(project="real-data-mvp-local", scope="isolated-real-data-mvp")
    target = RuntimeTarget(fake, project="real-data-mvp-local", authorization=auth,
                           permission_check=lambda *_: True)
    assert fake.calls == []
    assert target.resource_identity()["project"] == "real-data-mvp-local"
    assert fake.calls == [("resource_identity",)]


def test_mutating_operations_require_separate_authorization_before_backend():
    fake = FakeSystem()
    target = RuntimeTarget(fake, project="real-data-mvp-local", authorization=None)
    try:
        target.table_count()
    except PermissionError:
        pass
    else:
        raise AssertionError("external operation must fail closed")
    assert fake.calls == []


def test_authorization_is_explicit_and_project_bound():
    fake = FakeSystem()
    auth = RuntimeAuthorization(project="real-data-mvp-local", scope="isolated-real-data-mvp")
    target = RuntimeTarget(fake, project="real-data-mvp-local", authorization=auth,
                           permission_check=lambda *_: True)
    assert target.table_count() == 0
    assert fake.calls == [("table_count",)]


def test_post_batch_rejects_open_question_selector_and_unknown_fields():
    import pytest

    from f1_predict.replay.workflow import WorkflowBlocked

    target = RuntimeTarget(FakeSystem(), project="real-data-mvp-local", authorization=None)
    for payload in (
        {"questionIds": [1], "allOpenQuestions": True},
        {"questionIds": [0]},
        {"questionIds": [True]},
        {"questionIds": [1], "authorization": "sensitive"},
    ):
        with pytest.raises(WorkflowBlocked):
            target.post_batch(1, payload)


def test_load_rejects_empty_or_invalid_identifier_mapping():
    import pytest

    from f1_predict.replay.workflow import WorkflowBlocked

    class LoadSystem(FakeSystem):
        def load(self, *_):
            return {}

    auth = RuntimeAuthorization(project="real-data-mvp-local", scope="isolated-real-data-mvp")
    target = RuntimeTarget(LoadSystem(), project="real-data-mvp-local", authorization=auth,
                           permission_check=lambda *_: True)
    with pytest.raises(WorkflowBlocked):
        target.load({}, {})


def test_operation_journal_rejects_other_modes_and_persists_private_state(tmp_path):
    import stat

    import pytest

    from f1_predict.replay.runtime import OperationJournal
    from f1_predict.replay.workflow import WorkflowBlocked

    journal = OperationJournal(tmp_path / "journal", project="real-data-mvp-local",
                               resource_identity={"project": "real-data-mvp-local"})
    journal.save(stage="READY", sample=1)
    assert journal.load()["stage"] == "READY"
    reopened = OperationJournal(journal.directory, project="real-data-mvp-local",
                                resource_identity={"project": "real-data-mvp-local"})
    assert reopened.state["stage"] == "READY"
    reopened.save(stage="STOPPED")
    with pytest.raises(WorkflowBlocked):
        OperationJournal(journal.directory, project="real-data-mvp-local",
                         resource_identity={"project": "real-data-mvp-local"}).save(stage="READY")
    assert stat.S_IMODE(journal.path.stat().st_mode) == 0o600
    assert stat.S_IMODE(journal.directory.stat().st_mode) == 0o700
    journal.path.write_text('{"mode":"OFFLINE_SIMULATION"}')
    journal.path.chmod(0o600)
    with pytest.raises(WorkflowBlocked):
        journal.load()


def test_replay_runtime_runs_fake_operations_with_durable_identity_and_ownership(tmp_path):
    import hashlib
    import json

    import pytest

    from f1_predict.replay.runtime import OperationJournal, ReplayRuntime
    from f1_predict.replay.workflow import WorkflowBlocked

    class ReplayFake(FakeSystem):
        def __init__(self):
            super().__init__()
            self.processes = []

        def apply_schema(self, path, digest):
            self.calls.append(("apply_schema", path.name, digest))

        def inspect_schema(self):
            self.calls.append(("inspect_schema",))

        def post_batch(self, round_id, payload):
            self.calls.append(("post_batch", round_id, payload))
            return {"roundId": round_id, "questionCount": 1, "batchId": 42, "jobIds": ["job-42"]}

        def observe(self, job_id, batch_id):
            self.calls.append(("observe", job_id, batch_id))
            return {"workerAlive": True, "job": {"predictionJobId": job_id, "status": "SUCCEEDED"},
                    "batch": {"batchId": batch_id}, "outcome": {"predictionJobId": job_id},
                    "sql": {"predictionJobId": job_id, "batchId": batch_id},
                    "sqlite": {"predictionJobId": job_id}}

        def original_message(self, job_id):
            return {"messageId": "msg-42", "payload": {"schemaVersion": "2", "messageId": "msg-42",
                    "predictionJobId": job_id, "batchId": 42, "questionId": 7, "questionSnapshotId": 8,
                    "traceId": "trace-42", "dataCutoff": "2026-01-01T00:00:00Z", "modelVersion": "m1",
                    "promptVersion": "p1", "featureVersion": "f1", "embeddingVersion": None,
                    "retrieverVersion": None, "question": {"questionText": "Which driver?", "subText": None,
                    "questionType": "UNKNOWN", "optionTemplateId": None, "choiceLimit": 1,
                    "options": [{"optionId": 11, "optionNo": 0, "optionText": "A", "points": None, "chance": None},
                                {"optionId": 12, "optionNo": 1, "optionText": "B", "points": None, "chance": None}]},
                    "raceContext": {"seasonId": 1, "year": 2026, "roundId": 9, "roundNumber": 1,
                    "meetingKey": None, "sessionKey": None, "gamedayId": 7, "trackName": "Track"}}}

        def replay(self, message):
            self.calls.append(("replay", message))

        def restart_worker(self):
            self.calls.append(("restart_worker",))
            previous = list(self.processes)
            replacement = [{"project": item["project"], "runId": item["runId"],
                            "startToken": item["startToken"] + "-restart", "pid": item["pid"] + 1}
                           for item in previous]
            self.processes = replacement
            return {"previous": previous, "replacement": list(replacement)}

        def owned_processes(self):
            return list(self.processes)

        def stop_process(self, process):
            self.calls.append(("stop_process", process))
            self.processes.remove(process)

    fake = ReplayFake()
    project = "real-data-mvp-local"
    auth = RuntimeAuthorization(project=project, scope="isolated-real-data-mvp")
    target = RuntimeTarget(fake, project=project, authorization=auth, permission_check=lambda *_: True)
    identity = target.resource_identity()
    journal = OperationJournal(tmp_path / "journal", project=project, resource_identity=identity)
    runtime = ReplayRuntime(target, journal, mode="OFFLINE_ADAPTER_TEST")
    schema_dir = tmp_path / "schema"
    schema_dir.mkdir()
    names = ["001_create_database.sql", "002_season_round.sql", "003_question.sql",
             "004_prediction.sql", "006_sync.sql", "007_prediction_request_outbox.sql",
             "008_prediction_outcome.sql"]
    scripts = []
    for name in names:
        path = schema_dir / name
        path.write_text("-- approved fake schema " + name)
        scripts.append((path, hashlib.sha256(path.read_bytes()).hexdigest()))
    digest = hashlib.sha256(json.dumps([[path.name, value] for path, value in scripts],
                                      separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()

    assert runtime.prepare(scripts, digest)["stage"] == "READY"
    with pytest.raises(WorkflowBlocked):
        runtime.prepare(scripts, "0" * 64)
    payload = {"questionIds": [7], "dataCutoff": "2026-01-01T00:00:00Z", "modelVersion": "m1",
               "promptVersion": "p1", "featureVersion": "f1"}
    submission = runtime.submit(9, payload)
    assert submission["stage"] == "SUBMITTED"
    assert runtime.submit(9, dict(payload)) == submission
    with pytest.raises(WorkflowBlocked):
        runtime.submit(10, payload)
    assert sum(call[0] == "post_batch" for call in fake.calls) == 1
    assert runtime.observe("job-42", 42)["job"]["predictionJobId"] == "job-42"
    assert runtime.replay("job-42")["messageId"] == "msg-42"
    process = {"project": project, "runId": "run-1", "startToken": "token-1", "pid": 912}
    fake.processes.append(process)
    runtime.register_process(process)
    restarted = runtime.restart()
    assert restarted["stage"] == "RESTARTED"
    assert restarted["ownedProcesses"] == fake.processes
    assert runtime.stop_all()["stage"] == "STOPPED"
    assert fake.processes == []


def test_runtime_persists_uncertain_side_effects_and_never_retries(tmp_path):
    import hashlib
    import json

    import pytest

    from f1_predict.replay.runtime import OperationJournal, ReplayRuntime
    from f1_predict.replay.workflow import WorkflowBlocked

    class UncertainFake(FakeSystem):
        def __init__(self):
            super().__init__()
            self.posts = 0
            self.ddls = 0

        def apply_schema(self, *_):
            self.ddls += 1
            raise RuntimeError("private failure")

        def post_batch(self, *_):
            self.posts += 1
            raise RuntimeError("private response")

        def find_submission(self, round_id, payload):
            request = {"roundId": round_id, "payload": payload}
            digest = hashlib.sha256(json.dumps(request, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False).encode()).hexdigest()
            return {"requestHash": digest, "roundId": round_id, "questionCount": 1,
                    "batchId": 43, "jobIds": ["job-43"]}

    fake = UncertainFake()
    project = "real-data-mvp-local"
    auth = RuntimeAuthorization(project=project, scope="isolated-real-data-mvp")
    target = RuntimeTarget(fake, project=project, authorization=auth, permission_check=lambda *_: True)
    journal = OperationJournal(tmp_path / "uncertain", project=project, resource_identity=target.resource_identity())
    runtime = ReplayRuntime(target, journal, mode="OFFLINE_ADAPTER_TEST")
    schema_dir = tmp_path / "schema"
    schema_dir.mkdir()
    scripts = []
    for name in ("001_create_database.sql", "002_season_round.sql", "003_question.sql",
                 "004_prediction.sql", "006_sync.sql", "007_prediction_request_outbox.sql",
                 "008_prediction_outcome.sql"):
        path = schema_dir / name
        path.write_text(name)
        scripts.append((path, hashlib.sha256(path.read_bytes()).hexdigest()))
    digest = hashlib.sha256(json.dumps([[path.name, value] for path, value in scripts],
                                      separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
    with pytest.raises(WorkflowBlocked):
        runtime.prepare(scripts, digest)
    assert journal.state["stage"] == "SCHEMA_UNCERTAIN"
    with pytest.raises(WorkflowBlocked):
        runtime.prepare(scripts, digest)
    assert fake.ddls == 1

    # 使用另一条离线运行测试 POST 响应丢失，不允许同一意图再次发送。
    submit_journal = OperationJournal(tmp_path / "post-uncertain", project=project,
                                      resource_identity=target.resource_identity())
    submit_journal.save(stage="READY")
    submit_runtime = ReplayRuntime(target, submit_journal, mode="OFFLINE_ADAPTER_TEST")
    payload = {"questionIds": [7], "dataCutoff": "2026-01-01T00:00:00Z", "modelVersion": "m1",
               "promptVersion": "p1", "featureVersion": "f1"}
    with pytest.raises(WorkflowBlocked):
        submit_runtime.submit(9, payload)
    assert submit_journal.state["stage"] == "SUBMISSION_UNCERTAIN"
    with pytest.raises(WorkflowBlocked):
        submit_runtime.submit(9, payload)
    assert fake.posts == 1
    assert submit_runtime.verify_submission()["stage"] == "SUBMITTED"
    assert submit_journal.state["jobId"] == "job-43"
    with pytest.raises(WorkflowBlocked):
        runtime.submit(9, payload)
    assert fake.posts == 1


def test_stop_all_checks_full_inventory_before_stopping_any_process(tmp_path):
    import pytest

    from f1_predict.replay.runtime import OperationJournal, ReplayRuntime
    from f1_predict.replay.workflow import WorkflowBlocked

    class ProcessFake(FakeSystem):
        def __init__(self):
            super().__init__()
            self.processes = []
            self.stopped = []

        def owned_processes(self):
            return list(self.processes)

        def stop_process(self, process):
            self.stopped.append(process)

    fake = ProcessFake()
    project = "real-data-mvp-local"
    auth = RuntimeAuthorization(project=project, scope="isolated-real-data-mvp")
    target = RuntimeTarget(fake, project=project, authorization=auth, permission_check=lambda *_: True)
    runtime = ReplayRuntime(target, OperationJournal(tmp_path / "processes", project=project,
                                                     resource_identity=target.resource_identity()),
                           mode="OFFLINE_ADAPTER_TEST")
    expected = {"project": project, "runId": "run-1", "startToken": "token-1", "pid": 101}
    fake.processes = [expected]
    runtime.register_process(expected)
    fake.processes.append({**expected, "pid": 202, "startToken": "other"})
    with pytest.raises(WorkflowBlocked):
        runtime.stop_all()
    assert fake.stopped == []


def test_runtime_target_validates_complete_capture_before_backend_load():
    import copy

    import pytest
    from test_isolated import _source

    from f1_predict.replay.workflow import WorkflowBlocked

    class LoadSystem(FakeSystem):
        def load(self, source, identity):
            self.calls.append(("load", source, identity))
            return {"questionId": 1, "snapshotId": 2, "roundId": 3,
                    "optionIds": {"4401": 4, "4402": 5}}

    fake = LoadSystem()
    auth = RuntimeAuthorization(project="real-data-mvp-local", scope="isolated-real-data-mvp")
    target = RuntimeTarget(fake, project="real-data-mvp-local", authorization=auth,
                           permission_check=lambda *_: True)
    source, identity = _source()
    assert target.load(source, identity)["questionId"] == 1
    assert fake.calls[-1][0] == "load"
    for mutate in (
        lambda value: value.pop("question"),
        lambda value: value["snapshot"].update(id=0),
        lambda value: value["options"].append({"optionId": 99, "optionNo": 2,
            "optionText": "extra", "points": None, "chance": None}),
        lambda value: value["options"][0].update(optionId=True),
    ):
        bad = copy.deepcopy(source)
        mutate(bad)
        before = len(fake.calls)
        with pytest.raises(WorkflowBlocked):
            target.load(bad, identity)
        assert len(fake.calls) == before


def test_replay_rejects_valid_but_different_frozen_request_before_backend_replay(tmp_path):
    import pytest

    from f1_predict.replay.runtime import OperationJournal, ReplayRuntime
    from f1_predict.replay.workflow import WorkflowBlocked

    class MismatchFake(FakeSystem):
        def __init__(self):
            super().__init__()
            self.replays = 0

        def post_batch(self, round_id, payload):
            return {"roundId": round_id, "questionCount": 1, "batchId": 42, "jobIds": ["job-42"]}

        def original_message(self, job_id):
            return {"messageId": "msg-42", "payload": {
                "schemaVersion": "2", "messageId": "msg-42", "predictionJobId": job_id,
                "batchId": 42, "questionId": 999, "questionSnapshotId": 8, "traceId": "trace-42",
                "dataCutoff": "2025-01-01T00:00:00Z", "modelVersion": "m2", "promptVersion": "p1",
                "featureVersion": "f1", "embeddingVersion": None, "retrieverVersion": None,
                "question": {"questionText": "Which driver?", "subText": None, "questionType": "UNKNOWN",
                    "optionTemplateId": None, "choiceLimit": 1, "options": [
                        {"optionId": 11, "optionNo": 0, "optionText": "A", "points": None, "chance": None},
                        {"optionId": 12, "optionNo": 1, "optionText": "B", "points": None, "chance": None}]},
                "raceContext": {"seasonId": 1, "year": 2026, "roundId": 9, "roundNumber": 1,
                    "meetingKey": None, "sessionKey": None, "gamedayId": 7, "trackName": "Track"}}}

        def replay(self, _):
            self.replays += 1

    fake = MismatchFake()
    project = "real-data-mvp-local"
    auth = RuntimeAuthorization(project=project, scope="isolated-real-data-mvp")
    target = RuntimeTarget(fake, project=project, authorization=auth, permission_check=lambda *_: True)
    journal = OperationJournal(tmp_path / "mismatch", project=project,
                               resource_identity=target.resource_identity())
    journal.save(stage="READY")
    runtime = ReplayRuntime(target, journal, mode="OFFLINE_ADAPTER_TEST")
    runtime.submit(9, {"questionIds": [7], "dataCutoff": "2026-01-01T00:00:00Z", "modelVersion": "m1",
                       "promptVersion": "p1", "featureVersion": "f1"})
    with pytest.raises(WorkflowBlocked):
        runtime.replay("job-42")
    assert fake.replays == 0


def test_submitted_response_cache_is_bound_to_identical_frozen_request(tmp_path):
    import pytest

    from f1_predict.replay.runtime import OperationJournal, ReplayRuntime
    from f1_predict.replay.workflow import WorkflowBlocked

    class SubmitFake(FakeSystem):
        def __init__(self):
            super().__init__()
            self.posts = []

        def post_batch(self, round_id, payload):
            self.posts.append((round_id, payload))
            return {"roundId": round_id, "questionCount": 1, "batchId": 42, "jobIds": ["job-42"]}

    fake = SubmitFake()
    project = "real-data-mvp-local"
    auth = RuntimeAuthorization(project=project, scope="isolated-real-data-mvp")
    target = RuntimeTarget(fake, project=project, authorization=auth, permission_check=lambda *_: True)
    journal = OperationJournal(tmp_path / "cached-submit", project=project,
                               resource_identity=target.resource_identity())
    journal.save(stage="READY")
    runtime = ReplayRuntime(target, journal, mode="OFFLINE_ADAPTER_TEST")
    payload = {"questionIds": [7], "dataCutoff": "2026-01-01T00:00:00Z", "modelVersion": "m1",
               "promptVersion": "p1", "featureVersion": "f1"}
    response = runtime.submit(9, payload)
    assert runtime.submit(9, dict(payload)) == response
    with pytest.raises(WorkflowBlocked):
        runtime.submit(10, payload)
    assert fake.posts == [(9, payload)]


def test_journal_rejects_stale_writer_before_second_side_effect_intent(tmp_path):
    import pytest

    from f1_predict.replay.runtime import OperationJournal
    from f1_predict.replay.workflow import WorkflowBlocked

    kwargs = {"project": "real-data-mvp-local", "resource_identity": {"project": "real-data-mvp-local"}}
    first = OperationJournal(tmp_path / "shared", **kwargs)
    second = OperationJournal(tmp_path / "shared", **kwargs)
    first.save(stage="READY")
    first.save(stage="APPLYING", schemaNames=["approved"])
    with pytest.raises(WorkflowBlocked):
        second.save(stage="READY")
    assert OperationJournal(tmp_path / "shared", **kwargs).state["stage"] == "APPLYING"


def test_missing_persisted_journal_cannot_be_recreated_by_stale_new_instance(tmp_path):
    import pytest

    from f1_predict.replay.runtime import OperationJournal
    from f1_predict.replay.workflow import WorkflowBlocked

    kwargs = {"project": "real-data-mvp-local", "resource_identity": {"project": "real-data-mvp-local"}}
    first = OperationJournal(tmp_path / "deleted-state", **kwargs)
    stale_new = OperationJournal(tmp_path / "deleted-state", **kwargs)
    first.save(stage="READY")
    first.path.unlink()
    with pytest.raises(WorkflowBlocked):
        stale_new.save(stage="READY")


def test_backend_permission_error_is_sanitized():
    import pytest

    from f1_predict.replay.workflow import WorkflowBlocked

    class SecretSystem(FakeSystem):
        def table_count(self):
            raise PermissionError("private-secret-token")

    target = RuntimeTarget(SecretSystem(), project="real-data-mvp-local", authorization=RuntimeAuthorization(
        project="real-data-mvp-local", scope="isolated-real-data-mvp"), permission_check=lambda *_: True)
    with pytest.raises(WorkflowBlocked) as error:
        target.table_count()
    assert "private-secret-token" not in str(error.value)


def test_submit_requires_parseable_utc_cutoff_and_returns_stable_cached_state(tmp_path):
    import pytest

    from f1_predict.replay.runtime import OperationJournal, ReplayRuntime
    from f1_predict.replay.workflow import WorkflowBlocked

    class SubmitSystem(FakeSystem):
        def __init__(self):
            super().__init__()
            self.posts = 0

        def post_batch(self, round_id, payload):
            self.posts += 1
            return {"roundId": round_id, "questionCount": 1, "batchId": 42, "jobIds": ["job-42"]}

    fake = SubmitSystem()
    project = "real-data-mvp-local"
    target = RuntimeTarget(fake, project=project, authorization=RuntimeAuthorization(
        project=project, scope="isolated-real-data-mvp"), permission_check=lambda *_: True)
    journal = OperationJournal(tmp_path / "submit", project=project, resource_identity=fake.resource_identity())
    journal.save(stage="READY")
    runtime = ReplayRuntime(target, journal, mode="OFFLINE_ADAPTER_TEST")
    payload = {"questionIds": [7], "dataCutoff": "not-a-time", "modelVersion": "m1",
               "promptVersion": "p1", "featureVersion": "f1"}
    with pytest.raises(WorkflowBlocked):
        runtime.submit(9, payload)
    assert fake.posts == 0

    payload["dataCutoff"] = "2026-01-01T00:00:00Z"
    first = runtime.submit(9, payload)
    second = runtime.submit(9, payload)
    assert first == second
    assert first["stage"] == "SUBMITTED"
    assert fake.posts == 1
    with pytest.raises(WorkflowBlocked):
        runtime.submit(9, {**payload, "modelVersion": "m2"})
    assert fake.posts == 1


def test_observe_and_first_replay_bind_original_message_to_frozen_submission(tmp_path):
    import copy

    import pytest

    from f1_predict.replay.runtime import OperationJournal, ReplayRuntime
    from f1_predict.replay.workflow import WorkflowBlocked

    class MessageSystem(FakeSystem):
        def __init__(self):
            super().__init__()
            self.message = {
                "messageId": "msg-42",
                "payload": {
                    "schemaVersion": "2", "messageId": "msg-42", "predictionJobId": "job-42",
                    "batchId": 42, "questionId": 7, "questionSnapshotId": 8, "traceId": "trace-42",
                    "dataCutoff": "2026-01-01T00:00:00Z", "modelVersion": "m1", "promptVersion": "p1",
                    "featureVersion": "f1", "embeddingVersion": None, "retrieverVersion": None,
                    "question": {"questionText": "Which driver?", "subText": None, "questionType": "UNKNOWN",
                                  "optionTemplateId": None, "choiceLimit": 1,
                                  "options": [{"optionId": 11, "optionNo": 0, "optionText": "A", "points": None, "chance": None},
                                              {"optionId": 12, "optionNo": 1, "optionText": "B", "points": None, "chance": None}]},
                    "raceContext": {"seasonId": 1, "year": 2026, "roundId": 9, "roundNumber": 1,
                                    "meetingKey": None, "sessionKey": None, "gamedayId": 7, "trackName": "Track"},
                },
            }
            self.observe_calls = 0
            self.replay_calls = 0

        def post_batch(self, round_id, payload):
            return {"roundId": round_id, "questionCount": 1, "batchId": 42, "jobIds": ["job-42"]}

        def original_message(self, job_id):
            return copy.deepcopy(self.message)

        def observe(self, job_id, batch_id):
            self.observe_calls += 1
            return {"workerAlive": True, "job": {"predictionJobId": job_id},
                    "batch": {"batchId": batch_id}, "outcome": {"predictionJobId": job_id},
                    "sql": {"predictionJobId": job_id, "batchId": batch_id},
                    "sqlite": {"predictionJobId": job_id}}

        def replay(self, message):
            self.replay_calls += 1

    fake = MessageSystem()
    project = "real-data-mvp-local"
    target = RuntimeTarget(fake, project=project, authorization=RuntimeAuthorization(
        project=project, scope="isolated-real-data-mvp"), permission_check=lambda *_: True)
    journal = OperationJournal(tmp_path / "message", project=project, resource_identity=fake.resource_identity())
    journal.save(stage="READY")
    runtime = ReplayRuntime(target, journal, mode="OFFLINE_ADAPTER_TEST")
    runtime.submit(9, {"questionIds": [7], "dataCutoff": "2026-01-01T00:00:00Z", "modelVersion": "m1",
                       "promptVersion": "p1", "featureVersion": "f1"})
    original = copy.deepcopy(fake.message)
    for change in (
        lambda value: value["payload"].update(questionId=999),
        lambda value: value["payload"].update(dataCutoff="2025-01-01T00:00:00Z"),
        lambda value: value["payload"].update(modelVersion="m2"),
        lambda value: value["payload"].update(promptVersion="p2"),
        lambda value: value["payload"].update(featureVersion="f2"),
        lambda value: value["payload"]["raceContext"].update(roundId=10),
    ):
        fake.message = copy.deepcopy(original)
        change(fake.message)
        with pytest.raises(WorkflowBlocked):
            runtime.replay("job-42")
    fake.message = copy.deepcopy(original)
    fake.message["payload"]["modelVersion"] = "m2"
    with pytest.raises(WorkflowBlocked):
        runtime.observe("job-42", 42)
    assert fake.observe_calls == 0
    fake.message = original
    assert runtime.observe("job-42", 42)["job"]["predictionJobId"] == "job-42"
    assert runtime.replay("job-42")["payload"]["questionId"] == 7
    assert fake.replay_calls == 1


def test_restart_atomically_replaces_registered_process_with_verified_fresh_identity(tmp_path):
    import copy

    import pytest

    from f1_predict.replay.runtime import OperationJournal, ReplayRuntime
    from f1_predict.replay.workflow import WorkflowBlocked

    java = {"pid": 55, "project": "real-data-mvp-local", "runId": "run-1", "startToken": "java-token"}
    old = {"pid": 101, "project": "real-data-mvp-local", "runId": "run-1", "startToken": "old-token"}
    current = {"pid": 202, "project": "real-data-mvp-local", "runId": "run-1", "startToken": "new-token"}
    previous_inventory = [java, old]
    replacement_inventory = [java, current]

    class RestartSystem(FakeSystem):
        def __init__(self, *, report):
            super().__init__()
            self.processes = copy.deepcopy(previous_inventory)
            self.report = report
            self.stopped = []

        def restart_worker(self):
            self.processes = copy.deepcopy(replacement_inventory)
            return copy.deepcopy(self.report)

        def owned_processes(self):
            return copy.deepcopy(self.processes)

        def stop_process(self, process):
            self.stopped.append(process)
            self.processes.remove(process)

    cases = [
        {"previous": previous_inventory, "replacement": replacement_inventory},
        {"previous": [java, {**old, "runId": "other-run"}], "replacement": replacement_inventory},
        {"previous": previous_inventory, "replacement": [java, current,
                                                             {"pid": 303, "project": "real-data-mvp-local",
                                                              "runId": "run-1", "startToken": "extra-token"}]},
        None,
    ]
    for index, reported in enumerate(cases):
        fake = RestartSystem(report=reported)
        project = "real-data-mvp-local"
        target = RuntimeTarget(fake, project=project, authorization=RuntimeAuthorization(
            project=project, scope="isolated-real-data-mvp"), permission_check=lambda *_: True)
        journal = OperationJournal(tmp_path / f"restart-{index}", project=project,
                                   resource_identity=fake.resource_identity())
        journal.save(stage="REPLAYED", postAttempted=True, jobId="job-42", batchId=42,
                     ownedProcesses=previous_inventory)
        runtime = ReplayRuntime(target, journal, mode="OFFLINE_ADAPTER_TEST")
        if index == 0:
            result = runtime.restart()
            assert result["ownedProcesses"] == replacement_inventory
            assert runtime.stop_all()["stage"] == "STOPPED"
            assert fake.stopped == replacement_inventory
            assert old not in fake.stopped
        else:
            with pytest.raises(WorkflowBlocked):
                runtime.restart()
            assert journal.state["stage"] == "RESTART_UNCERTAIN"
            assert journal.state["ownedProcesses"] == previous_inventory
            with pytest.raises(WorkflowBlocked):
                runtime.stop_all()
            assert fake.stopped == []
