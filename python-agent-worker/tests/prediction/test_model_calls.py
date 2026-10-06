"""持久模型调用账本的恢复、去重与硬预算行为。"""

from __future__ import annotations

import hashlib
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path

import pytest

from f1_predict.prediction.model import ModelCandidate
from f1_predict.prediction.model_calls import (
    CallBudgetExceeded,
    CallIdentityConflict,
    CallNotPermitted,
    ModelCallLedger,
)
from f1_predict.prediction.model_vendor_api import ModelInvocation


def invocation(*, attempt: int = 1, lease: str = "lease-1", job: str = "job-1") -> ModelInvocation:
    return ModelInvocation(
        prediction_job_id=job,
        attempt=attempt,
        lease_token=lease,
        allowed_option_ids=(10, 20),
        data_cutoff=datetime(2026, 9, 1, tzinfo=UTC),
        model_version="deepseek-flash",
        prompt_version="prompt-v1",
        feature_version="feature-v1",
    )


def reserve(ledger: ModelCallLedger, inv: ModelInvocation = None, *, input_hash: str = "input-a"):
    return ledger.reserve(
        inv or invocation(), prompt_hash="a" * 64, config_hash="b" * 64,
        input_hash=hashlib.sha256(input_hash.encode()).hexdigest(),
    )


def test_ledger_rejects_existing_database_with_insecure_permissions(tmp_path: Path) -> None:
    path = tmp_path / "calls.sqlite3"
    ledger = ModelCallLedger(path, max_calls=1, call_reserve_microunits=10, total_budget_microunits=10)
    assert Path(ledger.path).stat().st_mode & 0o777 == 0o600
    path.chmod(0o644)

    with pytest.raises(ValueError, match="private owned regular file"):
        ModelCallLedger(path, max_calls=1, call_reserve_microunits=10, total_budget_microunits=10)


def test_ledger_rejects_symlink_at_any_path_component(tmp_path: Path) -> None:
    actual_dir = tmp_path / "actual"
    actual_dir.mkdir()
    linked_dir = tmp_path / "linked"
    linked_dir.symlink_to(actual_dir, target_is_directory=True)

    with pytest.raises(ValueError, match="symlinks"):
        ModelCallLedger(linked_dir / "calls.sqlite3", max_calls=1, call_reserve_microunits=10, total_budget_microunits=10)


def test_invocation_attempt_tracks_leases_not_the_three_call_budget() -> None:
    assert invocation(attempt=4, lease="lease-after-recovery").attempt == 4


def test_success_candidate_survives_restart_and_is_returned_without_new_call(tmp_path: Path) -> None:
    path = tmp_path / "calls.sqlite3"
    first = ModelCallLedger(path, max_calls=3, call_reserve_microunits=100, total_budget_microunits=300)
    intent = reserve(first)
    assert intent.should_call is True
    candidate = ModelCandidate(option_ids=[10], confidence=0.75, reasoning_summary="圈速比较")
    first.mark_success(
        intent.call_id, candidate, returned_model="deepseek-flash",
        usage={"total_tokens": 12, "api_key": "do-not-store", "private": "sensitive"},
    )

    recovered = ModelCallLedger(path, max_calls=3, call_reserve_microunits=100, total_budget_microunits=300)
    cached = reserve(recovered, invocation(attempt=2, lease="new-lease"))

    assert cached.should_call is False
    assert cached.cached_candidate == candidate
    assert recovered.snapshot().reserved_calls == 1
    assert recovered.snapshot().reserved_microunits == 100
    assert recovered.snapshot().calls[0].usage == {"total_tokens": 12}
    assert "secret" not in repr(recovered.snapshot())


def test_uncertain_intent_blocks_new_lease_and_changed_input(tmp_path: Path) -> None:
    ledger = ModelCallLedger(tmp_path / "calls.sqlite3", max_calls=3, call_reserve_microunits=10, total_budget_microunits=30)
    intent = reserve(ledger)
    ledger.mark_uncertain(intent.call_id)

    with pytest.raises(CallNotPermitted):
        reserve(ledger, invocation(attempt=2, lease="new-lease"))
    with pytest.raises(CallIdentityConflict):
        reserve(ledger, invocation(attempt=2, lease="new-lease"), input_hash="changed")


def test_concurrent_reservations_for_same_job_create_one_intent(tmp_path: Path) -> None:
    path = tmp_path / "calls.sqlite3"
    ledgers = [ModelCallLedger(path, max_calls=3, call_reserve_microunits=10, total_budget_microunits=30) for _ in range(8)]
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(reserve, ledgers))

    assert sum(item.should_call for item in results) == 1
    assert len({item.call_id for item in results}) == 1


def test_per_job_budget_does_not_block_a_different_job(tmp_path: Path) -> None:
    ledger = ModelCallLedger(tmp_path / "calls.sqlite3", max_calls=1, call_reserve_microunits=10, total_budget_microunits=10)
    first = reserve(ledger, invocation(job="job-1"))
    second = reserve(ledger, invocation(job="job-2"))

    assert first.should_call and second.should_call
    assert first.call_id != second.call_id
    assert ledger.snapshot().reserved_calls == 2
    assert ledger.snapshot().reserved_microunits == 20


def test_retry_attempt_can_advance_across_recovered_lease_numbers(tmp_path: Path) -> None:
    ledger = ModelCallLedger(
        tmp_path / "calls.sqlite3", max_calls=3,
        call_reserve_microunits=10, total_budget_microunits=30,
    )
    first = reserve(ledger, invocation(attempt=1))
    ledger.mark_rejected(first.call_id, retryable=True)

    second = reserve(ledger, invocation(attempt=4, lease="lease-after-recovery"))

    assert second.should_call is True
    assert ledger.snapshot().reserved_calls == 2
    ledger.mark_rejected(second.call_id, retryable=True)
    with pytest.raises(CallNotPermitted):
        reserve(ledger, invocation(attempt=4, lease="duplicate-lease"))


def test_call_count_and_reserved_budget_are_durable_hard_limits(tmp_path: Path) -> None:
    ledger = ModelCallLedger(tmp_path / "calls.sqlite3", max_calls=2, call_reserve_microunits=75, total_budget_microunits=150)
    first = reserve(ledger)
    ledger.mark_rejected(first.call_id, retryable=True)
    second = reserve(ledger, invocation(attempt=2, lease="lease-2"))
    ledger.mark_rejected(second.call_id, retryable=True)

    with pytest.raises(CallBudgetExceeded):
        reserve(ledger, invocation(attempt=3, lease="lease-3"))
    assert ledger.snapshot().reserved_calls == 2
    assert ledger.snapshot().reserved_microunits == 150


def test_ledger_rejects_symlink_database_path(tmp_path: Path) -> None:
    target = tmp_path / "target.sqlite3"
    target.touch(mode=0o600)
    alias = tmp_path / "alias.sqlite3"
    alias.symlink_to(target)

    with pytest.raises(ValueError, match="symlink"):
        ModelCallLedger(alias, max_calls=1, call_reserve_microunits=1, total_budget_microunits=1)


def test_ledger_rejects_hardlinked_or_overpermissive_existing_database(tmp_path: Path) -> None:
    linked = tmp_path / "linked.sqlite3"
    linked.touch(mode=0o600)
    hard_link = tmp_path / "hard-link.sqlite3"
    hard_link.hardlink_to(linked)
    with pytest.raises(ValueError, match="hard links"):
        ModelCallLedger(linked, max_calls=1, call_reserve_microunits=1, total_budget_microunits=1)

    hard_link.unlink()
    linked.chmod(0o640)
    with pytest.raises(ValueError, match="private owned regular file"):
        ModelCallLedger(linked, max_calls=1, call_reserve_microunits=1, total_budget_microunits=1)


def test_ledger_rejects_nonprivate_parent_directory(tmp_path: Path) -> None:
    parent = tmp_path / "shared"
    parent.mkdir(mode=0o700)
    parent.chmod(0o755)

    with pytest.raises(ValueError, match="parent directory must be private"):
        ModelCallLedger(parent / "calls.sqlite3", max_calls=1, call_reserve_microunits=1, total_budget_microunits=1)


def test_nonretryable_rejection_blocks_a_second_call(tmp_path: Path) -> None:
    ledger = ModelCallLedger(tmp_path / "calls.sqlite3", max_calls=3, call_reserve_microunits=10, total_budget_microunits=30)
    first = reserve(ledger)
    ledger.mark_rejected(first.call_id)
    with pytest.raises(CallNotPermitted):
        reserve(ledger, invocation(attempt=2, lease="lease-2"))


def test_ledger_rejects_cached_candidate_outside_reserved_options(tmp_path: Path) -> None:
    ledger = ModelCallLedger(tmp_path / "calls.sqlite3", max_calls=3, call_reserve_microunits=10, total_budget_microunits=30)
    first = reserve(ledger)
    invalid = ModelCandidate(option_ids=[99], confidence=0.4, reasoning_summary="不允许")

    with pytest.raises(CallNotPermitted):
        ledger.mark_success(first.call_id, invalid, returned_model="deepseek-flash", usage=None)
