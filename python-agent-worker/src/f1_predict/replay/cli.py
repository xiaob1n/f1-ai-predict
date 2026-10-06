"""真实单题 MVP 的离线安全预检命令行。"""

from __future__ import annotations

import argparse
import json
import os
import stat
import sys
import tempfile
from collections.abc import Mapping
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[4]
LEGACY_RUNTIME = ROOT / "tests" / "e2e" / ".runtime"
DEFAULT_RUN_DIR = Path.home() / ".f1predict-real-data-mvp"
SCHEMA_ALLOWLIST = ("001", "002", "003", "004", "006", "007", "008")
RESOURCE_STAGES = ("export", "prepare-isolated", "load-isolated", "run-stub", "run-real-model")


class PreflightBlocked(RuntimeError):
    """前置授权、可信元数据或实现尚不满足时拒绝继续。"""


def validate_run_dir(path: Path) -> Path:
    """强制使用专属用户目录，拒绝旧 E2E 目录及其子路径。"""
    expanded = path.expanduser().absolute()
    if expanded.parts[:2] == ("/", "var"):
        expanded = Path("/private").joinpath(*expanded.parts[1:])
    if any(component.is_symlink() for component in (expanded, *expanded.parents)):
        raise PreflightBlocked("专属运行目录路径不得含符号链接")
    resolved = expanded.resolve(strict=False)
    legacy = LEGACY_RUNTIME.resolve(strict=False)
    if resolved == legacy or legacy in resolved.parents:
        raise PreflightBlocked("禁止使用 tests/e2e/.runtime 或其子目录")
    if not resolved.is_absolute() or resolved == Path.home().resolve():
        raise PreflightBlocked("运行目录必须是 home 下独立的专属目录")
    home = Path.home().resolve()
    if home not in resolved.parents:
        raise PreflightBlocked("运行目录必须位于当前用户 home 目录内")
    home_path = Path.home().absolute()
    if home_path in expanded.parents:
        parts = (expanded, *expanded.parents[:expanded.parents.index(home_path)])
        if any(component.is_symlink() for component in parts):
            raise PreflightBlocked("专属运行目录路径不得含符号链接")
    elif expanded != resolved and expanded.is_symlink():
        raise PreflightBlocked("专属运行目录不得是符号链接")
    if resolved.exists():
        info = resolved.stat()
        if not resolved.is_dir() or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise PreflightBlocked("专属运行目录必须由当前用户拥有且权限为 0700")
    return resolved


def inventory() -> dict[str, object]:
    """只呈现代码内静态门禁，不探测网络、凭据或外部服务。"""
    return {
        "mode": "安全预检；没有实际盘点或数据读取，离线 runtime 适配仍不构成真实验收",
        "project_root": str(ROOT),
        "authorization": "未提供可核实的源读取及隔离写入授权",
        "trusted_metadata": "未提供 session 类型及跨源赛事身份可信证据",
        "schema_allowlist": list(SCHEMA_ALLOWLIST),
        "blocked_stages": list(RESOURCE_STAGES),
        "legacy_runtime_forbidden": True,
        "real_resource_access": False,
        "model_calls": False,
        "simulation": {"real": False, "m1": False, "m2": False},
    }


def blocked_stage(action: str, run_dir: Path) -> None:
    """无条件拒绝所有真实资源阶段；不创建目录或构造连接器。"""
    del run_dir
    requirements = {
        "export": "真实源读取未授权；需可信身份元数据及显式注入的只读连接",
        "prepare-isolated": "缺少明确隔离资源授权、资源身份确认及安全专属配置",
        "load-isolated": "缺少经审计的数据包、授权隔离库和可验证空库门禁",
        "run-stub": "缺少已装载的隔离副本及隔离传输/持久化实现",
        "run-real-model": "真实模型调用未获 C 阶段批准；仅有离线适配器不授予外发或收费权限",
    }
    if action not in requirements:
        raise PreflightBlocked("未知资源阶段")
    raise PreflightBlocked(f"{action} 已拒绝：{requirements[action]}；本工具仅实现安全预检")


def _simulation_state(location: Path) -> dict[str, object] | None:
    """只读取受保护的离线模拟状态，不接受它作为外部所有权证明。"""
    path = location / "workflow.json"
    if not path.exists():
        return None
    if path.is_symlink():
        raise PreflightBlocked("状态文件不得是符号链接")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > 1_048_576):
            raise PreflightBlocked("状态文件权限或大小不合格")
        data = json.load(stream)
    if not isinstance(data, dict) or data.get("mode") != "OFFLINE_SIMULATION":
        raise PreflightBlocked("本版不能接受或操作真实运行状态")
    return data


def verify(run_dir: Path) -> dict[str, object]:
    """只读查看模拟状态，不查询真实服务、不宣称真实终态验收。"""
    location = validate_run_dir(run_dir)
    data = _simulation_state(location)
    return {"run_dir": str(location), "evidence_present": data is not None,
            "status": "SIMULATION_EVIDENCE_ONLY" if data else "BLOCKED_NO_RUN_EVIDENCE",
            "stage": data.get("stage") if data else None, "external_checks": False,
            "m1Verified": False, "m2Verified": False}


def stop(run_dir: Path) -> dict[str, object]:
    """报告本工具未启动进程；不操作 Docker、数据库、Broker 或其他进程。"""
    location = validate_run_dir(run_dir)
    _simulation_state(location)
    return {"run_dir": str(location), "owned_processes": 0,
            "status": "NO_RESOURCES_STARTED", "external_actions": False}


def dry_run(
    run_dir: Path, *, keep_evidence: bool = False, schema_root: Path | None = None
) -> dict[str, Any]:
    """临时私有目录内验证离线模拟；schema 来源须由调用者明确指定。"""
    if schema_root is None or not schema_root.is_dir() or schema_root.is_symlink():
        raise PreflightBlocked("dry-run requires an explicit non-symlink --schema-root")
    try:
        from f1_predict.replay.simulation import run_simulation
    except ImportError as error:
        raise PreflightBlocked("dry-run 需 Worker 包；使用 uv run --project python-agent-worker python tests/real_data_mvp/run.py dry-run") from error
    if keep_evidence:
        directory = validate_run_dir(run_dir)
        if directory == DEFAULT_RUN_DIR or directory.exists():
            raise PreflightBlocked("保留模拟证据需指定新的专属目录，不能覆盖默认或已有目录")
        directory.mkdir(mode=0o700)
        return {**run_simulation(directory, schema_root), "evidence_directory": str(directory)}
    with tempfile.TemporaryDirectory(prefix=".f1-mvp-simulation-", dir=Path.home()) as temporary:
        directory = validate_run_dir(Path(temporary))
        return run_simulation(directory, schema_root)


def main(argv: list[str] | None = None, *, hooks: Mapping[str, Any] | None = None) -> int:
    """执行 CLI；hooks 仅供旧入口保留其公开 monkeypatch 接点。"""
    api = hooks or globals()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("inventory", *RESOURCE_STAGES, "verify", "stop", "dry-run"))
    parser.add_argument("--run-dir", type=Path, default=DEFAULT_RUN_DIR,
                        help="专属目录；默认 ~/.f1predict-real-data-mvp，禁止旧 E2E .runtime")
    parser.add_argument("--keep-evidence", action="store_true",
                        help="仅 dry-run 可用，将模拟证据保留在新的 --run-dir，不覆盖已有目录")
    parser.add_argument("--schema-root", type=Path,
                        help="dry-run 使用的显式 SQL 审阅目录；不会从安装包路径推断")
    args = parser.parse_args(argv)
    try:
        if args.keep_evidence and args.action != "dry-run":
            raise PreflightBlocked("保留模拟证据参数仅用于 dry-run")
        if args.action == "inventory":
            result = api["inventory"]()
        elif args.action == "dry-run":
            api["validate_run_dir"](args.run_dir)
            if args.schema_root is None:
                result = api["dry_run"](args.run_dir, keep_evidence=args.keep_evidence)
            else:
                result = api["dry_run"](args.run_dir, keep_evidence=args.keep_evidence,
                                         schema_root=args.schema_root)
        elif args.action in RESOURCE_STAGES:
            api["blocked_stage"](args.action, args.run_dir)
            return 2
        elif args.action == "verify":
            result = api["verify"](args.run_dir)
        else:
            result = api["stop"](args.run_dir)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except PreflightBlocked as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    except (OSError, ValueError, RuntimeError):
        print("BLOCKED: 本地状态、回放包或模拟校验失败；没有执行真实资源操作", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
