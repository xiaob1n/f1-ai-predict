"""Worker 状态契约：完整 camelCase 字段、真实心跳与占位依赖。"""

from __future__ import annotations

import socket
from datetime import UTC, datetime, timedelta

import pytest
from f1_predict.api import worker_status as worker_status_module
from f1_predict.api.app import app
from fastapi.testclient import TestClient

_TOP_LEVEL_KEYS = {
    "workerNode",
    "status",
    "currentJobId",
    "lastHeartbeat",
    "uptimeSeconds",
    "gpu",
    "modelLoaded",
    "rabbitmqConnected",
    "queueDepthEstimate",
}
_GPU_KEYS = {"memoryUsedBytes", "memoryTotalBytes", "utilization"}


def _client() -> TestClient:
    return TestClient(app)


def test_worker_status_returns_design_fields() -> None:
    """Given 阶段一 Worker, When GET /api/v1/worker/status, Then 200 且含设计字段集."""
    response = _client().get("/api/v1/worker/status")

    assert response.status_code == 200
    payload = response.json()
    assert set(payload) == _TOP_LEVEL_KEYS
    assert set(payload["gpu"]) == _GPU_KEYS
    assert payload["status"] == "IDLE"
    assert payload["currentJobId"] is None
    assert payload["modelLoaded"] is False
    assert payload["rabbitmqConnected"] is False
    assert payload["queueDepthEstimate"] == 0
    assert payload["gpu"]["memoryUsedBytes"] == 0
    assert payload["gpu"]["memoryTotalBytes"] == 0
    assert payload["gpu"]["utilization"] == 0.0
    assert isinstance(payload["uptimeSeconds"], int)
    assert payload["uptimeSeconds"] >= 0
    dumped = response.text
    assert "worker_node" not in dumped
    assert "current_job_id" not in dumped
    assert "last_heartbeat" not in dumped
    assert "uptime_seconds" not in dumped
    assert "model_loaded" not in dumped
    assert "rabbitmq_connected" not in dumped
    assert "queue_depth_estimate" not in dumped
    assert "memory_used_bytes" not in dumped


def test_worker_status_heartbeat_is_aware_utc_z() -> None:
    """Given 状态查询, When 读取 lastHeartbeat, Then 为带 Z 的时区感知 UTC."""
    before = datetime.now(UTC)
    payload = _client().get("/api/v1/worker/status").json()
    after = datetime.now(UTC)

    stamp = payload["lastHeartbeat"]
    assert isinstance(stamp, str)
    assert stamp.endswith("Z")
    assert "+00:00" not in stamp
    parsed = datetime.fromisoformat(stamp)
    assert parsed.tzinfo is not None
    assert parsed.utcoffset() == timedelta(0)
    assert parsed >= before.replace(microsecond=0)
    assert parsed <= after + timedelta(seconds=2)


def test_worker_status_uptime_uses_monotonic() -> None:
    """Given 连续两次查询, When 比较 uptimeSeconds, Then 第二次不小于第一次."""
    first = _client().get("/api/v1/worker/status").json()["uptimeSeconds"]
    second = _client().get("/api/v1/worker/status").json()["uptimeSeconds"]
    assert second >= first


def test_worker_node_hostname_or_local_dev() -> None:
    """Given 本机 hostname 可用, When 查询状态, Then workerNode 为 hostname 或 local-dev."""
    payload = _client().get("/api/v1/worker/status").json()
    try:
        expected = socket.gethostname() or "local-dev"
    except OSError:
        expected = "local-dev"
    assert payload["workerNode"] == expected


def test_worker_node_hostname_fallback(monkeypatch: pytest.MonkeyPatch) -> None:
    """Given gethostname 失败, When 查询状态, Then workerNode 回退 local-dev."""

    def _fail() -> str:
        raise OSError("resolver unavailable")

    monkeypatch.setattr(worker_status_module.socket, "gethostname", _fail)

    payload = _client().get("/api/v1/worker/status").json()
    assert payload["workerNode"] == "local-dev"
