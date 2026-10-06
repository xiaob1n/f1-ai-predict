"""Worker 厂商默认启动拒绝与离线显式构建边界。"""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from f1_predict.common.config import Settings
from f1_predict.prediction.model_calls import ModelCallLedger
from f1_predict.prediction.model_vendor_api import DeepSeekConfig, DeepSeekModel
from f1_predict.reliability.idempotency import InboxStore
from f1_predict.worker.main import build_prediction_processor, run


def test_default_run_refuses_deepseek_before_any_storage_or_broker_io(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        prediction_enabled=True, model_provider="deepseek",
        prediction_policy_path=str(tmp_path / "missing-policy.json"),
        prediction_laps_path=str(tmp_path / "missing-laps.json"),
        model_version="deepseek-flash", deepseek_model="deepseek-flash",
        prompt_version="prompt-v1", feature_version="feature-v1",
        deepseek_ledger_path=str(tmp_path / "missing-ledger.sqlite3"),
        deepseek_approval_id="offline-review",
        deepseek_max_cost_micro_usd=20, deepseek_reservation_micro_usd=10,
        consumer_health_path=str(tmp_path / "must-not-exist.health.json"),
    )
    settings.vendor_api_enabled = True
    settings.rabbitmq_url = "amqp://should-not-be-used"
    settings.consumer_sqlite_path = str(tmp_path / "must-not-exist.sqlite3")

    async def unexpected_connect(*args: object, **kwargs: object) -> None:
        raise AssertionError("broker must not be touched")

    monkeypatch.setattr("f1_predict.worker.main.aio_pika.connect", unexpected_connect)
    with pytest.raises(ValueError, match="stage C"):
        asyncio.run(run(settings))

    assert not (tmp_path / "must-not-exist.sqlite3").exists()


def test_default_deepseek_processor_builder_requires_injected_model_before_fixture_io(
    tmp_path: Path,
) -> None:
    settings = Settings(
        prediction_enabled=True,
        model_provider="deepseek",
        prediction_policy_path=str(tmp_path / "missing-policy.json"),
        prediction_laps_path=str(tmp_path / "missing-laps.json"),
        model_version="deepseek-flash", deepseek_model="deepseek-flash",
        prompt_version="prompt-v1", feature_version="feature-v1",
        deepseek_ledger_path=str(tmp_path / "missing-ledger.sqlite3"),
        deepseek_approval_id="offline-review",
        deepseek_max_cost_micro_usd=20, deepseek_reservation_micro_usd=10,
    )
    inbox = InboxStore(tmp_path / "inbox.sqlite3")

    with pytest.raises(ValueError, match="DeepSeekModel"):
        build_prediction_processor(settings, inbox)

    assert not (tmp_path / "missing-ledger.sqlite3").exists()


def test_offline_builder_accepts_explicit_model_and_clamps_attempts(
    tmp_path: Path,
) -> None:
    policy_path = tmp_path / "policy.json"
    policy_path.write_text(
        '{"question_snapshot_id":11,"question_id":7,"question_text":"Q?",'
        '"meeting_key":2026,"session_keys":[91],"option_drivers":{"10":11,"20":22},'
        '"minimum_laps":3,"model_version":"deepseek-flash",'
        '"prompt_version":"prompt-v1","feature_version":"feature-v1"}',
        encoding="utf-8",
    )
    laps_path = tmp_path / "laps.json"
    laps_path.write_text("[]", encoding="utf-8")
    ledger_path = tmp_path / "ledger.sqlite3"
    settings = Settings(
        prediction_enabled=True, model_provider="deepseek",
        prediction_policy_path=str(policy_path), prediction_laps_path=str(laps_path),
        model_version="deepseek-flash", deepseek_model="deepseek-flash",
        prompt_version="prompt-v1", feature_version="feature-v1",
        model_timeout_seconds=30.0, model_max_attempts=3,
        deepseek_ledger_path=str(ledger_path), deepseek_approval_id="offline-review",
        deepseek_max_calls=2, deepseek_max_cost_micro_usd=20,
        deepseek_reservation_micro_usd=10,
    )
    model = DeepSeekModel(
        DeepSeekConfig(api_key="offline-test", model="deepseek-flash", timeout_seconds=30.0),
        transport=object(),  # type: ignore[arg-type]
        ledger=ModelCallLedger(
            ledger_path, max_calls=2, call_reserve_microunits=10,
            total_budget_microunits=20,
        ),
    )

    processor = build_prediction_processor(
        settings, InboxStore(tmp_path / "inbox.sqlite3"), vendor_model=model,
    )

    assert processor.model is model
    assert processor.max_attempts == 2


def test_offline_builder_rejects_injected_budget_mismatch_before_fixture_reads(
    tmp_path: Path,
) -> None:
    policy_path = tmp_path / "missing-policy.json"
    laps_path = tmp_path / "missing-laps.json"
    ledger_path = tmp_path / "ledger.sqlite3"
    settings = Settings(
        prediction_enabled=True, model_provider="deepseek",
        prediction_policy_path=str(policy_path), prediction_laps_path=str(laps_path),
        model_version="deepseek-flash", deepseek_model="deepseek-flash",
        prompt_version="prompt-v1", feature_version="feature-v1",
        deepseek_ledger_path=str(ledger_path), deepseek_approval_id="offline-review",
        deepseek_max_calls=3, deepseek_max_cost_micro_usd=20,
        deepseek_reservation_micro_usd=10,
    )
    model = DeepSeekModel(
        DeepSeekConfig(api_key="offline-test", model="deepseek-flash"),
        transport=object(),  # type: ignore[arg-type]
        ledger=ModelCallLedger(
            ledger_path, max_calls=2, call_reserve_microunits=10,
            total_budget_microunits=20,
        ),
    )

    with pytest.raises(ValueError, match="differ from the frozen configuration"):
        build_prediction_processor(
            settings, InboxStore(tmp_path / "inbox.sqlite3"), vendor_model=model,
        )

    assert not policy_path.exists()
    assert not laps_path.exists()
