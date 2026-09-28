"""仅接受人工登记并与冻结请求完全匹配的题目策略。"""

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from f1_predict.features.repository import MAX_DRIVER_NUMBERS
from f1_predict.messaging.dto.request_v2 import PredictionRequestV2


class UnsupportedQuestion(ValueError):
    """请求没有可验证的单选题策略。"""


class VersionUnavailable(ValueError):
    """请求版本无法严格匹配当前策略。"""


class SnapshotPolicy(BaseModel):
    """题目语义由受控配置提供，绝不从自然语言或 choiceLimit 猜测。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    question_snapshot_id: int = Field(gt=0)
    question_id: int = Field(gt=0)
    question_text: str = Field(min_length=1)
    meeting_key: int = Field(gt=0)
    session_keys: tuple[int, ...] = Field(min_length=1, max_length=3)
    option_drivers: dict[int, int] = Field(min_length=2, max_length=MAX_DRIVER_NUMBERS)
    minimum_laps: int = Field(default=3, ge=1, le=100)
    model_version: str = Field(min_length=1)
    prompt_version: str = Field(min_length=1)
    feature_version: str = Field(min_length=1)

    @model_validator(mode="after")
    def valid_drivers(self) -> SnapshotPolicy:
        """拒绝重复车号及无效的选项映射。"""
        if any(option <= 0 or driver <= 0 for option, driver in self.option_drivers.items()):
            raise ValueError("option and driver numbers must be positive")
        if len(set(self.session_keys)) != len(self.session_keys):
            raise ValueError("duplicate session keys")
        if len(set(self.option_drivers.values())) != len(self.option_drivers):
            raise ValueError("duplicate driver numbers")
        return self

    def check(self, request: PredictionRequestV2) -> None:
        """选项编号和题目正文均须与请求时的快照一致。"""
        option_ids = [option.option_id for option in request.question.options]
        if (
            request.question_snapshot_id != self.question_snapshot_id
            or request.question_id != self.question_id
            or request.question.question_text != self.question_text
            or request.race_context.meeting_key != self.meeting_key
            or request.race_context.session_key not in (*self.session_keys, None)
            or request.question.choice_limit not in (None, 1)
            or len(option_ids) != len(set(option_ids))
            or set(option_ids) != set(self.option_drivers)
        ):
            raise UnsupportedQuestion("registered snapshot does not match request")
        if (
            request.model_version != self.model_version
            or request.prompt_version != self.prompt_version
            or request.feature_version != self.feature_version
        ):
            raise VersionUnavailable("requested model, prompt or feature version unavailable")


class BothAdvanceQ1Policy(BaseModel):
    """冻结两名目标车手均从 Q1 晋级 Q2 的策略配置。"""

    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)

    kind: Literal["BOTH_ADVANCE_Q1"]
    question_snapshot_id: int = Field(gt=0)
    question_id: int = Field(gt=0)
    question_text: str = Field(min_length=1)
    meeting_key: int = Field(gt=0)
    year: int = Field(gt=0)
    round_id: int = Field(gt=0)
    session_keys: tuple[int, ...] = Field(min_length=1, max_length=3)
    qualifying_session_key: int | None = Field(default=None, gt=0)
    qualifying_start_at: datetime
    yes_option_id: int = Field(gt=0)
    no_option_id: int = Field(gt=0)
    yes_option_text: str = Field(min_length=1)
    no_option_text: str = Field(min_length=1)
    target_drivers: tuple[int, int]
    participant_drivers: tuple[int, ...] = Field(min_length=3, max_length=MAX_DRIVER_NUMBERS)
    q1_advancement_slots: int = Field(ge=2)
    minimum_laps: int = Field(default=3, ge=1, le=100)
    model_version: str = Field(min_length=1)
    prompt_version: str = Field(min_length=1)
    feature_version: str = Field(min_length=1)

    @model_validator(mode="after")
    def validate_configuration(self) -> BothAdvanceQ1Policy:
        """配置须自洽，确保在接收请求或声明结果前拒绝无效策略。"""
        if not _unique_positive(self.session_keys):
            raise ValueError("session keys must be unique positive integers")
        if not _unique_positive(self.participant_drivers):
            raise ValueError("participant drivers must be unique positive integers")
        if not _unique_positive(self.target_drivers):
            raise ValueError("target drivers must be unique positive integers")
        if any(driver not in self.participant_drivers for driver in self.target_drivers):
            raise ValueError("target drivers must be participants")
        if self.qualifying_session_key in self.session_keys:
            raise ValueError("qualifying session key must not be a practice session")
        if self.qualifying_start_at.tzinfo is None or self.qualifying_start_at.utcoffset() is None:
            raise ValueError("qualifying start time must be timezone-aware")
        if self.yes_option_id == self.no_option_id:
            raise ValueError("yes and no option IDs must differ")
        if self.yes_option_text == self.no_option_text:
            raise ValueError("yes and no option texts must differ")
        if self.q1_advancement_slots >= len(self.participant_drivers):
            raise ValueError("Q1 advancement slots must be fewer than participants")
        if self.minimum_laps * len(self.participant_drivers) > 500:
            raise ValueError("minimum lap coverage exceeds supported total")
        for version in (self.model_version, self.prompt_version, self.feature_version):
            if not version.strip():
                raise ValueError("versions must not be empty")
        return self

    def check(self, request: PredictionRequestV2) -> None:
        """校验题目、赛事、截止时间和选项均与冻结策略一致。"""
        options = {option.option_id: option.option_text for option in request.question.options}
        if (
            request.question_snapshot_id != self.question_snapshot_id
            or request.question_id != self.question_id
            or request.question.question_text != self.question_text
            or request.race_context.meeting_key != self.meeting_key
            or request.race_context.year != self.year
            or request.race_context.round_id != self.round_id
            or request.race_context.session_key not in (*self.session_keys, self.qualifying_session_key)
            or request.question.choice_limit not in (None, 1)
            or options.get(self.yes_option_id) != self.yes_option_text
            or options.get(self.no_option_id) != self.no_option_text
            or len(request.question.options) != 2
            or set(options) != {self.yes_option_id, self.no_option_id}
            or request.data_cutoff >= self.qualifying_start_at
        ):
            raise UnsupportedQuestion("registered Q1 snapshot does not match request")
        if (
            request.model_version != self.model_version
            or request.prompt_version != self.prompt_version
            or request.feature_version != self.feature_version
        ):
            raise VersionUnavailable("requested model, prompt or feature version unavailable")


def _unique_positive(values: tuple[int, ...]) -> bool:
    return all(isinstance(value, int) and not isinstance(value, bool) and value > 0 for value in values) and len(set(values)) == len(values)


def load_policy(path: str) -> SnapshotPolicy | BothAdvanceQ1Policy | None:
    """未登记题目时保持关闭；旧配置和显式策略类型分别解析。"""
    if not path:
        return None
    raw = Path(path).read_text(encoding="utf-8")
    payload = json.loads(raw)
    if not isinstance(payload, dict):
        raise TypeError("policy configuration must be an object")
    kind = payload.get("kind")
    if kind is None:
        return SnapshotPolicy.model_validate(payload)
    if kind == "BOTH_ADVANCE_Q1":
        return BothAdvanceQ1Policy.model_validate_json(raw)
    raise ValueError(f"unknown policy kind: {kind}")
