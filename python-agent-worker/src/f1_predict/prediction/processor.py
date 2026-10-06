"""按租约处理已 ACK 的请求，终态与待发事件同事务落盘。"""

from __future__ import annotations

import asyncio
import hashlib
import json
import uuid
from datetime import UTC, datetime

from pydantic import ValidationError

from f1_predict.features.builder import FeatureQuery, build_features
from f1_predict.features.repository import LapRepository
from f1_predict.messaging.dto.failure_v2 import (
    PredictionFailureCode,
    PredictionFailureV2,
)
from f1_predict.messaging.dto.request_v2 import PredictionRequestV2
from f1_predict.messaging.dto.result_v2 import (
    EvidenceSourceV2,
    PredictionResultV2,
    SelectedOptionV2,
)
from f1_predict.prediction.model import ModelCandidate, ModelGateway
from f1_predict.prediction.model_calls import (
    CallBudgetExceeded,
    CallIdentityConflict,
    CallNotPermitted,
)
from f1_predict.prediction.model_http import InvalidModelOutput, ModelUnavailable
from f1_predict.prediction.model_vendor_api import (
    DeepSeekModel,
    ModelCallRejected,
    ModelCallUncertain,
    ModelInvocation,
    RetryableModelFailure,
)
from f1_predict.prediction.policy import (
    BothAdvanceQ1Policy,
    SnapshotPolicy,
    UnsupportedQuestion,
    VersionUnavailable,
)
from f1_predict.prediction.replay_context import ReplayContext
from f1_predict.reliability.idempotency import InboxClaim, InboxStore


def _message_id(job_id: str, kind: str) -> str:
    """同一任务在进程重启后仍保持相同的消息标识。"""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"f1-prediction-v2:{job_id}:{kind}"))


class PredictionProcessor:
    """模型只建议选项；时点、来源、版本和业务身份由受控代码填写。"""

    def __init__(
        self,
        store: InboxStore,
        repository: LapRepository,
        model: ModelGateway,
        policy: SnapshotPolicy | BothAdvanceQ1Policy | None,
        *,
        max_attempts: int = 3,
        lease_seconds: int = 90,
        replay_context: ReplayContext | None = None,
    ) -> None:
        self.store = store
        self.repository = repository
        self.model = model
        self.policy = policy
        self.max_attempts = max_attempts
        self.lease_seconds = lease_seconds
        self.replay_context = replay_context

    async def process_once(self) -> bool:
        """领取一条到期任务，网络调用始终在 SQLite 事务外。"""
        claim = await asyncio.to_thread(self.store.claim_next, lease_seconds=self.lease_seconds)
        if claim is None:
            return False
        request = PredictionRequestV2.model_validate_json(claim.payload_json)
        try:
            if self.policy is None:
                raise UnsupportedQuestion("snapshot is not registered")
            self.policy.check(request)
            if self.replay_context is not None:
                self.replay_context.verify(request, self.policy)
            policy_hash = hashlib.sha256(
                self.policy.model_dump_json().encode("utf-8")
            ).hexdigest()
            if claim.feature_snapshot_json:
                features = json.loads(claim.feature_snapshot_json)
            else:
                snapshot = await asyncio.to_thread(
                    build_features,
                    FeatureQuery(
                        meeting_key=self.policy.meeting_key,
                        session_keys=self.policy.session_keys,
                        driver_numbers=(
                            self.policy.participant_drivers
                            if isinstance(self.policy, BothAdvanceQ1Policy)
                            else tuple(self.policy.option_drivers.values())
                        ),
                        data_cutoff=request.data_cutoff,
                        feature_version=request.feature_version,
                        prediction_job_id=request.prediction_job_id,
                        min_clean_laps_per_driver=self.policy.minimum_laps,
                    ),
                    self.repository,
                )
                features = json.loads(snapshot.frozen_json)
                features["policyHash"] = policy_hash
                if self.replay_context is not None:
                    features.update(self.replay_context.frozen_fields())
                await asyncio.to_thread(
                    self.store.save_feature_snapshot, request.prediction_job_id, claim.lease_token, features
                )
            if (
                features["predictionJobId"] != request.prediction_job_id
                or features.get("policyHash") != policy_hash
                or features["featureVersion"] != request.feature_version
                or features["dataCutoff"] != request.data_cutoff.isoformat().replace("+00:00", "Z")
                or (
                    self.replay_context is not None
                    and any(
                        features.get(key) != value
                        for key, value in self.replay_context.frozen_fields().items()
                    )
                )
                or (self.replay_context is None and "executionMode" in features)
            ):
                raise VersionUnavailable("frozen feature identity differs from request")
            if features["status"] != "READY":
                await self._fail(claim, request, PredictionFailureCode.INSUFFICIENT_DATA)
                return True
            context_data: dict[str, object] = {
                "question": request.question.question_text[:1000],
                "options": [
                    {"optionId": option.option_id, "optionText": (option.option_text or "")[:200]}
                    for option in request.question.options
                ],
                "features": {"drivers": features["drivers"]},
            }
            if self.replay_context is not None:
                context_data["predictionTask"] = {
                    "kind": "HISTORICAL_ENGINEERING_REPLAY",
                    "optionDrivers": self.policy.option_drivers,
                    "instruction": "仅比较已登记练习圈速，返回登记选项之一；分数未经校准。",
                }
            if isinstance(self.policy, BothAdvanceQ1Policy):
                # 题目语义和晋级门槛来自人工登记策略，而非模型或选项文字推断。
                context_data["predictionTask"] = {
                    "kind": "BOTH_ADVANCE_Q1",
                    "targetDriverNumbers": self.policy.target_drivers,
                    "participantDriverNumbers": self.policy.participant_drivers,
                    "q1AdvancementSlots": self.policy.q1_advancement_slots,
                    "yesOptionId": self.policy.yes_option_id,
                    "noOptionId": self.policy.no_option_id,
                    "instruction": "赛前预测两名目标车手是否都能从 Q1 晋级 Q2；仅返回登记的是或否选项。",
                }
            context = json.dumps(context_data, ensure_ascii=False)
            allowed_options = (
                {self.policy.yes_option_id, self.policy.no_option_id}
                if isinstance(self.policy, BothAdvanceQ1Policy)
                else set(self.policy.option_drivers)
            )
            try:
                if isinstance(self.model, DeepSeekModel):
                    if self.model.config.model != request.model_version:
                        raise VersionUnavailable("vendor model version differs from request")
                    allowed_option_ids = tuple(sorted(allowed_options))
                    gateway = self.model.bind(ModelInvocation(
                        prediction_job_id=request.prediction_job_id,
                        attempt=claim.attempt,
                        lease_token=claim.lease_token,
                        allowed_option_ids=allowed_option_ids,
                        data_cutoff=request.data_cutoff,
                        model_version=request.model_version,
                        prompt_version=request.prompt_version,
                        feature_version=request.feature_version,
                    ))
                else:
                    gateway = self.model
                candidate = await gateway.predict(context)
            except (TimeoutError, OSError, ConnectionError) as error:
                if isinstance(self.model, DeepSeekModel):
                    # DeepSeek 传输已将不确定收费状态映射为专用异常；裸 I/O 错误可能来自账本，不能重试。
                    raise
                raise ModelUnavailable("model service unavailable") from error
            candidate = ModelCandidate.model_validate(candidate)
            option_id = candidate.option_ids[0]
            if option_id not in allowed_options or option_id not in {
                item.option_id for item in request.question.options
            }:
                raise InvalidModelOutput("selected option is not in frozen snapshot")
            evidence = [
                EvidenceSourceV2(
                    sourceType="OpenF1",
                    sourceName=item["sourceEndpoint"],
                    sourceUrl=None,
                    firstSeenAt=item["firstSeenAt"],
                    eventTime=item["eventTime"],
                    documentId=item["recordId"],
                    chunkId=None,
                )
                for item in features["sourceEvidence"][:100]
            ]
            result = PredictionResultV2.from_request(
                request,
                message_id=_message_id(request.prediction_job_id, "result"),
                selected_options=[SelectedOptionV2(optionId=option_id, position=1)],
                confidence=candidate.confidence,
                evidence=evidence,
                generated_at=datetime.now(UTC),
                reasoning_summary=(
                    self.replay_context.summary(candidate.reasoning_summary)
                    if self.replay_context is not None
                    else candidate.reasoning_summary
                ),
            )
            await asyncio.to_thread(
                self.store.finish,
                request.prediction_job_id,
                claim.lease_token,
                terminal_status="SUCCEEDED",
                result=result.model_dump(mode="json", by_alias=True),
            )
        except UnsupportedQuestion:
            await self._fail(claim, request, PredictionFailureCode.UNSUPPORTED_QUESTION)
        except (VersionUnavailable, CallIdentityConflict):
            await self._fail(claim, request, PredictionFailureCode.VERSION_UNAVAILABLE)
        except (ModelCallUncertain, ModelCallRejected, CallBudgetExceeded, CallNotPermitted):
            await self._fail(claim, request, PredictionFailureCode.MODEL_FAILED)
        except RetryableModelFailure:
            await self._retry(claim, request)
        except (InvalidModelOutput, ValidationError, KeyError, IndexError, TypeError):
            await self._fail(claim, request, PredictionFailureCode.INVALID_MODEL_OUTPUT)
        except (ModelUnavailable, TimeoutError, OSError):
            if isinstance(self.model, DeepSeekModel):
                # DeepSeek 裸 I/O 异常不等同于供应商明确可重试响应。
                raise
            await self._retry(claim, request)
        return True

    async def _fail(
        self, claim: InboxClaim, request: PredictionRequestV2, code: PredictionFailureCode
    ) -> None:
        """仅发布枚举安全摘要，不把异常、数据或服务地址写入消息。"""
        failure = PredictionFailureV2.from_request(
            request,
            message_id=_message_id(request.prediction_job_id, "failure"),
            failure_code=code,
            summary=code.value,
            attempt=claim.attempt,
            generated_at=datetime.now(UTC),
        )
        await asyncio.to_thread(
            self.store.finish,
            request.prediction_job_id,
            claim.lease_token,
            terminal_status="FAILED",
            result=failure.model_dump(mode="json", by_alias=True),
        )

    async def _retry(self, claim: InboxClaim, request: PredictionRequestV2) -> None:
        """暂态故障按有限次数退避，最终也写可靠失败事件。"""
        failure = PredictionFailureV2.from_request(
            request,
            message_id=_message_id(request.prediction_job_id, "failure"),
            failure_code=PredictionFailureCode.MODEL_FAILED,
            summary="MODEL_FAILED",
            attempt=claim.attempt,
            generated_at=datetime.now(UTC),
        )
        if claim.attempt >= self.max_attempts:
            await asyncio.to_thread(
                self.store.finish,
                request.prediction_job_id,
                claim.lease_token,
                terminal_status="FAILED",
                result=failure.model_dump(mode="json", by_alias=True),
            )
        else:
            await asyncio.to_thread(
                self.store.retry,
                request.prediction_job_id,
                claim.lease_token,
                max_attempts=self.max_attempts,
                base_delay_seconds=2,
                max_delay_seconds=60,
            )
