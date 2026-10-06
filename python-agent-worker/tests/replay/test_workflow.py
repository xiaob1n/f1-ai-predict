"""模拟目标验证单次提交、隔离门禁和中断恢复；不执行外部命令。"""

from __future__ import annotations

import copy
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest

from f1_predict.replay.workflow import (
    SCHEMA_NUMBERS,
    SCOPE,
    ReplayWorkflow,
    StateStore,
    WorkflowBlocked,
)

PROJECT = "real-data-mvp-simulation"
MAPPING = {"roundId": 4, "questionId": 8, "snapshotId": 12, "optionIds": {"31": 31, "32": 32}}
POLICY = {"question_id": 8, "question_snapshot_id": 12, "option_drivers": {"31": 51, "32": 52},
          "model_version": "replay-baseline-v1", "prompt_version": "prompt-h2h-replay-v1",
          "feature_version": "feature-v1"}
INSTANT = datetime(2026, 1, 2, tzinfo=UTC)


class FakeTarget:
    simulated = True

    def __init__(self) -> None:
        self.identity = {"project": PROJECT, "scope": SCOPE, "dockerContext": "local",
                         "database": "f1_ai_predict", "sourceShared": False,
                         "services": {key: {"project": PROJECT, "service": key, "scope": SCOPE,
                                            "bindings": [{"host": "127.0.0.1", "port": port}]}
                                      for key, port in (("mysql", 23306), ("rabbitmq", 25673))}}
        self.tables = 0
        self.scripts: list[str] = []
        self.schema_crash = False
        self.posts = 0
        self.post_crash = False
        self.restart_count = 0
        self.replays: list[dict[str, Any]] = []
        self.observations: list[dict[str, Any]] = []
        self.processes: list[dict[str, Any]] = []
        self.stopped: list[dict[str, Any]] = []
        self.request: dict[str, Any] = {}

    def resource_identity(self) -> dict[str, Any]:
        return copy.deepcopy(self.identity)

    def table_count(self) -> int:
        return self.tables

    def apply_schema(self, path: Path, digest: str) -> None:
        self.scripts.append(path.name)
        assert len(digest) == 64
        if self.schema_crash:
            raise OSError("模拟 DDL 返回不明")

    def inspect_schema(self) -> None:
        pass

    def load(self, source: dict[str, Any], identity: dict[str, Any]) -> dict[str, Any]:
        return copy.deepcopy(MAPPING)

    def post_batch(self, round_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        self.posts += 1
        self.request = copy.deepcopy(payload)
        if self.post_crash:
            raise TimeoutError
        return {"batchId": 21, "roundId": round_id, "questionCount": 1, "jobIds": ["job-simulation"]}

    def terminal(self) -> dict[str, Any]:
        return {"workerAlive": True, "job": {"predictionJobId": "job-simulation", "status": "SUCCEEDED"},
                "batch": {"batchId": 21, "status": "COMPLETED", "questionCount": 1},
                "outcome": {"predictionJobId": "job-simulation", "status": "SUCCEEDED", "failure": None,
                            "result": {"selectedOptions": [{"optionId": 31, "position": 1}], "confidence": 0.5}},
                "sql": {"predictionJobId": "job-simulation", "batchId": 21,
                        "resultCount": 1, "receiptCount": 1, "failureCount": 0},
                "sqlite": {"predictionJobId": "job-simulation", "inboxCount": 1, "outboxCount": 1,
                           "publishedCount": 1, "status": "SUCCEEDED", "manifestHash": "a" * 64,
                           "featureHash": "b" * 64, "dataCutoff": "2026-01-02T00:00:00.000Z",
                           "executionMode": "HISTORICAL_ENGINEERING_REPLAY",
                           "sourceFirstSeenStatus": "UNKNOWN", "sportingCutoff": "2026-01-02T00:00:00.000Z",
                           "importCompletedAt": "2026-01-02T00:00:00.000Z",
                           "modelVersion": POLICY["model_version"], "promptVersion": POLICY["prompt_version"],
                           "featureVersion": POLICY["feature_version"]}}

    def observe(self, job_id: str, batch_id: int) -> dict[str, Any]:
        return self.observations.pop(0) if self.observations else self.terminal()

    def original_message(self, job_id: str) -> dict[str, Any]:
        return {"messageId": "request-simulation", "payload": {"messageId": "request-simulation",
                "predictionJobId": job_id, "batchId": 21, "questionId": 8, "questionSnapshotId": 12,
                "dataCutoff": "2026-01-02T00:00:00.000Z"}}

    def replay(self, message: dict[str, Any]) -> None:
        self.replays.append(copy.deepcopy(message))

    def restart_worker(self) -> None:
        self.restart_count += 1

    def owned_processes(self) -> list[dict[str, Any]]:
        return self.processes

    def stop_process(self, process: dict[str, Any]) -> None:
        self.stopped.append(process)


@pytest.fixture
def flow(tmp_path: Path) -> tuple[ReplayWorkflow, FakeTarget, Path]:
    runtime = tmp_path / "run"
    runtime.mkdir(mode=0o700)
    root = tmp_path / "sql"
    root.mkdir()
    for number in (*SCHEMA_NUMBERS, "005"):
        (root / (number + "_demo.sql")).write_text("-- 模拟 schema\n", encoding="utf-8")
    target = FakeTarget()
    monotonic = [0.0]

    def wait(seconds: float) -> None:
        monotonic[0] += seconds

    workflow = ReplayWorkflow(StateStore(runtime), target, PROJECT, clock=lambda: INSTANT,
                              monotonic=lambda: monotonic[0], wait=wait, timeout_seconds=2)
    return workflow, target, root


def _ready(flow: tuple[ReplayWorkflow, FakeTarget, Path]) -> ReplayWorkflow:
    workflow, _, root = flow
    workflow.prepare(root)
    workflow.load_capture({}, {}, "c" * 64)
    workflow.bind_bundle("a" * 64, POLICY, INSTANT, INSTANT)
    return workflow


def test_simulated_submission_replay_and_restart_keep_one_terminal(flow) -> None:
    workflow = _ready(flow)
    target = flow[1]
    workflow.submit()
    workflow.submit()
    workflow.wait_terminal()
    workflow.exercise_recovery()
    assert target.posts == 1 and target.restart_count == 1
    assert len(target.replays) == 1
    assert workflow.report()["simulatedRecoveryVerified"] is True
    assert workflow.report()["m1Verified"] is False
    assert workflow.report()["m2Verified"] is False
    assert not any(name.startswith("005") for name in target.scripts)
    assert workflow.store.path.stat().st_mode & 0o777 == 0o600


def test_schema_uncertain_is_persistent_and_never_retried(flow) -> None:
    workflow, target, root = flow
    target.schema_crash = True
    with pytest.raises(WorkflowBlocked, match="schema 初始化结果不明"):
        workflow.prepare(root)
    target.schema_crash = False
    restored = ReplayWorkflow(workflow.store, target, PROJECT, clock=lambda: INSTANT,
                              monotonic=lambda: 0, wait=lambda _: None)
    with pytest.raises(WorkflowBlocked, match="禁止自动重跑"):
        restored.prepare(root)
    assert len(target.scripts) == 1


def test_post_timeout_and_resume_do_not_create_a_second_batch(flow) -> None:
    workflow = _ready(flow)
    target = flow[1]
    target.post_crash = True
    with pytest.raises(WorkflowBlocked, match="禁止自动重复 POST"):
        workflow.submit()
    restored = ReplayWorkflow(workflow.store, target, PROJECT, clock=lambda: INSTANT,
                              monotonic=lambda: 0, wait=lambda _: None)
    with pytest.raises(WorkflowBlocked, match="重复 POST"):
        restored.submit()
    assert target.posts == 1


@pytest.mark.parametrize("field,value", [("scope", "isolated-e2e"), ("sourceShared", True),
                                        ("project", "another-project"), ("dockerContext", "remote")])
def test_identity_mismatch_refuses_all_schema_side_effects(flow, field, value) -> None:
    workflow, target, root = flow
    target.identity[field] = value
    with pytest.raises(WorkflowBlocked):
        workflow.prepare(root)
    assert target.scripts == [] and target.posts == 0


def test_nonempty_database_refuses_initialization(flow) -> None:
    workflow, target, root = flow
    target.tables = 1
    with pytest.raises(WorkflowBlocked, match="空库"):
        workflow.prepare(root)
    assert not target.scripts


def test_wrong_terminal_identity_never_passes(flow) -> None:
    workflow = _ready(flow)
    target = flow[1]
    workflow.submit()
    record = target.terminal()
    record["sqlite"]["manifestHash"] = "d" * 64
    target.observations = [record]
    with pytest.raises(WorkflowBlocked, match="证据不一致"):
        workflow.wait_terminal()


def test_201_without_terminal_does_not_count_as_success(flow) -> None:
    workflow = _ready(flow)
    target = flow[1]
    workflow.submit()
    pending = target.terminal()
    pending["job"]["status"] = "PENDING"
    target.observations = [copy.deepcopy(pending) for _ in range(10)]
    with pytest.raises(WorkflowBlocked, match="有界预算"):
        workflow.wait_terminal()
    assert workflow.state["stage"] == "TIMED_OUT"
    assert target.posts == 1


@pytest.mark.parametrize("status,alive", [("FAILED", True), ("PENDING", False)])
def test_terminal_failure_and_worker_exit_end_promptly(flow, status, alive) -> None:
    workflow = _ready(flow)
    target = flow[1]
    workflow.submit()
    record = target.terminal()
    record["job"]["status"], record["workerAlive"] = status, alive
    target.observations = [record]
    with pytest.raises(WorkflowBlocked, match="推理失败"):
        workflow.wait_terminal()


def test_wrong_process_owner_is_not_stopped(flow) -> None:
    workflow = _ready(flow)
    target = flow[1]
    target.processes = [{"project": PROJECT, "runId": "another-run", "pid": 42, "startToken": "token"}]
    with pytest.raises(WorkflowBlocked, match="非本工具"):
        workflow.stop()
    assert not target.stopped


def test_recovery_changed_feature_hash_fails(flow) -> None:
    workflow = _ready(flow)
    target = flow[1]
    workflow.submit()
    workflow.wait_terminal()
    changed = target.terminal()
    changed["sqlite"]["featureHash"] = "d" * 64
    target.observations = [changed]
    with pytest.raises(WorkflowBlocked, match="改变了冻结输入"):
        workflow.exercise_recovery()
    assert workflow.state["stage"] == "RECOVERY_CONFLICT"


def test_recorded_process_start_token_is_required_before_stop(flow) -> None:
    workflow = _ready(flow)
    target = flow[1]
    process = {"project": PROJECT, "runId": workflow.state["runId"], "pid": 42, "startToken": "first-start"}
    workflow.record_started_process(process)
    target.processes = [{**process, "startToken": "reused-pid"}]
    with pytest.raises(WorkflowBlocked, match="非本工具"):
        workflow.stop()
    assert not target.stopped
    target.processes = [process]
    workflow.stop()
    assert target.stopped == [process]


def test_all_processes_are_checked_before_any_stop(flow) -> None:
    workflow = _ready(flow)
    target = flow[1]
    process = {"project": PROJECT, "runId": workflow.state["runId"], "pid": 42, "startToken": "first-start"}
    workflow.record_started_process(process)
    target.processes = [process, {**process, "pid": 43}]
    with pytest.raises(WorkflowBlocked):
        workflow.stop()
    assert not target.stopped


def test_local_simulation_processes_frozen_bundle_and_restarts_without_network(tmp_path, monkeypatch) -> None:
    from f1_predict.replay.simulation import run_simulation

    def forbidden(*args, **kwargs):
        raise AssertionError("离线模拟不得使用网络或外部进程")

    monkeypatch.setattr("socket.socket.connect", forbidden)
    monkeypatch.setattr("subprocess.Popen", forbidden)
    monkeypatch.setattr("subprocess.run", forbidden)
    monkeypatch.setattr("urllib.request.urlopen", forbidden)
    runtime = tmp_path / "simulation"
    runtime.mkdir(mode=0o700)
    sql_root = Path(__file__).resolve().parents[3] / "sql"
    report = run_simulation(runtime, sql_root)
    assert report["stage"] == "SIMULATED_COMPLETE"
    assert report["mode"] == "OFFLINE_SIMULATION"
    assert report["localSqliteUsed"] is True
    assert report["simulatedRecoveryVerified"] is True
    assert report["postCalls"] == 1
    assert report["simulatedReplays"] == report["simulatedWorkerRestarts"] == 1
    assert report["realResourceAccess"] is report["realModelCalls"] is False
    assert report["m1Verified"] is report["m2Verified"] is False


def test_isolated_environment_drops_inherited_service_and_proxy_configuration(tmp_path) -> None:
    from f1_predict.replay.workflow import worker_environment

    runtime = tmp_path / "private-runtime"
    runtime.mkdir(mode=0o700)
    bundle = runtime / "bundle"
    bundle.mkdir(mode=0o700)
    inherited = {"PATH": "/bin", "HOME": str(tmp_path), "HTTP_PROXY": "http://untrusted.invalid",
                 "JAVA_TOOL_OPTIONS": "-Dspring.config.import=nacos:test",
                 "SPRING_CONFIG_LOCATION": "production.yaml", "F1_PREDICT_MODEL_URL": "http://untrusted.invalid"}
    env = worker_environment(inherited, bundle, runtime, "a" * 64, POLICY,
                             "amqp://simulation:simulation@127.0.0.1:15683/%2Ff1predict-mvp")
    assert env["PATH"] == "/bin"
    assert "HTTP_PROXY" not in env and "JAVA_TOOL_OPTIONS" not in env
    assert "SPRING_CONFIG_LOCATION" not in env and "F1_PREDICT_MODEL_URL" not in env
    assert env["F1_PREDICT_PREDICTION_REPLAY_MODEL_MODE"] == "stub"
    assert env["F1_PREDICT_PREDICTION_REPLAY_MANIFEST_HASH"] == "a" * 64
    assert env["F1_PREDICT_CONSUMER_SQLITE_PATH"] == str(runtime / "worker.sqlite")
    for url in ("amqp://simulation:simulation@broker.example.test:15683/%2Ff1predict-mvp",
                "amqp://simulation:simulation@127.0.0.1:15683/f1predict-mvp"):
        with pytest.raises(WorkflowBlocked, match="专属回环"):
            worker_environment(inherited, bundle, runtime, "a" * 64, POLICY, url)


def test_state_store_rejects_symlink_and_oversized_state(flow, tmp_path) -> None:
    workflow = flow[0]
    foreign = tmp_path / "foreign.json"
    foreign.write_text('{}', encoding="utf-8")
    workflow.store.path.symlink_to(foreign)
    with pytest.raises(WorkflowBlocked, match="符号链接"):
        workflow.store.load()
    workflow.store.path.unlink()
    workflow.store.path.write_text(" " * 1_048_577, encoding="utf-8")
    workflow.store.path.chmod(0o600)
    with pytest.raises(WorkflowBlocked, match="大小不合格"):
        workflow.store.load()


def test_environment_does_not_allow_real_model_override() -> None:
    from f1_predict.replay.workflow import isolated_environment

    with pytest.raises(WorkflowBlocked):
        isolated_environment({}, {"F1_PREDICT_MODEL_URL": "http://127.0.0.1:9000"})


def test_live_target_is_not_allowed(flow) -> None:
    workflow, target, _ = flow
    target.simulated = False
    with pytest.raises(WorkflowBlocked, match="真实资源操作尚未授权"):
        ReplayWorkflow(workflow.store, target, PROJECT, clock=lambda: INSTANT,
                       monotonic=lambda: 0, wait=lambda _: None)
