"""健康检查契约：存活 200 UP，就绪 503 DOWN 且四项未配置。"""

from __future__ import annotations

from fastapi.testclient import TestClient

from f1_predict.api.app import app

_READY_CHECK_KEYS = ("rabbitmq", "mongodb", "qdrant", "model")


def test_health_live_returns_up() -> None:
    """Given 进程已启动, When GET /health/live, Then 200 且 status=UP."""
    client = TestClient(app)

    response = client.get("/health/live")

    assert response.status_code == 200
    payload = response.json()
    assert payload == {"status": "UP"}
    assert "status" in payload
    assert "_" not in "".join(payload)


def test_ready_returns_503_down() -> None:
    """Given 阶段一未接外部依赖, When GET /health/ready, Then 503 DOWN 且四键 NOT_CONFIGURED."""
    client = TestClient(app)

    response = client.get("/health/ready")

    assert response.status_code == 503
    payload = response.json()
    assert payload["status"] == "DOWN"
    checks = payload["checks"]
    assert set(checks) == set(_READY_CHECK_KEYS)
    for key in _READY_CHECK_KEYS:
        assert checks[key] == "NOT_CONFIGURED"
    dumped = response.text
    assert "traceback" not in dumped.lower()
    assert "amqp://" not in dumped
    assert "mongodb://" not in dumped
