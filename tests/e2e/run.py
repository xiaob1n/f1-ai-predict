"""隔离真实 MySQL/Rabbit 联调；不会连接仓库默认 Nacos 或外部 Feed。"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import secrets
import signal
import socket
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from stub import (
    CUTOFF,
    FEATURE_VERSION,
    GAMEDAY,
    MEETING,
    MODEL_VERSION,
    OPTIONS,
    PRACTICE_SESSION,
    PROMPT_VERSION,
    QUESTION_TEXT,
    UNSUPPORTED_TEXT,
    audited_laps,
)

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
STATE_DIR = HERE / ".runtime"
STATE_PATH = STATE_DIR / "state.json"
ENV_PATH = STATE_DIR / "compose.env"
COMPOSE = ROOT / "deploy/e2e/compose.yaml"
JAVA_CONFIG = ROOT / "deploy/e2e/application-e2e.yaml"
JAVA_ROOT = ROOT / "f1aipredict"
PYTHON_ROOT = ROOT / "python-agent-worker"
JAVA_API = "http://127.0.0.1:18780"
WORKER_PYTHON = PYTHON_ROOT / ".venv" / "bin" / "python"
WORKER_READY_PROBE_TIMEOUT = 5
WORKER_READY_RETRY_INTERVAL = 0.8


class E2EError(RuntimeError):
    """联调不满足明确前置条件或最终一致性断言。"""


def check_runtime_dir() -> None:
    """隔离状态只能位于当前用户拥有且不可供其他用户访问的真实目录。"""
    if STATE_DIR.is_symlink():
        raise E2EError("隔离运行目录不得为符号链接")
    if STATE_DIR.exists():
        info = STATE_DIR.stat()
        if not STATE_DIR.is_dir() or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise E2EError("隔离运行目录权限或所有者不安全")


def isolated_environment() -> dict[str, str]:
    """应用进程仅保留运行工具所需的宿主环境，不继承连接及 JVM 注入项。"""
    allowed = ("PATH", "HOME", "USER", "TMPDIR", "LANG", "LC_ALL", "JAVA_HOME")
    return {key: os.environ[key] for key in allowed if key in os.environ}


def ensure_free_port(port: int) -> None:
    """拒绝把旧进程的 HTTP 响应误判成本次应用就绪。"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(("127.0.0.1", port))
        except OSError as error:
            raise E2EError(f"本地端口 {port} 已被占用，拒绝使用既有服务") from error


def state() -> dict:
    check_runtime_dir()
    if not STATE_PATH.is_file():
        raise E2EError("尚未 prepare；不会自动启动或操作其他 Docker 项目")
    result = json.loads(STATE_PATH.read_text(encoding="utf-8"))
    project = result.get("project", "")
    if not re.fullmatch(r"f1predict_e2e_[0-9a-f]{14}", project):
        raise E2EError("项目标识不合法；拒绝执行 Docker 操作")
    if STATE_PATH.is_symlink() or ENV_PATH.is_symlink():
        raise E2EError("运行状态文件不得为符号链接")
    if (ENV_PATH.stat().st_mode | STATE_PATH.stat().st_mode) & 0o077:
        raise E2EError("隔离运行文件权限过宽；拒绝使用")
    return result


def save(value: dict) -> None:
    tmp = STATE_DIR / "state.tmp"
    tmp.write_text(json.dumps(value, indent=2), encoding="utf-8")
    os.chmod(tmp, 0o600)
    tmp.replace(STATE_PATH)


def compose_args(value: dict) -> list[str]:
    return ["docker", "compose", "-p", value["project"], "--env-file", str(ENV_PATH),
            "-f", str(COMPOSE)]


def command(args: list[str], *, cwd: Path = ROOT, env: dict | None = None,
            input_text: str | None = None, timeout: int = 90) -> str:
    # Compose 仅从专用 0600 env 文件读凭据，忽略父进程同名变量/项目覆盖。
    if args[0] == "docker" and env is None:
        env = {key: val for key, val in os.environ.items()
               if not key.startswith(("E2E_", "COMPOSE_"))}
    result = subprocess.run(args, cwd=cwd, env=env, input=input_text, text=True,
                            capture_output=True, timeout=timeout, check=False)
    if result.returncode:
        raise E2EError(f"命令执行失败 ({args[0]}，exit {result.returncode}): "
                       f"{(result.stderr or result.stdout)[-1200:]}")
    return result.stdout


def ensure_local_docker() -> None:
    """拒绝 DOCKER_HOST/CONTEXT 指向外部主机；专属项目也不得连远端 daemon。"""
    if any(os.environ.get(key) for key in ("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG")):
        raise E2EError("请先清除 DOCKER_HOST/DOCKER_CONTEXT/DOCKER_CONFIG，禁止远端 Docker")
    host = json.loads(command(["docker", "context", "inspect", "--format",
                               "{{json .Endpoints.docker.Host}}"])).strip()
    if not host.startswith("unix://"):
        raise E2EError("Docker 当前上下文不是本地 Unix socket")


def verify_containers(value: dict) -> None:
    """只有本地 compose 项目、服务和显式 E2E 标签三重匹配才允许操作。"""
    ensure_local_docker()
    for service in ("mysql", "rabbitmq"):
        ids = command(compose_args(value) + ["ps", "-a", "-q", service]).strip().splitlines()
        if len(ids) != 1 or not ids[0]:
            raise E2EError(f"缺少独立 {service} 容器，不执行数据库或 Broker 操作")
        labels = command(["docker", "inspect", "-f", "{{json .Config.Labels}}", ids[0]])
        found = json.loads(labels)
        if (found.get("com.docker.compose.project") != value["project"]
                or found.get("com.docker.compose.service") != service
                or found.get("org.f1predict.scope") != "isolated-e2e"):
            raise E2EError(f"{service} 项目/服务/隔离标签不匹配")
        ports = json.loads(command(["docker", "inspect", "-f", "{{json .HostConfig.PortBindings}}", ids[0]]))
        expected = ({"3306/tcp": "13306"} if service == "mysql"
                    else {"5672/tcp": "15673", "15672/tcp": "15672"})
        if set(ports) != set(expected):
            raise E2EError(f"{service} 端口映射不匹配，拒绝操作")
        for target, bindings in ports.items():
            if len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1" or bindings[0]["HostPort"] != expected[target]:
                raise E2EError(f"{service} 端口不是预期回环绑定，拒绝操作")


def prepare() -> None:
    ensure_local_docker()
    check_runtime_dir()
    if STATE_PATH.exists() or STATE_PATH.is_symlink():
        value = state()
        if value.get("schema") == "applying":
            raise E2EError("上次 DDL 未完成；保留现场，禁止自动重试非幂等迁移")
        verify_containers(value)
        command(compose_args(value) + ["up", "-d", "--wait", "--no-recreate"], timeout=180)
        verify_containers(value)
        print(f"隔离容器已准备：项目 {value['project']}，schema={value['schema']}；卷保持不变")
        return
    STATE_DIR.mkdir(mode=0o700, exist_ok=True)
    check_runtime_dir()
    value = {"project": "f1predict_e2e_" + secrets.token_hex(7), "schema": "new"}
    # 本目录的两个固定文件若已存在但状态丢失，不覆盖已有凭据或 DDL 现场。
    if ENV_PATH.exists() or ENV_PATH.is_symlink():
        raise E2EError("已有隔离密码文件但缺状态；请人工核查，不自动覆盖")
    # 文件从创建时即为 0600，不向命令行或日志输出本地随机密码。
    mysql_password, rabbit_password = secrets.token_hex(20), secrets.token_hex(20)
    descriptor = os.open(ENV_PATH, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as env_file:
        env_file.write(f"E2E_MYSQL_PASSWORD={mysql_password}\nE2E_RABBIT_PASSWORD={rabbit_password}\n")
    save(value)
    try:
        command(compose_args(value) + ["up", "-d", "--wait"], timeout=180)
        verify_containers(value)
        value["schema"] = "started"
        save(value)
        print(f"隔离容器就绪：项目 {value['project']}；请单独审查并显式应用数据库脚本")
    except Exception:
        print("准备失败；保留容器和卷供人工核查，不自动清理", file=sys.stderr)
        raise


def mysql_command(value: dict, args: list[str], *, input_text: str | None = None) -> str:
    """通过专用子进程环境传入密码，命令行只包含变量名。"""
    password, _ = credentials()
    environment = isolated_environment()
    environment["MYSQL_PWD"] = password
    return command(compose_args(value) + ["exec", "-T", "-e", "MYSQL_PWD", "mysql",
                                          "mysql", "-u", "root", *args],
                   env=environment, input_text=input_text, timeout=90)


def apply_schema(project: str, confirmed: bool) -> None:
    """数据库迁移仅由单独的显式操作执行，不属于 prepare、run 或应用启动。"""
    value = state()
    if not confirmed or project != value["project"]:
        raise E2EError("先核对项目名，再显式传 --project 和 --confirm-isolated-schema")
    if value.get("schema") != "started":
        raise E2EError("DDL 仅可对未初始化的独立项目执行一次；保留已有数据库")
    verify_containers(value)
    existing = mysql_command(value, ["-N", "-B", "f1_ai_predict", "-e", "SHOW TABLES"])
    if existing.strip():
        raise E2EError("隔离数据库并非空库；拒绝自动补跑非幂等 DDL")
    value["schema"] = "applying"
    save(value)
    try:
        for number in range(1, 9):
            files = sorted((ROOT / "sql").glob(f"{number:03d}_*.sql"))
            if len(files) != 1:
                raise E2EError(f"DDL {number:03d} 缺失或重复")
            # 007 无 USE，仍只向核验过的独立 MySQL 的 f1_ai_predict 数据库发送脚本。
            mysql_command(value, ["f1_ai_predict"], input_text=files[0].read_text(encoding="utf-8"))
        value["schema"] = "ready"
        save(value)
        print(f"隔离库初始化完成：项目 {value['project']}；DDL 001–008 不会再次自动执行")
    except Exception:
        print("DDL 未完成；保留现场和卷供人工核查，禁止自动重试", file=sys.stderr)
        raise


def credentials() -> tuple[str, str]:
    variables = dict(line.split("=", 1) for line in ENV_PATH.read_text().splitlines())
    return variables["E2E_MYSQL_PASSWORD"], variables["E2E_RABBIT_PASSWORD"]


def sql_query(value: dict, statement: str) -> list[list[str]]:
    if value["schema"] != "ready":
        raise E2EError("DDL 不完整")
    verify_containers(value)
    output = mysql_command(value, ["-N", "-B", "--raw", "f1_ai_predict", "-e", statement])
    return [line.split("\t") for line in output.splitlines()]


def rabbit_request_queue(value: dict) -> dict:
    """从已核验的本地 Broker 查询请求队列 ACK 计数与积压。"""
    verify_containers(value)
    _, password = credentials()
    token = base64.b64encode(f"e2e:{password}".encode()).decode("ascii")
    request = Request("http://127.0.0.1:15672/api/queues/e2e/f1.prediction.request.v2",
                      headers={"Authorization": f"Basic {token}"})
    with urlopen(request, timeout=5) as response:
        return json.load(response)


def http(method: str, path: str, payload: dict | None = None) -> object:
    data = json.dumps(payload).encode() if payload is not None else None
    request = Request(JAVA_API + path, data=data, method=method,
                      headers={"Content-Type": "application/json"})
    try:
        with urlopen(request, timeout=5) as response:
            return json.load(response)
    except HTTPError as error:
        raise E2EError(f"{method} {path}: HTTP {error.code} {error.read(600)!r}") from error


def wait_ready(url: str, process: subprocess.Popen, label: str, seconds: int = 100) -> None:
    until = time.monotonic() + seconds
    last_error: Exception | None = None
    while time.monotonic() < until:
        if process.poll() is not None:
            raise E2EError(f"{label} 提前退出 exit={process.returncode}，请检查 .runtime/{label}.log")
        try:
            with urlopen(url, timeout=2):
                return
        except (OSError, HTTPError) as error:
            last_error = error
            time.sleep(0.7)
    raise E2EError(f"{label} 在 {seconds}s 内未就绪 ({last_error})，检查 .runtime/{label}.log")


def start(args: list[str], cwd: Path, env: dict, label: str) -> subprocess.Popen:
    logfile = (STATE_DIR / f"{label}.log").open("ab", buffering=0)
    try:
        return subprocess.Popen(args, cwd=cwd, env=env, stdout=logfile, stderr=subprocess.STDOUT,
                                start_new_session=True)
    finally:
        logfile.close()


def wait_outcomes(batch: dict, expected: dict[str, str], seconds: int = 90) -> tuple[dict, dict]:
    until = time.monotonic() + seconds
    batch_job_ids = batch["jobIds"]
    if (not isinstance(batch_job_ids, list)
            or any(not isinstance(job_id, str) for job_id in batch_job_ids)
            or len(set(batch_job_ids)) != len(batch_job_ids)
            or len(batch_job_ids) != len(expected)):
        raise E2EError(f"批次响应 jobIds 与预期任务数不一致: {batch_job_ids}")
    jobs: dict[str, dict] = {}
    while time.monotonic() < until:
        for job_id in batch_job_ids:
            jobs[job_id] = http("GET", f"/api/v1/prediction-jobs/{job_id}/outcome")
        statuses = ["SUCCEEDED" if code == "SUCCEEDED" else "FAILED" for code in expected.values()]
        if sorted(job["status"] for job in jobs.values()) == sorted(statuses):
            break
        time.sleep(0.7)
    else:
        raise E2EError(f"批次 {batch['batchId']} 未收敛: {jobs}")

    listed: dict[str, dict] = {}
    page_number = 0
    total: int | None = None
    while total is None or len(listed) < total:
        page = http("GET", f"/api/v1/prediction-batches/{batch['batchId']}/jobs?page={page_number}&size=100")
        items = page.get("items")
        current_total = page.get("total")
        if (not isinstance(items, list) or not isinstance(current_total, int)
                or current_total < 0 or page.get("page") != page_number):
            raise E2EError(f"批次任务分页响应无效: page={page_number} {page}")
        if total is None:
            total = current_total
            if total != len(batch_job_ids):
                raise E2EError(f"批次任务总数与 jobIds 不一致: total={total} jobIds={batch_job_ids}")
        elif current_total != total:
            raise E2EError(f"批次任务总数在分页期间变化: {total} -> {current_total}")
        for item in items:
            job_id = item.get("predictionJobId")
            if not isinstance(job_id, str) or job_id in listed:
                raise E2EError(f"批次任务分页包含无效或重复 ID: {job_id}")
            listed[job_id] = item
        if len(listed) > total or (not items and len(listed) < total):
            raise E2EError(f"批次任务分页未完整返回: total={total} listed={list(listed)}")
        page_number += 1
    if set(listed) != set(batch_job_ids):
        raise E2EError(f"批次 jobs 列表与 jobIds 不一致: listed={list(listed)} jobIds={batch_job_ids}")

    for item in listed.values():
        outcome = jobs[item["predictionJobId"]]
        code = (outcome.get("failure") or {}).get("failureCode", "SUCCEEDED")
        if expected[item["questionId"]] != code or outcome["status"] != item["status"]:
            raise E2EError(f"任务/API 状态不一致: {item['predictionJobId']} {code}")
        if code == "SUCCEEDED":
            selected = outcome["result"]["selectedOptions"]
            if selected != [{"optionId": OPTIONS[0][0], "position": 1}]:
                raise E2EError("成功结果没有使用冻结题目选项")
    detail = http("GET", f"/api/v1/prediction-batches/{batch['batchId']}")
    successes = sum(code == "SUCCEEDED" for code in expected.values())
    failures = len(expected) - successes
    counts = detail["statusCounts"]
    if counts["succeededCount"] != successes or counts["failedCount"] != failures:
        raise E2EError(f"批次聚合不一致: {detail}")
    return jobs, detail


def wait_worker_ready(worker: subprocess.Popen, environment: dict,
                      seconds: float = 40) -> None:
    if seconds <= 0:
        raise E2EError("worker 就绪等待时间必须为正数")
    until = time.monotonic() + seconds
    # 探针只需定位心跳文件，不向子进程传递应用连接凭据。
    probe_environment = {
        key: value for key, value in environment.items()
        if key in ("PATH", "HOME", "USER", "TMPDIR", "LANG", "LC_ALL", "JAVA_HOME",
                   "F1_PREDICT_CONSUMER_SQLITE_PATH", "F1_PREDICT_CONSUMER_HEALTH_PATH")
    }
    while True:
        remaining = until - time.monotonic()
        if remaining <= 0:
            break
        if worker.poll() is not None:
            raise E2EError(f"worker 提前退出，查看 worker.log: exit={worker.returncode}")
        try:
            check = subprocess.run([str(WORKER_PYTHON), "-m", "f1_predict.worker.main",
                                    "--check-ready"],
                                   cwd=PYTHON_ROOT, env=probe_environment, capture_output=True,
                                   timeout=min(WORKER_READY_PROBE_TIMEOUT, remaining), check=False)
        except subprocess.TimeoutExpired:
            check = None
        if check is not None and check.returncode == 0:
            if worker.poll() is not None:
                raise E2EError(f"worker 提前退出，查看 worker.log: exit={worker.returncode}")
            return
        remaining = until - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(WORKER_READY_RETRY_INTERVAL, remaining))
    raise E2EError("worker 未就绪，查看 worker.log")


def build_java(environment: dict[str, str]) -> Path:
    # 派生专用 POM 与独立输出目录，避免复用 target 中可能过期的类字节码。
    namespace = "http://maven.apache.org/POM/4.0.0"
    ET.register_namespace("", namespace)
    tree = ET.parse(JAVA_ROOT / "pom.xml")
    build = tree.getroot().find(f"{{{namespace}}}build")
    if build is None:
        raise E2EError("Java POM 缺少 build 配置")
    output = STATE_DIR / ("java-build-" + secrets.token_hex(7))
    for key, location in (("directory", output),
                          ("sourceDirectory", JAVA_ROOT / "src/main/java"),
                          ("testSourceDirectory", JAVA_ROOT / "src/test/java")):
        element = build.find(f"{{{namespace}}}{key}")
        if element is None:
            element = ET.SubElement(build, f"{{{namespace}}}{key}")
        element.text = str(location)
    for group, location in (("resources", JAVA_ROOT / "src/main/resources"),
                            ("testResources", JAVA_ROOT / "src/test/resources")):
        element = build.find(f"{{{namespace}}}{group}")
        if element is None:
            element = ET.SubElement(build, f"{{{namespace}}}{group}")
        item = ET.SubElement(element, f"{{{namespace}}}resource" if group == "resources"
                             else f"{{{namespace}}}testResource")
        ET.SubElement(item, f"{{{namespace}}}directory").text = str(location)
    pom = STATE_DIR / ("java-pom-" + secrets.token_hex(7) + ".xml")
    tree.write(pom, encoding="utf-8", xml_declaration=True)
    command([str(JAVA_ROOT / "mvnw"), "-f", str(pom), "-o", "-Dmaven.test.skip=true",
             "package"], cwd=JAVA_ROOT, env=environment, timeout=240)
    jars = list(output.glob("f1aipredict-*.jar"))
    if len(jars) != 1:
        raise E2EError("隔离 Maven 构建未生成唯一 Java JAR")
    return jars[0]


def run() -> None:
    value = state()
    if value["schema"] != "ready":
        raise E2EError("先人工核查 DDL 初始化")
    verify_containers(value)
    mysql_password, rabbit_password = credentials()
    environment = isolated_environment()
    environment.update(E2E_MYSQL_PASSWORD=mysql_password, E2E_RABBIT_PASSWORD=rabbit_password,
                       F1_PREDICT_RABBITMQ_URL=f"amqp://e2e:{rabbit_password}@127.0.0.1:15673/e2e",
                       F1_PREDICT_CONSUMER_SQLITE_PATH=str(STATE_DIR / "inbox.sqlite"),
                       F1_PREDICT_PREDICTION_ENABLED="true",
                       F1_PREDICT_PREDICTION_POLICY_PATH=str(STATE_DIR / "policy.json"),
                       F1_PREDICT_PREDICTION_LAPS_PATH=str(STATE_DIR / "laps.json"),
                       F1_PREDICT_MODEL_URL="http://127.0.0.1:18781/model",
                       F1_PREDICT_MODEL_VERSION=MODEL_VERSION,
                       F1_PREDICT_PROMPT_VERSION=PROMPT_VERSION,
                       F1_PREDICT_FEATURE_VERSION=FEATURE_VERSION)
    (STATE_DIR / "laps.json").write_text(json.dumps(audited_laps()), encoding="utf-8")
    workers: list[subprocess.Popen] = []
    try:
        ensure_free_port(18781)
        ensure_free_port(18780)
        stub = start([sys.executable, str(HERE / "stub.py")], ROOT, environment, "stub")
        workers.append(stub)
        wait_ready("http://127.0.0.1:18781/health", stub, "stub", 8)
        jar = build_java(environment)
        java = start(["java", "-jar", str(jar),
                      f"--spring.config.location=file:{JAVA_CONFIG}", "--spring.profiles.active=e2e",
                      "--spring.cloud.nacos.config.enabled=false",
                      "--spring.cloud.nacos.discovery.enabled=false"], JAVA_ROOT, environment, "java")
        workers.append(java)
        wait_ready(JAVA_API + "/api/v1/admin/sync/records?size=1", java, "java")
        synced = http("POST", "/api/v1/admin/sync/current")
        if synced["status"] not in ("SUCCESS", "SKIPPED_UNCHANGED"):
            raise E2EError(f"Feed 同步未成功: {synced}")
        matches = sql_query(value, "SELECT DISTINCT round_id FROM meeting_session "
                            f"WHERE gameday_id={GAMEDAY} AND session_key={PRACTICE_SESSION}")
        if len(matches) != 1:
            raise E2EError("Feed 同步没有生成唯一的合成比赛分站")
        round_id = int(matches[0][0])
        http("GET", f"/api/v1/rounds/{round_id}")
        questions = http("GET", f"/api/v1/rounds/{round_id}/questions")
        indexed = {item["questionText"]: item for item in questions}
        if set(indexed) != {QUESTION_TEXT, UNSUPPORTED_TEXT}:
            raise E2EError("同步后的题目与合成 Feed 不一致")
        registered, other = indexed[QUESTION_TEXT], indexed[UNSUPPORTED_TEXT]
        if registered["status"] != "OPEN" or other["status"] != "OPEN":
            raise E2EError("Feed 题目未映射为 OPEN；禁止直接更新数据库状态绕过同步")
        policy = {"question_snapshot_id": registered["latestSnapshotId"],
                  "question_id": registered["questionId"], "question_text": QUESTION_TEXT,
                  "meeting_key": MEETING, "session_keys": [PRACTICE_SESSION],
                  "option_drivers": {str(OPTIONS[0][0]): 11, str(OPTIONS[1][0]): 22},
                  "minimum_laps": 3, "model_version": MODEL_VERSION,
                  "prompt_version": PROMPT_VERSION, "feature_version": FEATURE_VERSION}
        (STATE_DIR / "policy.json").write_text(json.dumps(policy), encoding="utf-8")
        worker = start(["uv", "run", "--offline", "python", "-m", "f1_predict.worker.main"],
                       PYTHON_ROOT, environment, "worker")
        workers.append(worker)
        wait_worker_ready(worker, environment)
        question_ids = (registered["questionId"], other["questionId"])
        scenarios = (("success", [question_ids[0]], {question_ids[0]: "SUCCEEDED"}),
                     ("unsupported", [question_ids[1]], {question_ids[1]: "UNSUPPORTED_QUESTION"}),
                     ("mixed", list(question_ids), {question_ids[0]: "SUCCEEDED",
                                                    question_ids[1]: "UNSUPPORTED_QUESTION"}))
        evidence = []
        for name, selected, expected in scenarios:
            batch = http("POST", f"/api/v1/rounds/{round_id}/prediction-batches",
                         {"questionIds": selected, "dataCutoff": CUTOFF,
                          "featureVersion": FEATURE_VERSION, "modelVersion": MODEL_VERSION,
                          "promptVersion": PROMPT_VERSION})
            outcomes, detail = wait_outcomes(batch, expected)
            expected_batch = {"success": "COMPLETED", "unsupported": "FAILED", "mixed": "FAILED"}[name]
            if detail["status"] != expected_batch:
                raise E2EError(f"{name} 批次状态不符合现有聚合语义: {detail['status']}")
            for job_id in batch["jobIds"]:
                rows = sql_query(value, "SELECT message_id,payload_json,status FROM prediction_request_outbox "
                                 f"WHERE prediction_job_id='{job_id}'")
                if len(rows) != 1 or rows[0][2] != "SENT":
                    raise E2EError(f"请求 outbox 未确认: {job_id}")
                receipts = sql_query(value, "SELECT COUNT(*) FROM prediction_outcome_receipt r "
                                     "JOIN prediction_job j ON j.id=r.job_id "
                                     f"WHERE j.prediction_job_id='{job_id}'")
                if receipts != [["1"]]:
                    raise E2EError(f"Java receipt 非唯一: {job_id}")
                with sqlite3.connect(STATE_DIR / "inbox.sqlite") as db:
                    inbox = db.execute("SELECT COUNT(*) FROM request_inbox WHERE prediction_job_id=?",
                                       (job_id,)).fetchone()[0]
                    terminal = db.execute("SELECT COUNT(*) FROM terminal_outbox WHERE prediction_job_id=?",
                                          (job_id,)).fetchone()[0]
                if inbox != 1 or terminal != 1:
                    raise E2EError(f"Python inbox/outbox 非唯一: {job_id}")
                if name == "success":
                    before = outcomes[job_id]
                    previous_queue = rabbit_request_queue(value)
                    if previous_queue.get("messages_ready", 0) or previous_queue.get("messages_unacknowledged", 0):
                        raise E2EError("重放前请求队列尚未清空，无法独立证明本次消费")
                    previous_acks = previous_queue.get("message_stats", {}).get("ack", 0)
                    replay = {"messageId": rows[0][0], "body": rows[0][1]}
                    command(["uv", "run", "--offline", "python", str(HERE / "replay.py")],
                            cwd=PYTHON_ROOT, env=environment, input_text=json.dumps(replay))
                    deadline = time.monotonic() + 30
                    while time.monotonic() < deadline:
                        if worker.poll() is not None:
                            raise E2EError("重放时 worker 已退出，不能证明去重")
                        queue = rabbit_request_queue(value)
                        if (queue.get("message_stats", {}).get("ack", 0) > previous_acks
                                and queue.get("messages_ready", 0) == 0
                                and queue.get("messages_unacknowledged", 0) == 0):
                            break
                        time.sleep(0.5)
                    else:
                        raise E2EError("重放未观察到请求消费 ACK 和队列排空")
                    after = http("GET", f"/api/v1/prediction-jobs/{job_id}/outcome")
                    if after != before or sql_query(value, "SELECT COUNT(*) FROM prediction_outcome_receipt r "
                                                    "JOIN prediction_job j ON j.id=r.job_id "
                                                    f"WHERE j.prediction_job_id='{job_id}'") != [["1"]]:
                        raise E2EError("重投原请求后出现第二个终态")
                    with sqlite3.connect(STATE_DIR / "inbox.sqlite") as db:
                        duplicates = db.execute("SELECT COUNT(*) FROM request_inbox WHERE prediction_job_id=?",
                                                (job_id,)).fetchone()[0]
                    if duplicates != 1:
                        raise E2EError("重投原请求后 Python inbox 出现重复任务")
            if name == "success":
                # 消费者单活重启后使用同一 SQLite，后续失败批次检验可恢复消费。
                os.killpg(worker.pid, signal.SIGTERM)
                worker.wait(timeout=12)
                worker = start(["uv", "run", "--offline", "python", "-m",
                                "f1_predict.worker.main"], PYTHON_ROOT, environment, "worker")
                workers.append(worker)
                wait_worker_ready(worker, environment)
                print("已重启单活 worker 并复用原 SQLite")
            evidence.append({"scenario": name, "batchId": batch["batchId"],
                             "jobIds": batch["jobIds"], "batchStatus": detail["status"]})
            print(f"{name}: batch={batch['batchId']} status={detail['status']} jobs={batch['jobIds']}")
            if name == "unsupported":
                # 仅重启本次专属 Broker；后续混合批次验证重连后的双端消息恢复。
                verify_containers(value)
                command(compose_args(value) + ["restart", "rabbitmq"], timeout=60)
                command(compose_args(value) + ["up", "-d", "--wait", "--no-recreate", "rabbitmq"],
                        timeout=120)
                verify_containers(value)
                command(compose_args(value) + ["exec", "-T", "rabbitmq",
                                               "rabbitmq-diagnostics", "-q", "check_running"], timeout=60)
                print("已重启隔离 Broker；将用混合批次验证重连和最终持久化")
        (STATE_DIR / "evidence.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    finally:
        for process in reversed(workers):
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
        for process in reversed(workers):
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=3)
        print("应用进程已停止；MySQL/Rabbit/SQLite 数据及脱敏证据默认保留")


def stop() -> None:
    value = state()
    verify_containers(value)
    command(compose_args(value) + ["stop"], timeout=90)
    print("只停止已核验的独立 compose 项目；卷及数据保留")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "apply-schema", "run", "status", "stop"))
    parser.add_argument("--project", help="手工初始化时必须输入 status 显示的完整隔离项目名")
    parser.add_argument("--confirm-isolated-schema", action="store_true",
                        help="明确确认只对空的隔离库执行 001–008；不可重复执行")
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            prepare()
        elif args.action == "apply-schema":
            apply_schema(args.project, args.confirm_isolated_schema)
        elif args.action == "run":
            run()
        elif args.action == "stop":
            stop()
        else:
            value = state()
            verify_containers(value)
            print(f"project={value['project']} schema={value['schema']}")
    except (E2EError, OSError, subprocess.TimeoutExpired, URLError) as error:
        print(f"E2E 未通过：{error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
