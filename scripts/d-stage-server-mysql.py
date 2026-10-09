#!/usr/bin/env python3
"""准备一个随机、独立且受限的远程 MySQL 集成测试库。"""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import queue
import re
import secrets
import shlex
import shutil
import socket
import stat
import subprocess
import sys
import threading
import time
from contextlib import contextmanager
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL_DIR = ROOT / "sql"
SQL_FILES = (
    "001_create_database.sql",
    "002_season_round.sql",
    "003_question.sql",
    "004_prediction.sql",
    "005_answer_scoring.sql",
    "006_sync.sql",
    "007_prediction_request_outbox.sql",
    "008_prediction_outcome.sql",
    "009_prediction_lock.sql",
    "010_official_answer_history.sql",
    "011_scoring_status.sql",
)
BUSINESS_SCHEMA = "f1_ai_predict"
IT_SOURCE_DIR = ROOT / "f1aipredict" / "src" / "it" / "java"
IT_SELECTION_CHARS = re.compile(r"^[A-Za-z0-9_#,]+$")
IT_CLASS_NAME = re.compile(r"^[A-Z][A-Za-z0-9]*IT$")
IT_METHOD_NAME = re.compile(r"^[a-z][A-Za-z0-9_]*$")
MAX_IT_SELECTIONS = 16
IDENTIFIER = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,63}$")
ENV_KEY = re.compile(r"^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$")


class SafeSetupError(Exception):
    """可安全显示给操作者的初始化错误。"""


def parse_env_file(path: Path) -> tuple[dict[str, str], list[str]]:
    """只解析 .env 字面量，不执行 shell；同时返回用于日志脱敏的值。"""
    values: dict[str, str] = {}
    sensitive_values: list[str] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise SafeSetupError(f"无法读取 .env（{type(exc).__name__}）") from None
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = ENV_KEY.match(stripped)
        if not match:
            continue
        key, value = match.groups()
        value = value.strip()
        if value.startswith(("\"", "'")):
            try:
                tokens = shlex.split(value, comments=True, posix=True)
            except ValueError:
                raise SafeSetupError(".env 中存在未闭合或无效的引号") from None
            if len(tokens) != 1:
                raise SafeSetupError(".env 中存在无效的带引号字面量")
            value = tokens[0]
        elif " #" in value:
            value = value.split(" #", 1)[0].rstrip()
        values[key] = value
    required = (
        "MVP_SOURCE_MYSQL_HOST",
        "MVP_SOURCE_MYSQL_PORT",
        "MVP_SOURCE_MYSQL_USER",
        "MVP_SOURCE_MYSQL_PASSWORD",
        "MVP_SOURCE_MYSQL_DATABASE",
        "SERVER_IP",
        "SERVER_USER",
        "SERVER_PASSWORD",
    )
    missing = [name for name in required if not values.get(name)]
    if missing:
        raise SafeSetupError(".env 缺少必要键：" + ", ".join(missing))
    if not IDENTIFIER.fullmatch(values["MVP_SOURCE_MYSQL_DATABASE"]):
        raise SafeSetupError(".env 中 MySQL 数据库名格式无效")
    try:
        port = int(values.get("MVP_SOURCE_MYSQL_PORT", "3306"))
    except ValueError:
        raise SafeSetupError(".env 中 MySQL 端口格式无效") from None
    if not 1 <= port <= 65535:
        raise SafeSetupError(".env 中 MySQL 端口超出范围")
    values["MVP_SOURCE_MYSQL_PORT"] = str(port)
    values["MVP_SOURCE_MYSQL_HOST"] = validate_connection_host(values["MVP_SOURCE_MYSQL_HOST"])
    server_ip = values["SERVER_IP"].strip().strip("[]")
    try:
        values["SERVER_IP"] = str(ipaddress.ip_address(server_ip))
    except ValueError:
        raise SafeSetupError(".env 中 SERVER_IP 必须是有效 IP 地址") from None
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", values["SERVER_USER"]):
        raise SafeSetupError(".env 中 SERVER_USER 格式无效")
    sensitive_values.extend(value for value in values.values() if value)
    return values, sensitive_values


def state_dir() -> Path:
    base = os.environ.get("CLAUDE_JOB_DIR")
    if base:
        root = Path(base) / "tmp"
    else:
        root = Path(os.environ.get("TMPDIR", "/tmp"))
    directory = root / "d-stage-server-mysql"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = directory.lstat()
    if stat.S_ISLNK(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise SafeSetupError("私有状态目录所有权或权限不安全")
    return directory


def local_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _listener_pids(port: int) -> set[int] | None:
    lsof = shutil.which("lsof")
    if not lsof:
        return None
    try:
        result = subprocess.run(
            [lsof, "-nP", "-t", "-a", f"-iTCP@127.0.0.1:{port}", "-sTCP:LISTEN"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=2,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    output = result.stdout.strip()
    if result.returncode == 1 and not output:
        return set()
    if result.returncode != 0 or not output:
        return None
    lines = output.splitlines()
    if any(not line.isdecimal() for line in lines):
        return None
    return {int(line) for line in lines}


def _assert_tunnel_active(process, port: int) -> None:
    if process.poll() is not None or _listener_pids(port) != {process.pid}:
        raise SafeSetupError("SSH 隧道已退出或本地监听进程归属不匹配")


def _probe_mysql_tunnel(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1) as probe:
            probe.settimeout(1)
            header = b""
            while len(header) < 4:
                chunk = probe.recv(4 - len(header))
                if not chunk:
                    return False
                header += chunk
            payload_size = int.from_bytes(header[:3], "little")
            return header[3] == 0 and 1 <= payload_size <= 65535 and probe.recv(1) == b"\x0a"
    except OSError:
        return False


def _capture_stream(stream, chunks: list[bytes]) -> None:
    size = 0
    while True:
        chunk = os.read(stream.fileno(), 8192)
        if not chunk:
            return
        if size < 65536:
            retained = chunk[:65536 - size]
            chunks.append(retained)
            size += len(retained)


def redact_text(text: str, values: list[str] | tuple[str, ...]) -> str:
    for value in sorted({value for value in values if value}, key=len, reverse=True):
        text = text.replace(value, "[REDACTED]")
    return re.sub(r"jdbc:mysql://[^\s\"'<>]+", "[REDACTED_JDBC_URL]", text)


@contextmanager
def ssh_mysql_tunnel(
    config: dict[str, str], directory: Path, redaction_values: list[str] | tuple[str, ...] = ()
):
    """用已登记的 SSH host key 将远端 MySQL 仅转发到本机回环。"""
    redactions = tuple(redaction_values) + tuple(config.values())
    ssh = shutil.which("ssh")
    ssh_keygen = shutil.which("ssh-keygen")
    ssh_directory = Path.home() / ".ssh"
    known_hosts = ssh_directory / "known_hosts"
    if not ssh or not ssh_keygen:
        raise SafeSetupError("本机缺少 OpenSSH 客户端，拒绝直接连接 MySQL")
    try:
        directory_info = ssh_directory.lstat()
        info = known_hosts.lstat()
    except OSError:
        raise SafeSetupError("未找到可信 SSH known_hosts 文件") from None
    if (
        stat.S_ISLNK(directory_info.st_mode)
        or not stat.S_ISDIR(directory_info.st_mode)
        or directory_info.st_uid != os.getuid()
        or directory_info.st_mode & 0o022
        or stat.S_ISLNK(info.st_mode)
        or not stat.S_ISREG(info.st_mode)
        or info.st_uid != os.getuid()
        or info.st_mode & 0o022
    ):
        raise SafeSetupError("SSH known_hosts 路径所有权、权限或类型不安全")
    key_check = subprocess.run(
        [ssh_keygen, "-F", config["SERVER_IP"], "-f", str(known_hosts)],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if key_check.returncode != 0:
        raise SafeSetupError("SERVER_IP 在可信 known_hosts 中没有已登记的 host key")

    suffix = secrets.token_hex(8)
    password_file = directory / f"ssh-auth-{suffix}.secret"
    askpass_file = directory / f"ssh-askpass-{suffix}.py"
    process = None
    stderr_thread = None
    stderr_chunks: list[bytes] = []
    created_files: list[Path] = []
    try:
        for path, content, mode in (
            (password_file, config["SERVER_PASSWORD"], 0o600),
            (
                askpass_file,
                (
                    f"#!{sys.executable}\n"
                    "from pathlib import Path\n"
                    f"print(Path({json.dumps(str(password_file))}).read_text(encoding='utf-8'))\n"
                ),
                0o700,
            ),
        ):
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
            created_files.append(path)
            os.fchmod(fd, mode)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                stream.write(content)

        port = local_port()
        destination = config["SERVER_IP"]
        if ":" in destination:
            destination = f"[{destination}]"
        command = [
            ssh,
            "-F", "/dev/null",
            "-N",
            "-o", "StrictHostKeyChecking=yes",
            "-o", f"UserKnownHostsFile={known_hosts}",
            "-o", "GlobalKnownHostsFile=/dev/null",
            "-o", "CanonicalizeHostname=no",
            "-o", "UpdateHostKeys=no",
            "-o", "ControlMaster=no",
            "-o", "ControlPath=none",
            "-o", "IdentitiesOnly=yes",
            "-o", "ExitOnForwardFailure=yes",
            "-o", "ConnectTimeout=10",
            "-o", "PreferredAuthentications=password",
            "-o", "PubkeyAuthentication=no",
            "-o", "PasswordAuthentication=yes",
            "-o", "KbdInteractiveAuthentication=no",
            "-o", "NumberOfPasswordPrompts=1",
            "-o", "RequestTTY=no",
            "-o", "ServerAliveInterval=15",
            "-o", "ServerAliveCountMax=3",
            "-o", "LogLevel=ERROR",
            "-L", f"127.0.0.1:{port}:127.0.0.1:{config['MVP_SOURCE_MYSQL_PORT']}",
            f"{config['SERVER_USER']}@{destination}",
        ]
        child_env = {
            "PATH": os.environ.get("PATH", os.defpath),
            "HOME": str(Path.home()),
            "SSH_ASKPASS": str(askpass_file),
            "SSH_ASKPASS_REQUIRE": "force",
            "DISPLAY": "f1-it-askpass",
            "LC_ALL": "C",
        }
        try:
            process = subprocess.Popen(
                command,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                env=child_env,
                start_new_session=True,
            )
        except OSError as exc:
            raise SafeSetupError(f"无法启动受信 SSH 隧道（{type(exc).__name__}）") from None
        stderr_thread = threading.Thread(
            target=_capture_stream, args=(process.stderr, stderr_chunks), daemon=True
        )
        stderr_thread.start()
        deadline = time.monotonic() + 10
        ready = False
        while time.monotonic() < deadline:
            if process.poll() is not None:
                break
            listener_pids = _listener_pids(port)
            if listener_pids is None:
                raise SafeSetupError("无法核验 SSH 隧道本地监听进程")
            if listener_pids and listener_pids != {process.pid}:
                raise SafeSetupError("本地隧道端口由其他进程监听，拒绝连接")
            if listener_pids == {process.pid} and _probe_mysql_tunnel(port):
                _assert_tunnel_active(process, port)
                ready = True
                break
            time.sleep(0.1)
        if not ready:
            if stderr_thread is not None:
                stderr_thread.join(timeout=1)
            detail = redact_text(b"".join(stderr_chunks).decode("utf-8", errors="replace"), redactions).strip()
            last_detail = detail.splitlines()[-1] if detail else "SSH stderr 已捕获并脱敏"
            safe_detail = re.sub(r"[\x00-\x1f\x7f]", " ", last_detail)[:500]
            raise SafeSetupError(f"SSH 隧道未就绪；host-key、口令认证或端口转发失败：{safe_detail}")
        _assert_tunnel_active(process, port)
        yield port, process
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        if stderr_thread is not None:
            stderr_thread.join(timeout=1)
        if process is not None and process.stderr is not None:
            process.stderr.close()
        for path in created_files:
            path.unlink(missing_ok=True)


def tunnel_config(config: dict[str, str], port: int) -> dict[str, str]:
    result = dict(config)
    result["MYSQL_TUNNEL_PORT"] = str(port)
    return result


def _strip_comments(text: str) -> str:
    """删除 SQL 行注释和块注释，同时保留引号中的字符。"""
    result: list[str] = []
    i = 0
    quote: str | None = None
    while i < len(text):
        char = text[i]
        if quote:
            result.append(char)
            if char == "\\" and quote != "`" and i + 1 < len(text):
                i += 1
                result.append(text[i])
            elif char == quote:
                if i + 1 < len(text) and text[i + 1] == quote:
                    i += 1
                    result.append(text[i])
                else:
                    quote = None
            i += 1
            continue
        if char in "'\"`":
            quote = char
            result.append(char)
            i += 1
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end < 0:
                raise SafeSetupError("SQL 中存在未闭合块注释")
            result.append(" ")
            i = end + 2
            continue
        if char == "#" or (text.startswith("--", i) and (i + 2 == len(text) or text[i + 2].isspace())):
            end = text.find("\n", i)
            i = len(text) if end < 0 else end + 1
            result.append(" ")
            continue
        result.append(char)
        i += 1
    if quote:
        raise SafeSetupError("SQL 中存在未闭合引号")
    return "".join(result)


def split_statements(text: str) -> list[str]:
    clean = _strip_comments(text)
    statements: list[str] = []
    start = 0
    quote: str | None = None
    i = 0
    while i < len(clean):
        char = clean[i]
        if quote:
            if char == "\\" and quote != "`":
                i += 2
                continue
            if char == quote:
                if i + 1 < len(clean) and clean[i + 1] == quote:
                    i += 2
                    continue
                quote = None
        elif char in "'\"`":
            quote = char
        elif char == ";":
            statement = clean[start:i].strip()
            if statement:
                statements.append(statement)
            start = i + 1
        i += 1
    tail = clean[start:].strip()
    if tail:
        statements.append(tail)
    return statements


def get_safe_schema_sql(schema: str) -> list[tuple[str, str]]:
    """只允许固定 001..011 的建表 DDL 定向到本次随机库。"""
    files = sorted(path.name for path in SQL_DIR.glob("[0-9][0-9][0-9]_*.sql"))
    if tuple(files) != tuple(sorted(SQL_FILES)):
        raise SafeSetupError("sql/ 编号脚本清单与固定 001..011 不一致，拒绝初始化")
    if not re.fullmatch(r"f1_it_[0-9a-f]{16}", schema):
        raise SafeSetupError("随机测试库名格式无效")
    prepared: list[tuple[str, str]] = []
    create_count = 0
    for filename in SQL_FILES:
        try:
            source = (SQL_DIR / filename).read_text(encoding="utf-8")
        except OSError as exc:
            raise SafeSetupError(f"无法读取 {filename}（{type(exc).__name__}）") from None
        statements = split_statements(source)
        if filename == SQL_FILES[0]:
            replaced_statements: list[str] = []
            create_database_count = 0
            for statement in statements:
                rewritten, count = re.subn(
                    r"CREATE\s+DATABASE\s+IF\s+NOT\s+EXISTS\s+`f1_ai_predict`",
                    f"CREATE DATABASE `{schema}`",
                    statement,
                    flags=re.IGNORECASE,
                )
                replaced_statements.append(rewritten)
                create_database_count += count
            if create_database_count != 1:
                raise SafeSetupError("001 的目标数据库语句与已审阅模板不符，拒绝初始化")
            statements = replaced_statements

        rewritten_statements: list[str] = []
        use_count = 0
        for statement in statements:
            normalized = " ".join(statement.split())
            if re.match(r"^USE\b", normalized, re.IGNORECASE):
                if not re.fullmatch(r"USE `f1_ai_predict`", normalized, re.IGNORECASE):
                    raise SafeSetupError(f"{filename} 包含非目标 USE，拒绝初始化")
                use_count += 1
                rewritten_statements.append(f"USE `{schema}`")
            else:
                rewritten_statements.append(normalized)
        allowed_use_counts = (0, 1) if filename == SQL_FILES[6] else (1,)
        if use_count not in allowed_use_counts:
            raise SafeSetupError(f"{filename} 的目标 USE 语句与已审阅模板不符，拒绝初始化")
        if use_count == 0:
            rewritten_statements.insert(0, f"USE `{schema}`")
        for statement in rewritten_statements:
            normalized = " ".join(statement.split())
            if re.match(r"^CREATE\s+DATABASE\b", normalized, re.IGNORECASE):
                if filename != SQL_FILES[0] or not re.match(
                    rf"^CREATE DATABASE `{re.escape(schema)}`(?:\s|$)", normalized, re.IGNORECASE
                ):
                    raise SafeSetupError(f"{filename} 包含非目标 CREATE DATABASE，拒绝初始化")
                create_count += 1
            elif re.match(r"^USE\b", normalized, re.IGNORECASE):
                if not re.fullmatch(rf"USE `{re.escape(schema)}`", normalized, re.IGNORECASE):
                    raise SafeSetupError(f"{filename} 包含非目标 USE，拒绝初始化")
            elif not re.match(r"^(?:CREATE\s+TABLE|ALTER\s+TABLE)\b", normalized, re.IGNORECASE):
                raise SafeSetupError(f"{filename} 含非建表 DDL/DML，拒绝初始化")
            without_literals = re.sub(r"'(?:''|\\\\.|[^'])*'|\"(?:\"\"|\\\\.|[^\"])*\"", "''", normalized)
            qualified_identifier = re.compile(
                r"(?:`[A-Za-z][A-Za-z0-9_]*`|[A-Za-z][A-Za-z0-9_]*)\s*\.\s*"
                r"(?:`[A-Za-z][A-Za-z0-9_]*`|[A-Za-z][A-Za-z0-9_]*)"
            )
            if qualified_identifier.search(without_literals):
                raise SafeSetupError(f"{filename} 含 schema 限定引用，拒绝初始化")
            prepared.append((filename, normalized))
    if create_count != 1:
        raise SafeSetupError("固定 DDL 没有且仅有一个数据库创建语句")
    return prepared


def mysql_connection(
    config: dict[str, str], *, database: str | None = None, tunnel_process=None
):
    try:
        import pymysql
    except ImportError:
        raise SafeSetupError("当前 Python 环境未安装 PyMySQL") from None
    try:
        # SSH 隧道是唯一传输路径，缺少动态回环端口时绝不允许直接连接。
        tunnel_port = int(config["MYSQL_TUNNEL_PORT"])
        if not 1 <= tunnel_port <= 65535 or tunnel_process is None:
            raise SafeSetupError("SSH 隧道端口或进程身份缺失")
        _assert_tunnel_active(tunnel_process, tunnel_port)
        connection = pymysql.connect(
            host="127.0.0.1",
            port=tunnel_port,
            user=config["MVP_SOURCE_MYSQL_USER"],
            password=config["MVP_SOURCE_MYSQL_PASSWORD"],
            database=database,
            charset="utf8mb4",
            connect_timeout=10,
            read_timeout=30,
            write_timeout=30,
            autocommit=True,
            ssl_disabled=True,
        )
        try:
            _assert_tunnel_active(tunnel_process, tunnel_port)
        except BaseException:
            connection.close()
            raise
        return connection
    except (pymysql.MySQLError, OSError) as exc:
        code = exc.args[0] if getattr(exc, "args", None) and isinstance(exc.args[0], int) else "n/a"
        raise SafeSetupError(f"MySQL 连接失败（{type(exc).__name__}, code={code}）") from None


def validate_client_host(value: str) -> str:
    host = value.strip()
    if host.startswith("[") and host.endswith("]"):
        host = host[1:-1]
    if host == "localhost":
        return host
    try:
        ipaddress.ip_address(host)
    except ValueError:
        raise SafeSetupError("服务器报告的客户端地址不是 IP 或 localhost，拒绝使用通配 host") from None
    if any(char in host for char in "'%_\\"):
        raise SafeSetupError("服务器报告的客户端地址含不安全字符，拒绝授权")
    return host


def validate_connection_host(value: str) -> str:
    host = value.strip()
    if not host or any(char in host for char in "/@?#\\\\%'\";` "):
        raise SafeSetupError(".env 中 MySQL 主机名格式无效")
    if ":" in host:
        try:
            ipaddress.IPv6Address(host.strip("[]"))
        except ValueError:
            raise SafeSetupError(".env 中 IPv6 主机地址无效") from None
        return f"[{host.strip('[]')}]"
    try:
        ipaddress.IPv4Address(host)
        return host
    except ValueError:
        pass
    if len(host) > 253 or not re.fullmatch(r"[A-Za-z0-9.-]+", host):
        raise SafeSetupError(".env 中 MySQL 主机名格式无效")
    if any(not label or len(label) > 63 or label.startswith("-") or label.endswith("-") for label in host.rstrip(".").split(".")):
        raise SafeSetupError(".env 中 MySQL 主机名格式无效")
    return host


def quote_sql_string(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def write_env_file(path: Path, schema: str, username: str, password: str, run_id: str) -> None:
    values = {
        "F1_IT_JDBC_URL": f"jdbc:mysql://127.0.0.1:0/{schema}?connectionTimeZone=UTC&characterEncoding=UTF-8&sslMode=DISABLED&allowPublicKeyRetrieval=true",
        "F1_IT_DB_USERNAME": username,
        "F1_IT_DB_PASSWORD": password,
        "F1_IT_RUN_ID": run_id,
        "F1_IT_REMOTE_MODE": "true",
        "F1_IT_DATABASE": schema,
        "F1_IT_MARKER_SCHEMA": schema,
    }
    content = "# 只能由脚本 --run-it 建立 SSH 隧道后使用；端口 0 是故意失效的占位符。\n"
    content += "".join(f"export {key}={shlex.quote(value)}\n" for key, value in values.items())
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(content)


def _read_private_file(path: Path, max_bytes: int) -> str:
    try:
        info = path.lstat()
        if (
            stat.S_ISLNK(info.st_mode)
            or not stat.S_ISREG(info.st_mode)
            or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o600
            or info.st_size > max_bytes
        ):
            raise SafeSetupError("集成测试状态文件的所有权、权限或大小不安全")
        return path.read_text(encoding="utf-8")
    except SafeSetupError:
        raise
    except (OSError, UnicodeError) as exc:
        raise SafeSetupError(f"无法读取集成测试状态文件（{type(exc).__name__}）") from None


def _parse_key_values(text: str, expected: set[str]) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("export "):
            try:
                tokens = shlex.split(stripped, posix=True)
            except ValueError:
                raise SafeSetupError("集成测试环境文件语法无效") from None
            if len(tokens) != 2 or tokens[0] != "export":
                raise SafeSetupError("集成测试环境文件格式无效")
            assignment = tokens[1]
        else:
            assignment = stripped
        if "=" not in assignment:
            raise SafeSetupError("集成测试状态清单格式无效")
        key, value = assignment.split("=", 1)
        if key not in expected or key in values:
            raise SafeSetupError("集成测试状态清单包含意外或重复字段")
        values[key] = value
    if set(values) != expected:
        raise SafeSetupError("集成测试状态清单字段不完整")
    return values


def load_run_configuration(
    directory: Path, run_id: str, config: dict[str, str]
) -> tuple[dict[str, str], dict[str, str], list[str]]:
    if not re.fullmatch(r"[0-9a-f]{32}", run_id):
        raise SafeSetupError("run ID 格式无效")
    state_path = directory / f"{run_id}.state"
    env_path = directory / f"{run_id}.env"
    state = _parse_key_values(
        _read_private_file(state_path, 8192),
        {
            "run_id", "schema", "account", "account_host", "created_objects", "status",
            "server_ip", "server_user", "mysql_port", "source_database",
        },
    )
    if (
        state["run_id"] != run_id
        or not re.fullmatch(r"f1_it_[0-9a-f]{16}", state["schema"])
        or not re.fullmatch(r"f1_it_[0-9a-f]{16}", state["account"])
        or validate_client_host(state["account_host"]) != state["account_host"]
        or state["status"] != "initialized"
        or f"database:{state['schema']}" not in state["created_objects"].split(",")
        or f"user:{state['account']}@{state['account_host']}" not in state["created_objects"].split(",")
        or state["server_ip"] != config["SERVER_IP"]
        or state["server_user"] != config["SERVER_USER"]
        or state["mysql_port"] != config["MVP_SOURCE_MYSQL_PORT"]
        or state["source_database"] != config["MVP_SOURCE_MYSQL_DATABASE"]
    ):
        raise SafeSetupError("集成测试状态与随机 schema/run marker 或 SSH 目标不匹配，拒绝运行")

    keys = {
        "F1_IT_JDBC_URL",
        "F1_IT_DB_USERNAME",
        "F1_IT_DB_PASSWORD",
        "F1_IT_RUN_ID",
        "F1_IT_REMOTE_MODE",
        "F1_IT_DATABASE",
        "F1_IT_MARKER_SCHEMA",
    }
    values = _parse_key_values(_read_private_file(env_path, 16384), keys)
    schema = state["schema"]
    expected_url = (
        f"jdbc:mysql://127.0.0.1:0/{schema}?connectionTimeZone=UTC&characterEncoding=UTF-8"
        "&sslMode=DISABLED&allowPublicKeyRetrieval=true"
    )
    if (
        values["F1_IT_RUN_ID"] != run_id
        or values["F1_IT_REMOTE_MODE"] != "true"
        or values["F1_IT_DATABASE"] != schema
        or values["F1_IT_MARKER_SCHEMA"] != schema
        or values["F1_IT_DB_USERNAME"] != state["account"]
        or values["F1_IT_JDBC_URL"] != expected_url
        or not values["F1_IT_DB_PASSWORD"]
    ):
        raise SafeSetupError("隔离环境文件与状态清单不匹配，拒绝运行")
    redactions = list(values.values()) + list(state.values()) + list(config.values())
    run_config = dict(config)
    run_config["MVP_SOURCE_MYSQL_USER"] = values["F1_IT_DB_USERNAME"]
    run_config["MVP_SOURCE_MYSQL_PASSWORD"] = values["F1_IT_DB_PASSWORD"]
    run_config["MYSQL_SCHEMA"] = schema
    run_config["MYSQL_ACCOUNT_HOST"] = state["account_host"]
    return run_config, values, redactions


def validate_it_connection(connection, schema: str, run_id: str, account: str, account_host: str) -> None:
    with connection.cursor() as cursor:
        cursor.execute("SELECT DATABASE(), USER(), CURRENT_USER()")
        selected_database, connected_user, current_user = cursor.fetchone()
        if selected_database != schema:
            raise SafeSetupError("随机 IT 账号连接后的 schema 不匹配")
        if "@" not in connected_user or "@" not in current_user:
            raise SafeSetupError("随机 IT 账号连接身份格式无效")
        connected_host = validate_client_host(connected_user.rsplit("@", 1)[1])
        expected_account = f"{account}@{account_host}"
        if connected_host != account_host or current_user != expected_account:
            raise SafeSetupError("随机 IT 账号没有使用本次记录的精确 host 授权")
        cursor.execute(f"SELECT run_id FROM `{schema}`.it_run_marker")
        markers = cursor.fetchall()
        if markers != ((run_id,),):
            raise SafeSetupError("随机 IT 账号无法验证本次 run marker")


def discover_it_tests(source_dir: Path | None = None) -> dict[str, set[str]]:
    """扫描当前源码中的具体 IT 类及其测试方法名。

    抽象基类、未声明同名具体类的文件，以及在多个目录中重名的类都不可被选择，
    避免 `-Dit.test` 按简单类名匹配到意料之外的测试。
    """
    directory = IT_SOURCE_DIR if source_dir is None else source_dir
    if not directory.is_dir():
        raise SafeSetupError("找不到 IT 源码目录，无法校验 --it-tests")
    found: dict[str, set[str]] = {}
    duplicated: set[str] = set()
    for path in sorted(directory.rglob("*IT.java")):
        name = path.stem
        if not IT_CLASS_NAME.fullmatch(name):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            raise SafeSetupError(f"无法读取 IT 源码（{type(exc).__name__}）") from None
        if not re.search(rf"^\s*(?:public\s+|final\s+)*class\s+{name}\b", text, re.MULTILINE):
            continue
        if name in found:
            duplicated.add(name)
            continue
        methods: set[str] = set()
        for marker in re.finditer(r"@(?:Test|RepeatedTest|ParameterizedTest|TestFactory)\b", text):
            declaration = re.search(r"\bvoid\s+([A-Za-z_]\w*)\s*\(", text[marker.end():marker.end() + 600])
            if declaration:
                methods.add(declaration.group(1))
        found[name] = methods
    for name in duplicated:
        found.pop(name, None)
    return found


def parse_it_tests(value: str, source_dir: Path | None = None) -> str:
    """校验受控的 IT 选择并返回传给 Failsafe `-Dit.test` 的文本。

    只接受当前源码中真实存在的具体 IT 类名，可选 `Class#method`（方法须存在于该类）。
    不接受通配符、正则、空格、任意 Maven 选项或同一个类的整类与单方法混用。
    """
    if not isinstance(value, str) or not IT_SELECTION_CHARS.fullmatch(value):
        raise SafeSetupError("--it-tests 只能包含字母、数字、下划线、# 与逗号分隔的 IT 类名")
    entries = value.split(",")
    if len(entries) > MAX_IT_SELECTIONS:
        raise SafeSetupError(f"--it-tests 最多选择 {MAX_IT_SELECTIONS} 项")
    discovered = discover_it_tests(source_dir)
    seen: set[str] = set()
    whole_classes: set[str] = set()
    method_classes: set[str] = set()
    for position, entry in enumerate(entries, start=1):
        class_name, separator, method_name = entry.partition("#")
        if not IT_CLASS_NAME.fullmatch(class_name) or class_name not in discovered:
            raise SafeSetupError(f"--it-tests 第 {position} 项不是当前源码中的具体 IT 类名")
        if separator:
            if not IT_METHOD_NAME.fullmatch(method_name) or method_name not in discovered[class_name]:
                raise SafeSetupError(f"--it-tests 第 {position} 项的测试方法不存在于该 IT 类")
            method_classes.add(class_name)
        else:
            whole_classes.add(class_name)
        if entry in seen:
            raise SafeSetupError(f"--it-tests 第 {position} 项重复")
        seen.add(entry)
    if whole_classes & method_classes:
        raise SafeSetupError("--it-tests 不能同时选择某个 IT 类的整类与其单个方法")
    return ",".join(entries)


def build_maven_it_command(it_tests: str | None = None) -> list[str]:
    """构造固定的 Failsafe 命令；选择器只增加 `-Dit.test`，永远保留 failsafe:verify 且不跳过测试。"""
    command = ["./mvnw", "-Pit"]
    if it_tests is not None:
        command += [f"-Dit.test={parse_it_tests(it_tests)}", "-Dit.failIfNoSpecifiedTests=true"]
    command += ["test-compile", "failsafe:integration-test", "failsafe:verify"]
    return command


def run_maven_it(
    directory: Path,
    env_values: dict[str, str],
    redaction_values: list[str],
    port: int,
    tunnel_process,
    it_tests: str | None = None,
) -> tuple[int, Path]:
    command = build_maven_it_command(it_tests)
    schema = env_values["F1_IT_DATABASE"]
    url = (
        f"jdbc:mysql://127.0.0.1:{port}/{schema}?connectionTimeZone=UTC&characterEncoding=UTF-8"
        "&sslMode=DISABLED&allowPublicKeyRetrieval=true"
    )
    inherited_names = {"PATH", "HOME", "JAVA_HOME", "TMPDIR", "TEMP", "TMP", "LANG", "LC_ALL", "MAVEN_USER_HOME"}
    child_env = {key: value for key, value in os.environ.items() if key in inherited_names}
    child_env.update({
        "F1_IT_JDBC_URL": url,
        "F1_IT_DB_USERNAME": env_values["F1_IT_DB_USERNAME"],
        "F1_IT_DB_PASSWORD": env_values["F1_IT_DB_PASSWORD"],
        "F1_IT_RUN_ID": env_values["F1_IT_RUN_ID"],
        "F1_IT_REMOTE_MODE": "true",
        "F1_IT_DATABASE": schema,
        "F1_IT_MARKER_SCHEMA": schema,
    })
    log_dir = directory / "logs"
    log_dir.mkdir(mode=0o700, exist_ok=True)
    if stat.S_IMODE(log_dir.stat().st_mode) != 0o700 or log_dir.stat().st_uid != os.getuid():
        raise SafeSetupError("Maven 日志目录权限不安全")
    log_path = log_dir / f"{env_values['F1_IT_RUN_ID']}-{secrets.token_hex(4)}.log"
    fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    log_stream = os.fdopen(fd, "w", encoding="utf-8")
    try:
        process = subprocess.Popen(
            command,
            cwd=ROOT / "f1aipredict",
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            env=child_env,
        )
    except OSError as exc:
        log_stream.close()
        log_path.unlink(missing_ok=True)
        raise SafeSetupError(f"无法启动 Maven Failsafe（{type(exc).__name__}）") from None

    output_queue: queue.Queue[tuple[str, str | None]] = queue.Queue()

    def forward(stream, label: str) -> None:
        try:
            for line in iter(stream.readline, ""):
                output_queue.put((label, line))
        finally:
            output_queue.put((label, None))

    readers = [
        threading.Thread(target=forward, args=(process.stdout, "stdout"), daemon=True),
        threading.Thread(target=forward, args=(process.stderr, "stderr"), daemon=True),
    ]
    for reader in readers:
        reader.start()
    finished_streams = 0
    next_tunnel_check = 0.0
    secrets_to_redact = redaction_values + [url, env_values["F1_IT_DB_PASSWORD"]]
    try:
        while finished_streams < len(readers):
            if time.monotonic() >= next_tunnel_check:
                _assert_tunnel_active(tunnel_process, port)
                next_tunnel_check = time.monotonic() + 0.25
            try:
                label, line = output_queue.get(timeout=0.1)
            except queue.Empty:
                continue
            if line is None:
                finished_streams += 1
                continue
            safe_line = redact_text(line, secrets_to_redact)
            log_stream.write(safe_line)
            log_stream.flush()
            destination = sys.stderr if label == "stderr" else sys.stdout
            destination.write(safe_line)
            destination.flush()
        return_code = process.wait()
        _assert_tunnel_active(tunnel_process, port)
    except BaseException:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        raise
    finally:
        for reader in readers:
            reader.join(timeout=1)
        log_stream.close()
        process.stdout.close()
        process.stderr.close()
    return return_code, log_path


def preflight(
    config: dict[str, str], prepared: list[tuple[str, str]], schema: str, tunnel_process
) -> None:
    if schema == BUSINESS_SCHEMA or schema == config["MVP_SOURCE_MYSQL_DATABASE"]:
        raise SafeSetupError("随机测试库不能与业务库或源连接库重名")
    connection = mysql_connection(
        config, database=config["MVP_SOURCE_MYSQL_DATABASE"], tunnel_process=tunnel_process
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT VERSION()")
            version = cursor.fetchone()[0]
            if not str(version).startswith("8."):
                raise SafeSetupError("服务器版本不是 MySQL 8.x，拒绝初始化")
            cursor.execute("SELECT DATABASE()")
            if cursor.fetchone()[0] != config["MVP_SOURCE_MYSQL_DATABASE"]:
                raise SafeSetupError("连接后的默认数据库与 .env 配置不一致，拒绝初始化")
            cursor.execute("SELECT SUBSTRING_INDEX(USER(), '@', -1)")
            validate_client_host(cursor.fetchone()[0])
            cursor.execute("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s", (schema,))
            if cursor.fetchone():
                raise SafeSetupError("随机测试库已存在，拒绝覆盖")
        print(f"只读预检通过：MySQL {version.split('-')[0]}；已验证客户端 host 为精确 IP/localhost；固定 DDL {len(prepared)} 条语句。")
    finally:
        connection.close()


def create_isolated_database(
    config: dict[str, str],
    prepared: list[tuple[str, str]],
    directory: Path,
    schema: str,
    tunnel_process=None,
) -> tuple[str, str, Path]:
    if not re.fullmatch(r"f1_it_[0-9a-f]{16}", schema):
        raise SafeSetupError("随机测试库名格式无效")
    ddl = prepared

    run_id = secrets.token_hex(16)
    app_username = "f1_it_" + secrets.token_hex(8)
    app_password = secrets.token_urlsafe(32)
    manifest = directory / f"{run_id}.state"
    account_host = "unresolved"
    created_objects: list[str] = []

    def save_manifest(status: str) -> None:
        objects = ",".join(created_objects) or "none"
        manifest.write_text(
            f"run_id={run_id}\nschema={schema}\naccount={app_username}\naccount_host={account_host}\n"
            f"server_ip={config['SERVER_IP']}\nserver_user={config['SERVER_USER']}\n"
            f"mysql_port={config['MVP_SOURCE_MYSQL_PORT']}\n"
            f"source_database={config['MVP_SOURCE_MYSQL_DATABASE']}\n"
            f"created_objects={objects}\nstatus={status}\n",
            encoding="utf-8",
        )
        os.chmod(manifest, 0o600)

    # 先落不可执行的意图记录；失败也保留精确目标与已创建对象，不自动清理。
    save_manifest("initialization-incomplete")
    connection = None
    try:
        connection = mysql_connection(
            config, database=config["MVP_SOURCE_MYSQL_DATABASE"], tunnel_process=tunnel_process
        )
        with connection.cursor() as cursor:
            cursor.execute("SELECT SUBSTRING_INDEX(USER(), '@', -1)")
            account_host = validate_client_host(cursor.fetchone()[0])
            save_manifest("initialization-incomplete")
            cursor.execute("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s", (schema,))
            if cursor.fetchone():
                raise SafeSetupError("本次随机测试库意外已存在，拒绝继续")
            cursor.execute("SELECT COUNT(*) FROM mysql.user WHERE User=%s", (app_username,))
            if cursor.fetchone()[0] != 0:
                raise SafeSetupError("随机测试账号意外已存在，拒绝继续")
            for filename, statement in ddl:
                cursor.execute(statement)
                if re.match(r"^CREATE DATABASE\b", statement, re.IGNORECASE):
                    created_objects.append(f"database:{schema}")
                    save_manifest("initialization-incomplete")
                elif re.match(r"^CREATE TABLE\b", statement, re.IGNORECASE):
                    match = re.match(r"^CREATE TABLE(?: IF NOT EXISTS)? `([^`]+)`", statement, re.IGNORECASE)
                    if match:
                        created_objects.append(f"table:{schema}.{match.group(1)}")
                        save_manifest("initialization-incomplete")
                elif re.match(r"^ALTER TABLE\b", statement, re.IGNORECASE):
                    match = re.match(r"^ALTER TABLE `([^`]+)`", statement, re.IGNORECASE)
                    if match:
                        created_objects.append(f"altered-table:{schema}.{match.group(1)}")
                        save_manifest("initialization-incomplete")
            cursor.execute(f"CREATE TABLE `{schema}`.it_run_marker (run_id CHAR(32) NOT NULL PRIMARY KEY, created_at DATETIME(3) NOT NULL) ENGINE=InnoDB")
            created_objects.append(f"table:{schema}.it_run_marker")
            save_manifest("initialization-incomplete")
            cursor.execute(f"INSERT INTO `{schema}`.it_run_marker (run_id, created_at) VALUES ({quote_sql_string(run_id)}, UTC_TIMESTAMP(3))")
            cursor.execute(
                f"CREATE USER {quote_sql_string(app_username)}@{quote_sql_string(account_host)} IDENTIFIED BY {quote_sql_string(app_password)}"
            )
            created_objects.append(f"user:{app_username}@{account_host}")
            save_manifest("initialization-incomplete")
            for privilege in ("SELECT", "INSERT", "UPDATE", "DELETE"):
                cursor.execute(f"GRANT {privilege} ON `{schema}`.* TO {quote_sql_string(app_username)}@{quote_sql_string(account_host)}")
                created_objects.append(f"grant:{privilege} on {schema}.* to {app_username}@{account_host}")
                save_manifest("initialization-incomplete")
        env_path = directory / f"{run_id}.env"
        write_env_file(env_path, schema, app_username, app_password, run_id)
        app_config = dict(config)
        app_config["MVP_SOURCE_MYSQL_USER"] = app_username
        app_config["MVP_SOURCE_MYSQL_PASSWORD"] = app_password
        app_connection = mysql_connection(
            app_config, database=schema, tunnel_process=tunnel_process
        )
        try:
            validate_it_connection(app_connection, schema, run_id, app_username, account_host)
        finally:
            app_connection.close()
        save_manifest("initialized")
        return schema, run_id, env_path
    except Exception as exc:
        save_manifest("initialization-incomplete")
        print("安全停止：初始化未完成。未自动删除任何服务器对象；请人工核验状态清单。", file=sys.stderr)
        print(f"状态清单：{manifest}", file=sys.stderr)
        if isinstance(exc, SafeSetupError):
            raise
        code = exc.args[0] if getattr(exc, "args", None) and isinstance(exc.args[0], int) else "n/a"
        raise SafeSetupError(f"服务器操作失败（{type(exc).__name__}, code={code}）") from None
    finally:
        if connection is not None:
            connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--confirm-create", action="store_true", help="明确授权创建随机测试库和受限账号")
    mode.add_argument("--run-it", metavar="RUN_ID", help="经新建的 SSH 隧道运行指定 run 的 Java Failsafe IT")
    parser.add_argument(
        "--it-tests",
        metavar="CLASSES",
        help="仅与 --run-it 联用：逗号分隔的当前源码 IT 类名（可写 Class#method）；默认运行全部 IT",
    )
    args = parser.parse_args()
    if args.it_tests is not None and not args.run_it:
        parser.error("--it-tests 只能与 --run-it 一起使用")
    try:
        # 选择器在读取 .env、建立 SSH 隧道之前校验，无效输入不会触碰任何凭据或服务器。
        it_tests = parse_it_tests(args.it_tests) if args.it_tests is not None else None
        config, redaction_values = parse_env_file(ROOT / ".env")
        directory = state_dir()
        if args.run_it:
            run_config, it_values, run_redactions = load_run_configuration(directory, args.run_it, config)
            all_redactions = redaction_values + run_redactions
            with ssh_mysql_tunnel(config, directory, all_redactions) as (port, ssh_process):
                app_config = tunnel_config(run_config, port)
                connection = mysql_connection(
                    app_config, database=it_values["F1_IT_DATABASE"], tunnel_process=ssh_process
                )
                try:
                    validate_it_connection(
                        connection,
                        it_values["F1_IT_DATABASE"],
                        it_values["F1_IT_RUN_ID"],
                        it_values["F1_IT_DB_USERNAME"],
                        run_config["MYSQL_ACCOUNT_HOST"],
                    )
                finally:
                    connection.close()
                print(f"已通过 SSH 隧道验证随机 schema 和 run marker：{it_values['F1_IT_DATABASE']}。")
                if it_tests is not None:
                    print(f"本次仅运行指定 IT：{it_tests}；仍执行 failsafe:verify。")
                return_code, log_path = run_maven_it(
                    directory, it_values, all_redactions, port, ssh_process, it_tests=it_tests
                )
            if return_code == 0:
                print(f"Java Failsafe IT 全部通过；脱敏日志：{log_path}")
            else:
                print(f"Java Failsafe IT 失败，退出码={return_code}；仅保存脱敏日志：{log_path}", file=sys.stderr)
            return return_code

        schema = "f1_it_" + secrets.token_hex(8)
        prepared = get_safe_schema_sql(schema)
        print("连接信息来自 .env，但不会显示连接值。")
        print(f"DDL 目标为随机 schema {schema}；不会复制或读取业务表数据。")
        with ssh_mysql_tunnel(config, directory, redaction_values) as (port, ssh_process):
            routed_config = tunnel_config(config, port)
            preflight(routed_config, prepared, schema, ssh_process)
            if not args.confirm_create:
                print("只读模式：当前未创建任何服务器对象。如需初始化，请显式传入 --confirm-create。")
                return 0
            schema, run_id, env_path = create_isolated_database(
                routed_config, prepared, directory, schema, ssh_process
            )
            print(f"已创建隔离测试 schema={schema}，run={run_id}。")
            print(f"环境文件（权限 0600）：{env_path}")
            print("仅将该 schema 的 SELECT/INSERT/UPDATE/DELETE 授予随机 IT 账号；未清理任何资源。")
        return 0
    except SafeSetupError as exc:
        print(f"安全停止：{exc}", file=sys.stderr)
        return 1
    except Exception as exc:  # noqa: BLE001 - CLI 边界需屏蔽未预期异常的敏感内容
        code = exc.args[0] if getattr(exc, "args", None) and isinstance(exc.args[0], int) else "n/a"
        print(f"安全停止：操作未完成（{type(exc).__name__}, code={code}）；未自动清理服务器对象。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
