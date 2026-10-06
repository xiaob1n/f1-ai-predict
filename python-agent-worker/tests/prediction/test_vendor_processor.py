"""DeepSeek 仅通过处理器公开边界进行离线集成验证。"""

from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from f1_predict.features.repository import InMemoryLapRepository, LapRecord
from f1_predict.messaging.dto.request_v2 import PredictionRequestV2
from f1_predict.prediction.model_calls import ModelCallLedger
from f1_predict.prediction.model_vendor_api import (
    DeepSeekConfig,
    DeepSeekModel,
    ModelCallRejected,
    ModelCallUncertain,
    ModelInvocation,
)
from f1_predict.prediction.policy import SnapshotPolicy
from f1_predict.prediction.processor import PredictionProcessor
from f1_predict.reliability.idempotency import InboxStore


class FakeTransport:
    """模拟固定响应的异步厂商边界，不访问网络。"""

    def __init__(self, *responses: dict[str, object] | Exception) -> None:
        self.responses = list(responses)
        self.calls = 0

    async def send(self, **_: object) -> dict[str, object]:
        self.calls += 1
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


def _response(status: int = 200, option_id: int = 10) -> dict[str, object]:
    return {
        "status_code": status,
        "body": {
            "model": "deepseek-flash",
            "usage": {"total_tokens": 12},
            "choices": [{
                "finish_reason": "stop",
                "message": {
                    "content": json.dumps({
                        "option_ids": [option_id], "confidence": 0.75,
                        "reasoning_summary": "圈速对照",
                    }) if status == 200 else "",
                },
            }],
        },
    }


def _setup(
    tmp_path: Path, payload: dict[str, object], transport: FakeTransport,
) -> tuple[InboxStore, PredictionProcessor]:
    question = payload["question"]
    race = payload["raceContext"]
    assert isinstance(question, dict) and isinstance(race, dict)
    question.update({
        "questionText": "练习赛后哪位车手更快？", "choiceLimit": 1,
        "options": [
            {"optionId": 10, "optionNo": 1, "optionText": "车手 11", "points": None, "chance": None},
            {"optionId": 20, "optionNo": 2, "optionText": "车手 22", "points": None, "chance": None},
        ],
    })
    race.update({"meetingKey": 2026, "sessionKey": 91})
    payload["modelVersion"] = "deepseek-flash"
    policy = SnapshotPolicy(
        question_snapshot_id=11, question_id=7, question_text=question["questionText"],
        meeting_key=2026, session_keys=(91,), option_drivers={10: 11, 20: 22},
        minimum_laps=3, model_version="deepseek-flash", prompt_version="prompt-v1",
        feature_version="feature-v1",
    )
    store = InboxStore(tmp_path / "inbox.sqlite3")
    store.save(PredictionRequestV2.model_validate(payload))
    start = datetime(2026, 9, 27, 7, tzinfo=UTC)
    laps = [
        LapRecord(
            record_id=f"{driver}-{index}", meeting_key=2026, session_key=91,
            driver_number=driver, event_time=start + timedelta(minutes=index),
            first_seen_at=start + timedelta(minutes=index + 1),
            lap_end=start + timedelta(minutes=index + 2),
            duration_seconds=89.0 + index, is_clean=True,
            source_endpoint="/laps", source_content_hash=f"{driver}-{index}",
        )
        for driver in (11, 22) for index in range(3)
    ]
    model = DeepSeekModel(
        DeepSeekConfig(api_key="offline-test", model="deepseek-flash"),
        transport=transport,  # type: ignore[arg-type]
        ledger=ModelCallLedger(
            tmp_path / "ledger.sqlite3", max_calls=3,
            call_reserve_microunits=10, total_budget_microunits=30,
        ),
    )
    return store, PredictionProcessor(
        store, InMemoryLapRepository(laps), model, policy, max_attempts=3,
    )


def test_processor_binds_frozen_identity_and_persists_one_success(
    tmp_path: Path, request_v2_payload: dict[str, object],
) -> None:
    transport = FakeTransport(_response())
    store, processor = _setup(tmp_path, request_v2_payload, transport)

    assert asyncio.run(processor.process_once()) is True

    event, = store.pending_outbox()
    assert event.outcome_type == "RESULT"
    assert json.loads(event.payload_json)["selectedOptions"] == [{"optionId": 10, "position": 1}]
    assert transport.calls == 1


@pytest.mark.parametrize(
    "failure",
    [
        TimeoutError("timeout"),
        ModelCallUncertain("unknown"),
        ModelCallRejected("rejected"),
        _response(401),
        _response(402),
        _response(200, option_id=99),
    ],
)
def test_uncertain_or_permanent_vendor_failure_is_terminal_without_retry(
    tmp_path: Path, request_v2_payload: dict[str, object], failure: dict[str, object] | Exception,
) -> None:
    transport = FakeTransport(failure)
    store, processor = _setup(tmp_path, request_v2_payload, transport)

    asyncio.run(processor.process_once())

    event, = store.pending_outbox()
    assert event.outcome_type == "FAILURE"
    assert json.loads(event.payload_json)["failureCode"] == "MODEL_FAILED"
    assert transport.calls == 1


def test_vendor_retryable_failure_obeys_finite_attempt_limit(
    tmp_path: Path, request_v2_payload: dict[str, object], monkeypatch: pytest.MonkeyPatch,
) -> None:
    transport = FakeTransport(_response(429), _response(200))
    store, processor = _setup(tmp_path, request_v2_payload, transport)
    processor.max_attempts = 2
    original_claim_next = store.claim_next
    claim_number = 0
    now = datetime.now(UTC)

    def claim_next(*, lease_seconds: int, now: datetime | None = None):
        nonlocal claim_number
        claim_number += 1
        effective_now = datetime.now(UTC) if claim_number == 1 else now_for_retry
        return original_claim_next(lease_seconds=lease_seconds, now=effective_now)

    now_for_retry = now + timedelta(seconds=3)
    monkeypatch.setattr(store, "claim_next", claim_next)

    assert asyncio.run(processor.process_once()) is True
    assert asyncio.run(processor.process_once()) is True

    assert transport.calls == 2
    assert store.pending_outbox()[0].outcome_type == "RESULT"


def test_existing_ledger_identity_conflict_fails_closed_without_another_vendor_send(
    tmp_path: Path, request_v2_payload: dict[str, object],
) -> None:
    transport = FakeTransport(_response())
    store, processor = _setup(tmp_path, request_v2_payload, transport)
    assert isinstance(processor.model, DeepSeekModel)
    seeded = processor.model.bind(ModelInvocation(
        prediction_job_id="job-1", attempt=1, lease_token="previous-lease",
        allowed_option_ids=(10,), data_cutoff=datetime(2026, 9, 27, 8, tzinfo=UTC),
        model_version="deepseek-flash", prompt_version="prompt-v1",
        feature_version="feature-v1",
    ))
    seeded_context = json.dumps({
        "question": "练习赛后哪位车手更快？",
        "options": [{"optionId": 10, "optionText": "车手 11"}],
        "features": {"drivers": [
            {"driverNumber": 11, "cleanLapCount": 3, "medianLapSeconds": 90.0},
        ]},
    }, ensure_ascii=False)
    asyncio.run(seeded.predict(seeded_context))

    assert asyncio.run(processor.process_once()) is True

    event, = store.pending_outbox()
    assert event.outcome_type == "FAILURE"
    assert json.loads(event.payload_json)["failureCode"] == "VERSION_UNAVAILABLE"
    assert transport.calls == 1


def test_ledger_cached_candidate_survives_storage_oserror_and_reopen(
    tmp_path: Path, request_v2_payload: dict[str, object], monkeypatch: pytest.MonkeyPatch,
) -> None:
    transport = FakeTransport(_response())
    store, processor = _setup(tmp_path, request_v2_payload, transport)
    inbox_path = tmp_path / "inbox.sqlite3"
    original_claim_next = store.claim_next
    first_claim_time = datetime.now(UTC)
    recovery_time = first_claim_time + timedelta(minutes=5)
    claims = 0

    def claim_next(*, lease_seconds: int, now: datetime | None = None):
        nonlocal claims
        claims += 1
        effective_now = first_claim_time if claims == 1 else recovery_time
        return original_claim_next(lease_seconds=lease_seconds, now=effective_now)

    monkeypatch.setattr(store, "claim_next", claim_next)
    original_finish = store.finish
    finish_calls = 0

    def fail_first_finish(*args: object, **kwargs: object) -> None:
        nonlocal finish_calls
        finish_calls += 1
        if finish_calls == 1:
            raise OSError("simulated SQLite storage failure")
        original_finish(*args, **kwargs)  # type: ignore[arg-type]

    monkeypatch.setattr(store, "finish", fail_first_finish)
    with pytest.raises(OSError, match="simulated SQLite storage failure"):
        asyncio.run(processor.process_once())

    reopened_store = InboxStore(inbox_path)
    reopened_claim_next = reopened_store.claim_next
    monkeypatch.setattr(
        reopened_store,
        "claim_next",
        lambda *, lease_seconds: reopened_claim_next(
            lease_seconds=lease_seconds, now=recovery_time
        ),
    )
    reopened_model = DeepSeekModel(
        DeepSeekConfig(api_key="offline-test", model="deepseek-flash"),
        transport=transport,  # type: ignore[arg-type]
        ledger=ModelCallLedger(
            tmp_path / "ledger.sqlite3", max_calls=3,
            call_reserve_microunits=10, total_budget_microunits=30,
        ),
    )
    recovered = PredictionProcessor(
        reopened_store, processor.repository, reopened_model, processor.policy,
    )

    assert asyncio.run(recovered.process_once()) is True
    assert transport.calls == 1
    assert reopened_store.pending_outbox()[0].outcome_type == "RESULT"


def test_third_paid_candidate_survives_fourth_claim_after_finish_failure(
    tmp_path: Path, request_v2_payload: dict[str, object], monkeypatch: pytest.MonkeyPatch,
) -> None:
    """第三次收费结果落盘后，第四次领取仍可恢复，不放宽三次请求预算。"""
    transport = FakeTransport(_response(429), _response(429), _response())
    store, processor = _setup(tmp_path, request_v2_payload, transport)
    start = datetime.now(UTC)
    claim_times = iter(start + timedelta(seconds=offset) for offset in (0, 3, 8))
    claim_next = store.claim_next
    monkeypatch.setattr(
        store, "claim_next",
        lambda *, lease_seconds: claim_next(lease_seconds=lease_seconds, now=next(claim_times)),
    )

    def unavailable_storage(*args: object, **kwargs: object) -> None:
        raise OSError("offline terminal storage interrupted")

    monkeypatch.setattr(store, "finish", unavailable_storage)
    assert asyncio.run(processor.process_once()) is True
    assert asyncio.run(processor.process_once()) is True
    with pytest.raises(OSError, match="terminal storage interrupted"):
        asyncio.run(processor.process_once())
    assert transport.calls == 3
    assert store.pending_outbox() == []

    recovered_store = InboxStore(tmp_path / "inbox.sqlite3")
    recovered_claim_next = recovered_store.claim_next
    monkeypatch.setattr(
        recovered_store, "claim_next",
        lambda *, lease_seconds: recovered_claim_next(
            lease_seconds=lease_seconds, now=start + timedelta(minutes=5),
        ),
    )
    ledger = ModelCallLedger(
        tmp_path / "ledger.sqlite3", max_calls=3,
        call_reserve_microunits=10, total_budget_microunits=30,
    )
    recovered = PredictionProcessor(
        recovered_store, processor.repository,
        DeepSeekModel(
            DeepSeekConfig(api_key="offline-test", model="deepseek-flash"),
            transport=transport, ledger=ledger,
        ),
        processor.policy,
    )

    assert asyncio.run(recovered.process_once()) is True
    assert asyncio.run(recovered.process_once()) is False
    event, = recovered_store.pending_outbox()
    assert event.outcome_type == "RESULT"
    assert transport.calls == 3
    assert ledger.snapshot().reserved_calls == 3
    assert ledger.snapshot().reserved_microunits == 30
