"""明确标记的离线单题模拟：真实包/SQLite/替身计算，外部系统全部为内存替身。"""

from __future__ import annotations

import asyncio
import copy
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from f1_predict.messaging.dto.request_v2 import PredictionRequestV2
from f1_predict.prediction.data import load_lap_fixture
from f1_predict.prediction.processor import PredictionProcessor
from f1_predict.prediction.replay_context import ReplayContext
from f1_predict.prediction.replay_model import ReplayBaselineModel
from f1_predict.reliability.idempotency import InboxStore
from f1_predict.replay.manifest import canonical_sha256, format_utc
from f1_predict.replay.workflow import (
    SCOPE,
    ReplayWorkflow,
    StateStore,
    WorkflowBlocked,
)

PROJECT = "real-data-mvp-simulation"


def synthetic_capture(now: datetime) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]], datetime]:
    """使用刻意不同于候选真实题的合成身份，绝不伪装可信赛事材料。"""
    cutoff = now - timedelta(days=1)
    text = "离线模拟：比较两名合成车手的冻结练习圈时"
    options = [{"optionId": number, "optionNo": index, "optionText": label, "points": None, "chance": None}
               for index, (number, label) in enumerate(((41, "合成车手甲"), (42, "合成车手乙")))]
    source = {
        "question": {"id": 9003, "roundId": 9002, "gamedayId": 9002, "sourceQuestionId": 9003,
                     "questionNo": 1, "status": "2", "latestSnapshotId": 9004,
                     "firstSeenAt": format_utc(cutoff - timedelta(hours=1))},
        "snapshot": {"id": 9004, "questionId": 9003, "snapshotNo": 1,
                     "contentHash": canonical_sha256(text), "createdAt": format_utc(cutoff - timedelta(hours=1)),
                     "raw": {"Text": text, "SubText": None, "OptionTemplateId": None, "Config": {"ChoiceLimit": 1}}},
        "season": {"id": 9001, "year": now.year, "name": "离线模拟赛季", "status": "UPCOMING"},
        "round": {"id": 9002, "seasonId": 9001, "roundNumber": 1, "grandPrixName": "离线模拟站",
                  "circuitName": "合成赛道", "country": None, "locality": None,
                  "startDate": cutoff.date().isoformat(), "endDate": cutoff.date().isoformat(), "status": "SCHEDULED"},
        "sessions": [{"meetingKey": 9101, "sessionKey": key, "sessionName": "Practice " + str(index),
                      "sessionType": "Practice", "gamedayId": 9002,
                      "startDateUtc": format_utc(cutoff - timedelta(hours=1)),
                      "endDateUtc": format_utc(cutoff), "status": "SCHEDULED"}
                     for index, key in enumerate((9103, 9104), 1)],
        "options": options,
    }
    identity = {"sourceQuestionId": 9003, "sourceSnapshotId": 9004, "sourceRoundId": 9002,
                "questionTextHash": canonical_sha256(text),
                "optionTextHashes": {str(item["optionId"]): canonical_sha256(item["optionText"]) for item in options},
                "optionDrivers": {"41": 91, "42": 92},
                "meetingIdentity": {"mysqlMeetingKey": 9101, "mongoMeetingKey": 9102},
                "sessions": [{"sessionKey": key, "type": "Practice"} for key in (9103, 9104)],
                "drivers": [{"driverNumber": key} for key in (91, 92)],
                "evidenceHashes": {"syntheticCatalog": canonical_sha256("OFFLINE_SIMULATION_ONLY")}}
    rows = [{"_key": f"synthetic-{driver}-{lap}", "meeting_key": 9102, "session_key": 9103,
             "driver_number": driver, "lap_number": lap + 1,
             "date_start": format_utc(cutoff - timedelta(minutes=10 + lap * 2)),
             "lap_duration": duration, "duration_sector_1": duration / 3,
             "duration_sector_2": duration / 3, "duration_sector_3": duration / 3,
             "is_pit_out_lap": False}
            for driver, duration in ((91, 90.0), (92, 93.0)) for lap in range(3)]
    return source, identity, rows, cutoff


class SimulationTarget:
    """外部 MySQL/Rabbit/HTTP/进程仅记录模拟动作，Worker 计算复用本地实现。"""

    simulated = True

    def __init__(self, directory: Path) -> None:
        self.directory = directory
        self.scripts: list[str] = []
        self.posts = 0
        self.restart_count = 0
        self.replay_count = 0
        self.mapping = {"seasonId": 701, "roundId": 702, "questionId": 703, "snapshotId": 704,
                        "optionIds": {"41": 801, "42": 802}}
        self.loaded_hash: str | None = None
        self.source: dict[str, Any] = {}
        self.policy: Any = None
        self.context: ReplayContext | None = None
        self.message: dict[str, Any] = {}
        self.result: dict[str, Any] = {}
        self.store: InboxStore | None = None
        self.frozen_hash: str | None = None

    def resource_identity(self) -> dict[str, Any]:
        """返回明确合成的资源标签，不访问 Docker。"""
        return {"project": PROJECT, "scope": SCOPE, "dockerContext": "local",
                "database": "f1_ai_predict", "sourceShared": False,
                "services": {key: {"project": PROJECT, "service": key, "scope": SCOPE,
                                   "bindings": [{"host": "127.0.0.1", "port": port}]}
                             for key, port in (("mysql", 23306), ("rabbitmq", 25673))}}

    def table_count(self) -> int:
        """返回内存 schema 规划状态，不查询数据库。"""
        return 0 if not self.scripts else 1

    def apply_schema(self, path: Path, digest: str) -> None:
        """只记录批准脚本，不执行 SQL。"""
        if path.name.startswith("005") or len(digest) != 64:
            raise WorkflowBlocked("模拟 schema 白名单不匹配")
        self.scripts.append(path.name)

    def inspect_schema(self) -> None:
        """核对模拟脚本顺序；不构成真实 DDL 结构证明。"""
        if [item[:3] for item in self.scripts] != ["001", "002", "003", "004", "006", "007", "008"]:
            raise WorkflowBlocked("模拟 schema 结构尚未完整规划")

    def load(self, source: dict[str, Any], identity: dict[str, Any]) -> dict[str, Any]:
        """内存保存源副本并固定合成隔离 ID。"""
        digest = canonical_sha256({"source": source, "identity": identity})
        if self.loaded_hash is not None and self.loaded_hash != digest:
            raise WorkflowBlocked("模拟隔离装载内容冲突")
        self.loaded_hash = digest
        self.source = copy.deepcopy(source)
        return copy.deepcopy(self.mapping)

    def configure_bundle(self, sealed: Any) -> None:
        """核验已封签本地包，并创建模拟专属 SQLite。"""
        self.policy = sealed.policy
        self.context = ReplayContext.load(
            str(sealed.manifest_path), str(sealed.manifest_path.parent / "fixture.json"),
            str(sealed.manifest_path.parent / "policy.json"), model_mode="stub",
            expected_manifest_hash=sealed.manifest_hash,
        )
        self.store = InboxStore(self.directory / "simulation.sqlite")

    def post_batch(self, round_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        """按 v2 契约构造合成请求，直接落本地 inbox 而不发送 HTTP。"""
        if self.store is None or self.context is None:
            raise WorkflowBlocked("模拟推理尚未装载封签包")
        self.posts += 1
        raw = self.source["snapshot"]["raw"]
        request = PredictionRequestV2.model_validate({
            "schemaVersion": "2", "messageId": "simulation-request-1", "predictionJobId": "simulation-job-1",
            "batchId": 705, "questionId": self.mapping["questionId"], "questionSnapshotId": self.mapping["snapshotId"],
            "traceId": "simulation-trace-1", "dataCutoff": payload["dataCutoff"],
            "modelVersion": payload["modelVersion"], "promptVersion": payload["promptVersion"],
            "featureVersion": payload["featureVersion"], "embeddingVersion": None, "retrieverVersion": None,
            "question": {"questionText": raw["Text"], "subText": raw["SubText"], "questionType": "UNKNOWN",
                         "optionTemplateId": raw["OptionTemplateId"], "choiceLimit": raw["Config"]["ChoiceLimit"],
                         "options": [{**item, "optionId": self.mapping["optionIds"][str(item["optionId"])]}
                                     for item in self.source["options"]]},
            "raceContext": {"seasonId": self.mapping["seasonId"], "year": self.source["season"]["year"],
                            "roundId": round_id, "roundNumber": self.source["round"]["roundNumber"],
                            "meetingKey": self.policy.meeting_key, "sessionKey": None,
                            "gamedayId": self.source["question"]["gamedayId"], "trackName": self.source["round"]["circuitName"]},
        })
        self.message = {"messageId": request.message_id, "payload": request.model_dump(mode="json", by_alias=True)}
        self.store.save(request)
        self._process()
        return {"batchId": 705, "roundId": round_id, "questionCount": 1, "jobIds": [request.prediction_job_id]}

    def _process(self) -> None:
        assert self.store is not None and self.context is not None
        processor = PredictionProcessor(self.store, load_lap_fixture(str(self.context.fixture_path)),
                                        ReplayBaselineModel(), self.policy, replay_context=self.context)
        asyncio.run(processor.process_once())
        for event in self.store.pending_outbox():
            self.result = json.loads(event.payload_json)
            self.store.mark_outbox_published(event.event_id)

    def observe(self, job_id: str, batch_id: int) -> dict[str, Any]:
        """读本地 SQLite；REST 与 MySQL 部分只返回显式模拟证据。"""
        assert self.store is not None
        with self.store._connect() as connection:
            inbox = connection.execute("SELECT * FROM request_inbox WHERE prediction_job_id = ?", (job_id,)).fetchone()
            inbox_count = connection.execute("SELECT COUNT(*) FROM request_inbox").fetchone()[0]
            outbox_count = connection.execute("SELECT COUNT(*) FROM terminal_outbox").fetchone()[0]
            published_count = connection.execute("SELECT COUNT(*) FROM terminal_outbox WHERE published_at IS NOT NULL").fetchone()[0]
        frozen = json.loads(inbox["feature_snapshot_json"])
        succeeded = "selectedOptions" in self.result
        status = "SUCCEEDED" if succeeded else "FAILED"
        return {"workerAlive": True, "job": {"predictionJobId": job_id, "status": status},
                "batch": {"batchId": batch_id, "status": "COMPLETED", "questionCount": 1},
                "outcome": {"predictionJobId": job_id, "status": status, "failure": None,
                            "result": {"selectedOptions": self.result.get("selectedOptions", []),
                                       "confidence": self.result.get("confidence")}},
                "sql": {"predictionJobId": job_id, "batchId": batch_id, "resultCount": int(succeeded),
                        "receiptCount": 1, "failureCount": int(not succeeded)},
                "sqlite": {"predictionJobId": job_id, "inboxCount": inbox_count, "outboxCount": outbox_count,
                           "publishedCount": published_count, "status": inbox["status"],
                           "manifestHash": frozen["manifestHash"], "featureHash": canonical_sha256(frozen),
                           "dataCutoff": self.message["payload"]["dataCutoff"],
                           "executionMode": frozen["executionMode"], "sportingCutoff": frozen["sportingCutoff"],
                           "importCompletedAt": frozen["importCompletedAt"], "sourceFirstSeenStatus": frozen["sourceFirstSeenStatus"],
                           "modelVersion": self.policy.model_version, "promptVersion": self.policy.prompt_version,
                           "featureVersion": self.policy.feature_version}}

    def original_message(self, job_id: str) -> dict[str, Any]:
        """返回相同合成请求的副本，保留消息标识与内容。"""
        return copy.deepcopy(self.message)

    def replay(self, message: dict[str, Any]) -> None:
        """再次保存原请求，由已有 inbox 去重而不是创建另一结果。"""
        assert self.store is not None
        request = PredictionRequestV2.model_validate(message["payload"])
        self.store.save(request)
        self.replay_count += 1
        self._process()

    def restart_worker(self) -> None:
        """重新打开同一 SQLite 并扫描任务，模拟恢复但不启动进程。"""
        self.store = InboxStore(self.directory / "simulation.sqlite")
        self.restart_count += 1
        self._process()

    def owned_processes(self) -> list[dict[str, Any]]:
        """模拟运行从未启动外部进程。"""
        return []

    def stop_process(self, process: dict[str, Any]) -> None:
        """拒绝停止真实进程，避免把合成 PID 当实际所有权。"""
        raise WorkflowBlocked("模拟流程从未启动外部进程")


def run_simulation(directory: Path, sql_root: Path) -> dict[str, Any]:
    """只调用本地包、SQLite、替身；输出明确的模拟验收报告。"""
    # 延迟导入包写入器，静态 inventory 不需要任何新依赖或运行资源。
    from f1_predict.replay.bundle import capture_staging, seal_bundle

    now = datetime.now(UTC).replace(microsecond=0)
    source, identity, rows, sporting_cutoff = synthetic_capture(now)
    staging = capture_staging(directory, source, rows, identity, sporting_cutoff,
                              clock=lambda: now)
    target = SimulationTarget(directory)
    ticks = [0.0]

    def wait(seconds: float) -> None:
        ticks[0] += seconds

    workflow = ReplayWorkflow(StateStore(directory), target, PROJECT, clock=lambda: datetime.now(UTC),
                              monotonic=lambda: ticks[0], wait=wait)
    workflow.prepare(sql_root)
    mapping = workflow.load_capture(source, identity, staging.capture_hash)
    sealed = seal_bundle(staging, directory / "bundle",
                         {key: value for key, value in mapping.items() if key != "seasonId"})
    target.configure_bundle(sealed)
    workflow.bind_bundle(sealed.manifest_hash, sealed.policy.model_dump(mode="json"),
                         staging.import_completed_at, sporting_cutoff)
    workflow.submit()
    workflow.wait_terminal()
    workflow.exercise_recovery()
    report = workflow.report()
    workflow.stop()
    return {**report, "stage": "SIMULATED_COMPLETE", "stopped": True,
            "schemaSteps": target.scripts, "localSqliteUsed": True,
            "source": "SYNTHETIC_ONLY", "postCalls": target.posts,
            "simulatedReplays": target.replay_count, "simulatedWorkerRestarts": target.restart_count,
            "limitations": ["未连接MySQL/Rabbit/HTTP", "未验证驱动连通/真实DDL/真实资源所有权", "非M1/M2验收"]}
