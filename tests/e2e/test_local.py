"""纯 stdlib 协议夹具与安全门禁单测；不需要 Docker 或网络外连。"""

from __future__ import annotations

import asyncio
import importlib
import json
import subprocess
import sys
import tempfile
import time
import unittest
from datetime import datetime
from http.server import ThreadingHTTPServer
from io import BytesIO, StringIO
from pathlib import Path
from threading import Thread
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import run
from stub import (
    CUTOFF,
    GAMEDAY,
    MEETING,
    MODEL_VERSION,
    OPTIONS,
    PRACTICE_SESSION,
    QUESTION_TEXT,
    Handler,
    audited_laps,
    feed_payloads,
)


class _FakeClock:
    def __init__(self) -> None:
        self.now = 0.0
        self.sleeps: list[float] = []

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


class _FakeHeaders:
    def __init__(self, content_length: str | None) -> None:
        self.content_length = content_length
        self.reads = 0

    def get(self, name: str, default: str | None = None) -> str | None:
        self.reads += 1
        if name != "Content-Length":
            raise AssertionError(f"unexpected header: {name}")
        return self.content_length if self.content_length is not None else default


class _FakeConnection:
    def __init__(self) -> None:
        self.timeout: float | None = None
        self.timeouts: list[float | None] = []

    def gettimeout(self) -> float | None:
        return self.timeout

    def settimeout(self, timeout: float | None) -> None:
        self.timeouts.append(timeout)
        self.timeout = timeout


class _FakeInput:
    def __init__(self, body: bytes = b"", error: Exception | None = None) -> None:
        self.body = body
        self.error = error
        self.read_sizes: list[int] = []
        self.connection: _FakeConnection | None = None

    def read(self, size: int) -> bytes:
        self.read_sizes.append(size)
        if self.connection is None or self.connection.timeout is None:
            raise AssertionError("body read without a socket timeout")
        if self.error is not None:
            raise self.error
        chunk = self.body[:size]
        self.body = self.body[len(chunk):]
        return chunk

    def read1(self, size: int) -> bytes:
        return self.read(size)


class _SlowDripInput(_FakeInput):
    def __init__(self, clock: _FakeClock) -> None:
        super().__init__(b"abc")
        self.clock = clock
        self.offset = 0

    def read1(self, size: int) -> bytes:
        self.read_sizes.append(size)
        if self.connection is None or self.connection.timeout is None:
            raise AssertionError("body read without a socket timeout")
        delay = 0.75
        if delay > self.connection.timeout:
            self.clock.now += self.connection.timeout
            raise TimeoutError("absolute body deadline")
        self.clock.now += delay
        chunk = self.body[self.offset:self.offset + 1]
        self.offset += len(chunk)
        return chunk


class _RecordingHandler(Handler):
    def __init__(self, path: str, content_length: str | None,
                 body: bytes = b"", read_error: Exception | None = None) -> None:
        self.path = path
        self.headers = _FakeHeaders(content_length)
        self.connection = _FakeConnection()
        self.rfile = _FakeInput(body, read_error)
        self.rfile.connection = self.connection
        self.wfile = BytesIO()
        self.close_connection = False
        self.status: int | None = None
        self.response_headers: dict[str, str] = {}

    def send_response(self, status: int, *args: object) -> None:
        self.status = status

    def send_header(self, name: str, value: str) -> None:
        self.response_headers[name] = value

    def end_headers(self) -> None:
        return


class IsolatedFixtureTest(unittest.TestCase):
    def test_feed_and_audited_laps_are_consistent(self) -> None:
        payloads = feed_payloads()
        questions = payloads[f"/feeds/questions/questions_{GAMEDAY}_en.json"]
        value = questions["Data"]["Value"]["Questions"]
        self.assertEqual([item["Id"] for item in value], [91101, 91102])
        self.assertEqual({option["Id"] for option in value[0]["Options"]},
                         {option[0] for option in OPTIONS})
        self.assertEqual({row["meetingKey"] for row in audited_laps()}, {MEETING})
        self.assertEqual({row["sessionKey"] for row in audited_laps()}, {PRACTICE_SESSION})
        cutoff = datetime.fromisoformat(CUTOFF)
        rows = audited_laps()
        late_rows = [row for row in rows
                     if datetime.fromisoformat(row["firstSeenAt"]) > cutoff
                     or datetime.fromisoformat(row["lapEnd"]) > cutoff]
        self.assertEqual([row["recordId"] for row in late_rows],
                         ["synthetic-post-cutoff-driver-11"])
        post_cutoff = late_rows[0]
        self.assertLessEqual(datetime.fromisoformat(post_cutoff["lapEnd"]), cutoff)
        self.assertGreater(datetime.fromisoformat(post_cutoff["firstSeenAt"]), cutoff)
        for row in rows:
            if row not in late_rows:
                self.assertLessEqual(datetime.fromisoformat(row["firstSeenAt"]), cutoff)
                self.assertLessEqual(datetime.fromisoformat(row["lapEnd"]), cutoff)
            duration = (datetime.fromisoformat(row["lapEnd"])
                        - datetime.fromisoformat(row["eventTime"])).total_seconds()
            self.assertAlmostEqual(duration, row["durationSeconds"], places=3)

    def test_http_stub_rejects_unknown_and_returns_valid_model_candidate(self) -> None:
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            root = f"http://127.0.0.1:{server.server_port}"
            with urlopen(root + "/feeds/schedule/raceday_en.json") as response:
                self.assertEqual(len(json.load(response)["Data"]["Value"]), 2)
            context = {
                "question": QUESTION_TEXT,
                "options": [{"optionId": item[0]} for item in OPTIONS],
                "features": {"drivers": [
                    {"driverNumber": 11, "cleanLapCount": 3, "medianLapSeconds": 91.3},
                    {"driverNumber": 22, "cleanLapCount": 3, "medianLapSeconds": 92.0},
                ]},
            }

            def post_model(value: dict) -> object:
                body = json.dumps({"model": MODEL_VERSION,
                                   "messages": [{"role": "system", "content": "ignored"},
                                                {"role": "user", "content": json.dumps(value)}]}).encode()
                return urlopen(Request(root + "/model", data=body,
                                       headers={"Content-Type": "application/json"}))

            with post_model(context) as response:
                candidate = json.loads(json.load(response)["choices"][0]["message"]["content"])
            self.assertEqual(candidate["option_ids"], [OPTIONS[0][0]])
            leaked = json.loads(json.dumps(context))
            leaked["features"]["drivers"][0]["cleanLapCount"] = 4
            with self.assertRaises(HTTPError) as rejected:
                post_model(leaked)
            self.assertEqual(rejected.exception.code, 400)
            rejected.exception.close()
            with self.assertRaises(HTTPError) as missing:
                urlopen(root + "/unlisted")
            missing.exception.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)

    def test_wait_worker_ready_retries_timed_out_probe_without_passing_secrets(self) -> None:
        clock = _FakeClock()
        worker = Mock()
        worker.poll.return_value = None
        calls: list[dict[str, object]] = []
        commands: list[list[str]] = []
        environment = {
            "PATH": "/usr/bin:/bin",
            "HOME": "/home/test",
            "F1_PREDICT_CONSUMER_SQLITE_PATH": "/private/inbox.sqlite",
            "F1_PREDICT_RABBITMQ_URL": "amqp://e2e:private@127.0.0.1/",
            "E2E_MYSQL_PASSWORD": "private-db-password",
            "SPRING_APPLICATION_JSON": "private-config",
        }

        def probe(args: list[str], **kwargs: object) -> subprocess.CompletedProcess:
            commands.append(args)
            calls.append(kwargs)
            if len(calls) == 1:
                if "timeout" not in kwargs:
                    raise AssertionError("readiness subprocess has no timeout")
                clock.now += 0.25
                raise subprocess.TimeoutExpired(args, kwargs["timeout"])
            return subprocess.CompletedProcess(args, 0)

        with (patch.object(run.time, "monotonic", clock.monotonic),
              patch.object(run.time, "sleep", clock.sleep),
              patch.object(run.subprocess, "run", side_effect=probe)):
            run.wait_worker_ready(worker, environment, seconds=8)

        self.assertEqual(len(calls), 2)
        self.assertEqual(commands[0][0],
                         str(run.PYTHON_ROOT / ".venv" / "bin" / "python"))
        self.assertEqual(commands[0][1:], ["-m", "f1_predict.worker.main", "--check-ready"])
        self.assertEqual(calls[0]["timeout"], 5)
        self.assertEqual(calls[1]["timeout"], 5)
        self.assertEqual(clock.sleeps, [0.8])
        probe_environment = calls[0]["env"]
        self.assertEqual(probe_environment["F1_PREDICT_CONSUMER_SQLITE_PATH"],
                         "/private/inbox.sqlite")
        self.assertNotIn("F1_PREDICT_RABBITMQ_URL", probe_environment)
        self.assertNotIn("E2E_MYSQL_PASSWORD", probe_environment)
        self.assertNotIn("SPRING_APPLICATION_JSON", probe_environment)
        self.assertGreaterEqual(worker.poll.call_count, 3)

    def test_wait_worker_ready_rejects_nonpositive_budget_without_probe(self) -> None:
        worker = Mock()
        for seconds in (0, -1):
            with (self.subTest(seconds=seconds),
                  patch.object(run.subprocess, "run") as probe,
                  self.assertRaisesRegex(run.E2EError, "正数")):
                run.wait_worker_ready(worker, {}, seconds=seconds)
                probe.assert_not_called()
        worker.poll.assert_not_called()

    def test_wait_worker_ready_does_not_probe_after_deadline_or_sleep_past_it(self) -> None:
        clock = _FakeClock()
        worker = Mock()
        worker.poll.return_value = None

        def timed_out(args: list[str], **kwargs: object) -> subprocess.CompletedProcess:
            if "timeout" not in kwargs:
                raise AssertionError("readiness subprocess has no timeout")
            clock.now += kwargs["timeout"]
            raise subprocess.TimeoutExpired(args, kwargs["timeout"])

        with (patch.object(run.time, "monotonic", clock.monotonic),
              patch.object(run.time, "sleep", clock.sleep),
              patch.object(run.subprocess, "run", side_effect=timed_out) as probe,
              self.assertRaisesRegex(run.E2EError, "未就绪")):
            run.wait_worker_ready(worker, {}, seconds=0.25)
        self.assertEqual(probe.call_count, 1)
        self.assertEqual(probe.call_args.kwargs["timeout"], 0.25)
        self.assertEqual(clock.sleeps, [])

        clock = _FakeClock()
        worker = Mock()
        worker.poll.return_value = None

        def not_ready(args: list[str], **kwargs: object) -> subprocess.CompletedProcess:
            clock.now += 0.8
            return subprocess.CompletedProcess(args, 1)

        with (patch.object(run.time, "monotonic", clock.monotonic),
              patch.object(run.time, "sleep", clock.sleep),
              patch.object(run.subprocess, "run", side_effect=not_ready) as probe,
              self.assertRaisesRegex(run.E2EError, "未就绪")):
            run.wait_worker_ready(worker, {}, seconds=1)
        self.assertEqual(probe.call_count, 1)
        self.assertEqual(len(clock.sleeps), 1)
        self.assertAlmostEqual(clock.sleeps[0], 0.2)

    def test_wait_worker_ready_checks_worker_before_probe_and_success(self) -> None:
        worker = Mock()
        worker.returncode = 7
        worker.poll.return_value = 7
        with (patch.object(run.subprocess, "run") as probe,
              self.assertRaisesRegex(run.E2EError, "提前退出")):
            run.wait_worker_ready(worker, {}, seconds=1)
        probe.assert_not_called()

        worker = Mock()
        worker.returncode = 7
        worker.poll.side_effect = [None, 7]
        with (patch.object(run.subprocess, "run",
                           return_value=subprocess.CompletedProcess([], 0)) as probe,
              self.assertRaisesRegex(run.E2EError, "提前退出")):
            run.wait_worker_ready(worker, {}, seconds=1)
        probe.assert_called_once()
        self.assertEqual(worker.poll.call_count, 2)

    def test_stub_rejects_invalid_lengths_without_reading_body(self) -> None:
        cases = ((None, 400), ("", 400), ("nope", 400), ("-1", 400),
                 ("0", 400), ("32769", 413))
        for content_length, expected_status in cases:
            with self.subTest(content_length=content_length):
                handler = _RecordingHandler("/model", content_length, b"unread")
                handler.do_POST()
                self.assertEqual(handler.status, expected_status)
                self.assertEqual(handler.headers.reads, 1)
                self.assertEqual(handler.rfile.read_sizes, [])
                self.assertTrue(handler.close_connection)
                self.assertEqual(handler.response_headers["Connection"], "close")

    def test_stub_checks_path_before_reading_unknown_post_body(self) -> None:
        handler = _RecordingHandler("/not-model", "malformed", b"unread")
        handler.do_POST()
        self.assertEqual(handler.status, 404)
        self.assertEqual(handler.headers.reads, 0)
        self.assertEqual(handler.rfile.read_sizes, [])
        self.assertTrue(handler.close_connection)
        self.assertEqual(handler.response_headers["Connection"], "close")

    def test_stub_closes_short_stalled_and_invalid_utf8_bodies(self) -> None:
        cases = (("4", b"{}", None, [4, 2]),
                 ("2", b"", TimeoutError("stalled"), [2]),
                 ("1", bytes((255,)), None, [1]))
        for content_length, body, error, expected_reads in cases:
            with self.subTest(content_length=content_length, error=error):
                handler = _RecordingHandler("/model", content_length, body, error)
                handler.do_POST()
                self.assertEqual(handler.status, 400)
                self.assertEqual(handler.rfile.read_sizes, expected_reads)
                self.assertEqual(len(handler.connection.timeouts), len(expected_reads) + 1)
                for timeout in handler.connection.timeouts[:-1]:
                    self.assertAlmostEqual(timeout, 2, places=4)
                self.assertIsNone(handler.connection.timeouts[-1])
                self.assertTrue(handler.close_connection)
                self.assertEqual(handler.response_headers["Connection"], "close")

    def test_stub_body_read_obeys_absolute_deadline_when_bytes_keep_arriving(self) -> None:
        clock = _FakeClock()
        handler = _RecordingHandler("/model", "3")
        stream = _SlowDripInput(clock)
        stream.connection = handler.connection
        handler.rfile = stream

        with patch.object(time, "monotonic", clock.monotonic):
            handler.do_POST()

        self.assertEqual(handler.status, 400)
        self.assertEqual(stream.read_sizes, [3, 2, 1])
        self.assertEqual(len(handler.connection.timeouts), 4)
        self.assertAlmostEqual(handler.connection.timeouts[0], 2)
        self.assertAlmostEqual(handler.connection.timeouts[1], 1.25)
        self.assertAlmostEqual(handler.connection.timeouts[2], 0.5)
        self.assertIsNone(handler.connection.timeouts[3])
        self.assertAlmostEqual(clock.now, 2)
        self.assertTrue(handler.close_connection)

    def test_wait_outcomes_checks_all_paginated_jobs(self) -> None:
        batch = {"batchId": "batch-1", "jobIds": ["job-1", "job-2"]}
        outcomes = {
            "job-1": {"status": "SUCCEEDED", "result": {"selectedOptions": [
                {"optionId": OPTIONS[0][0], "position": 1}]}},
            "job-2": {"status": "SUCCEEDED", "result": {"selectedOptions": [
                {"optionId": OPTIONS[0][0], "position": 1}]}},
        }
        pages = {
            "/api/v1/prediction-batches/batch-1/jobs?page=0&size=100": {
                "page": 0, "size": 100, "total": 2,
                "items": [{"predictionJobId": "job-1", "questionId": "q-1",
                           "status": "SUCCEEDED"}],
            },
            "/api/v1/prediction-batches/batch-1/jobs?page=1&size=100": {
                "page": 1, "size": 100, "total": 2,
                "items": [{"predictionJobId": "job-2", "questionId": "q-2",
                           "status": "SUCCEEDED"}],
            },
        }

        def respond(method: str, path: str) -> object:
            if path.endswith("/outcome"):
                return outcomes[path.rsplit("/", 2)[-2]]
            if path in pages:
                return pages[path]
            if path.endswith("/prediction-batches/batch-1"):
                return {"statusCounts": {"succeededCount": 2, "failedCount": 0}}
            raise AssertionError(f"unexpected request: {method} {path}")

        with patch.object(run, "http", side_effect=respond) as request:
            run.wait_outcomes(batch, {"q-1": "SUCCEEDED", "q-2": "SUCCEEDED"})
        requested_pages = [call.args[1] for call in request.call_args_list
                           if "/jobs?" in call.args[1]]
        self.assertEqual(requested_pages, list(pages))

    def test_wait_outcomes_rejects_missing_job_from_listing(self) -> None:
        batch = {"batchId": "batch-1", "jobIds": ["job-1", "job-2"]}
        outcome = {"status": "SUCCEEDED", "result": {"selectedOptions": [
            {"optionId": OPTIONS[0][0], "position": 1}]}}

        def respond(method: str, path: str) -> object:
            if path.endswith("/outcome"):
                return outcome
            if "/jobs?" in path:
                return {"page": 0, "size": 100, "total": 1,
                        "items": [{"predictionJobId": "job-1", "questionId": "q-1",
                                   "status": "SUCCEEDED"}]}
            raise AssertionError(f"unexpected request: {method} {path}")

        with (patch.object(run, "http", side_effect=respond),
              self.assertRaisesRegex(run.E2EError, "任务总数与 jobIds 不一致")):
            run.wait_outcomes(batch, {"q-1": "SUCCEEDED", "q-2": "SUCCEEDED"})

    def test_docker_context_rejects_remote_daemon(self) -> None:
        with (patch.dict("os.environ", {"DOCKER_HOST": "tcp://remote.invalid:2375"}),
              self.assertRaisesRegex(run.E2EError, "禁止远端 Docker")):
            run.ensure_local_docker()
        with (patch.dict("os.environ", {"DOCKER_CONFIG": "/other/docker-config"}),
              self.assertRaisesRegex(run.E2EError, "禁止远端 Docker")):
            run.ensure_local_docker()
        with (patch.dict("os.environ", {}, clear=True),
              patch.object(run, "command", return_value='"tcp://remote.invalid:2375"'),
              self.assertRaisesRegex(run.E2EError, "不是本地 Unix socket")):
            run.ensure_local_docker()

    def test_state_rejects_unsafe_project_before_docker(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            location = Path(folder)
            path = location / "state.json"
            env = location / "compose.env"
            env.write_text("private=test\n", encoding="utf-8")
            env.chmod(0o600)
            path.write_text(json.dumps({"project": "production", "schema": "ready"}),
                            encoding="utf-8")
            path.chmod(0o600)
            with patch.object(run, "STATE_PATH", path), patch.object(run, "ENV_PATH", env):
                with self.assertRaisesRegex(run.E2EError, "项目标识不合法"):
                    run.state()
                path.write_text(json.dumps({"project": "f1predict_e2e_0123456789abcd",
                                            "schema": "ready"}), encoding="utf-8")
                env.chmod(0o644)
                with self.assertRaisesRegex(run.E2EError, "权限过宽"):
                    run.state()

    def test_application_environment_drops_jvm_and_production_overrides(self) -> None:
        malicious = {
            "PATH": "/usr/bin:/bin", "HOME": "/home/test",
            "_JAVA_OPTIONS": "-Dspring.datasource.url=jdbc:mysql://non-isolated.invalid/db",
            "JAVA_TOOL_OPTIONS": "-Dspring.config.import=somewhere",
            "JDK_JAVA_OPTIONS": "-Dspring.rabbitmq.host=non-isolated.invalid",
            "MAVEN_OPTS": "-Dspring.config.location=somewhere",
            "SPRING_APPLICATION_JSON": '{"spring":{"datasource":{"url":"somewhere"}}}',
            "F1_PREDICT_RABBITMQ_URL": "amqp://non-isolated.invalid/",
        }
        with patch.dict("os.environ", malicious, clear=True):
            self.assertEqual(run.isolated_environment(),
                             {"PATH": "/usr/bin:/bin", "HOME": "/home/test"})

    def test_prepare_only_starts_isolated_containers(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            runtime = Path(folder) / "runtime"
            with (
                patch.object(run, "STATE_DIR", runtime),
                patch.object(run, "STATE_PATH", runtime / "state.json"),
                patch.object(run, "ENV_PATH", runtime / "compose.env"),
                patch.object(run, "ensure_local_docker"),
                patch.object(run, "verify_containers"),
                patch.object(run, "command", return_value="") as execute,
            ):
                run.prepare()
                self.assertEqual(run.state()["schema"], "started")
                self.assertEqual(len(execute.call_args_list), 1)
                self.assertIn("up", execute.call_args.args[0])

    def test_database_password_is_not_in_docker_arguments(self) -> None:
        with (
            patch.object(run, "credentials", return_value=("private-db-password", "broker-password")),
            patch.object(run, "command", return_value="1\n") as execute,
        ):
            run.mysql_command({"project": "f1predict_e2e_0123456789abcd"}, ["-N", "-e", "SELECT 1"])
        args = execute.call_args.args[0]
        self.assertNotIn("private-db-password", " ".join(args))
        self.assertEqual(args[args.index("-e") + 1], "MYSQL_PWD")
        self.assertEqual(execute.call_args.kwargs["env"]["MYSQL_PWD"], "private-db-password")

    def test_schema_requires_explicit_confirmation_and_rejects_repeat(self) -> None:
        project = "f1predict_e2e_0123456789abcd"
        with (
            patch.object(run, "state", return_value={"project": project, "schema": "started"}),
            patch.object(run, "verify_containers") as inspect,
            self.assertRaisesRegex(run.E2EError, "显式传"),
        ):
            run.apply_schema(project, False)
            inspect.assert_not_called()
        with (
            patch.object(run, "state", return_value={"project": project, "schema": "ready"}),
            patch.object(run, "verify_containers") as inspect,
            self.assertRaisesRegex(run.E2EError, "仅可对未初始化"),
        ):
            run.apply_schema(project, True)
            inspect.assert_not_called()

    def test_replay_rejects_non_isolated_broker_before_publish(self) -> None:
        # 系统 Python 不需安装 aio-pika；本测试只验证连接前的拒绝分支。
        with patch.dict(sys.modules, {"aio_pika": Mock()}):
            replay = importlib.import_module("replay")
        with (
            patch.object(replay, "state", return_value={"schema": "ready"}),
            patch.object(replay, "verify_containers"),
            patch.object(replay, "credentials", return_value=("local-db", "local-broker")),
            patch.dict("os.environ", {"F1_PREDICT_RABBITMQ_URL": "amqp://non-isolated.invalid/"}),
            patch.object(sys, "stdin", StringIO('{"messageId":"original","body":"{}"}')),
            self.assertRaisesRegex(run.E2EError, "仅允许连接当前隔离项目"),
        ):
            asyncio.run(replay.main())


if __name__ == "__main__":
    unittest.main()
