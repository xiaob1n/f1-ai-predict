"""离线对照：按同一冻结练习赛特征计算可重复的圈速基线。"""

from __future__ import annotations

from f1_predict.features.builder import FeatureSnapshot, FeatureStatus
from f1_predict.prediction.policy import BothAdvanceQ1Policy, SnapshotPolicy


def median_lap_baseline(snapshot: FeatureSnapshot, policy: SnapshotPolicy) -> int | None:
    """仅当所有候选满足策略覆盖门槛时输出最快中位圈的选项 ID。"""
    if snapshot.status != FeatureStatus.READY:
        return None
    by_driver = {item.driver_number: item for item in snapshot.drivers}
    if any(
        number not in by_driver or by_driver[number].clean_lap_count < policy.minimum_laps
        for number in policy.option_drivers.values()
    ):
        return None
    return min(
        policy.option_drivers,
        key=lambda option_id: (
            by_driver[policy.option_drivers[option_id]].median_lap_seconds,
            option_id,
        ),
    )


def q1_median_lap_baseline(
    snapshot: FeatureSnapshot,
    policy: BothAdvanceQ1Policy,
) -> int | None:
    """圈速覆盖完整时，估计两名目标车手能否同时从 Q1 晋级 Q2。"""
    if snapshot.status != FeatureStatus.READY:
        return None
    by_driver = {item.driver_number: item for item in snapshot.drivers}
    if len(by_driver) != len(snapshot.drivers) or any(
        number not in by_driver or by_driver[number].clean_lap_count < policy.minimum_laps
        for number in policy.participant_drivers
    ):
        return None

    ranked = sorted(
        policy.participant_drivers,
        key=lambda number: (by_driver[number].median_lap_seconds, number),
    )
    boundary_time = by_driver[ranked[policy.q1_advancement_slots - 1]].median_lap_seconds
    boundary_tied = [
        number for number in ranked
        if by_driver[number].median_lap_seconds == boundary_time
    ]
    ahead_count = sum(
        by_driver[number].median_lap_seconds < boundary_time
        for number in policy.participant_drivers
    )
    if ahead_count < policy.q1_advancement_slots < ahead_count + len(boundary_tied):
        return None

    advancing = set(ranked[:policy.q1_advancement_slots])
    return policy.yes_option_id if set(policy.target_drivers) <= advancing else policy.no_option_id
