"""兼容旧 run 导入路径；实现统一位于 Worker replay CLI。"""

from __future__ import annotations

import sys
from pathlib import Path  # 旧 run.Path.home monkeypatch 接口保持可用。

_WORKER_SRC = Path(__file__).resolve().parents[2] / "python-agent-worker" / "src"
if _WORKER_SRC.is_dir() and str(_WORKER_SRC) not in sys.path:
    sys.path.insert(0, str(_WORKER_SRC))

from f1_predict.replay import cli as _cli

ROOT = _cli.ROOT
LEGACY_RUNTIME = _cli.LEGACY_RUNTIME
DEFAULT_RUN_DIR = _cli.DEFAULT_RUN_DIR
SCHEMA_ALLOWLIST = _cli.SCHEMA_ALLOWLIST
PreflightBlocked = _cli.PreflightBlocked
validate_run_dir = _cli.validate_run_dir
inventory = _cli.inventory
blocked_stage = _cli.blocked_stage
_simulation_state = _cli._simulation_state
verify = _cli.verify
stop = _cli.stop
dry_run = _cli.dry_run


def main(argv: list[str] | None = None) -> int:
    """保留旧模块的 monkeypatch 接点并委托统一 CLI。"""
    return _cli.main(argv, hooks={
        "validate_run_dir": validate_run_dir,
        "inventory": inventory,
        "blocked_stage": blocked_stage,
        "verify": verify,
        "stop": stop,
        "dry_run": dry_run,
    })


if __name__ == "__main__":
    raise SystemExit(main())
