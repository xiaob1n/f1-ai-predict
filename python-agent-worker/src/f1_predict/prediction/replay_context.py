"""已核验清单与冻结请求之间的受控历史工程回放上下文。"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from f1_predict.messaging.dto.request_v2 import PredictionRequestV2
from f1_predict.prediction.policy import (
    SnapshotPolicy,
    UnsupportedQuestion,
    VersionUnavailable,
)
from f1_predict.replay.export import SOURCE_ALIAS, _normalize_lap
from f1_predict.replay.manifest import (
    CLEAN_RULE_VERSION,
    MANIFEST_MODE,
    canonical_sha256,
    manifest_sha256,
    parse_utc,
    validate_manifest,
)


@dataclass(frozen=True, slots=True)
class ReplayContext:
    """只从部署指定的本地清单构造；每次处理前再核验包未被替换。"""

    manifest_path: Path
    fixture_path: Path
    policy_path: Path
    manifest_hash: str
    sporting_cutoff: datetime
    import_completed_at: datetime
    clean_rule_version: str
    isolated_question_id: int
    isolated_snapshot_id: int

    @classmethod
    def load(
        cls, manifest_path: str, fixture_path: str, policy_path: str, *, model_mode: str,
        expected_manifest_hash: str,
    ) -> ReplayContext:
        """校验固定包内四个文件及策略与 fixture，不接受路径逃逸。"""
        manifest_file = Path(manifest_path).resolve(strict=True)
        fixture_file = Path(fixture_path).resolve(strict=True)
        policy_file = Path(policy_path).resolve(strict=True)
        if fixture_file.parent != manifest_file.parent or policy_file.parent != manifest_file.parent:
            raise ValueError("replay files must reside in one dedicated bundle directory")
        manifest = _read_json(manifest_file)
        validate_manifest(manifest)
        if model_mode not in ("stub", "real") or manifest["modelMode"] != model_mode:
            raise ValueError("replay model mode differs from deployment")
        if manifest_sha256(manifest) != expected_manifest_hash:
            raise ValueError("replay manifest differs from approved deployment hash")
        identity = manifest["identity"]
        mapping = identity["isolatedIdMapping"]
        rules = manifest["rules"]
        if not isinstance(mapping, dict) or not isinstance(rules, dict):
            raise TypeError("replay identity or cleaning rules are invalid")
        try:
            context = cls(
                manifest_path=manifest_file,
                fixture_path=fixture_file,
                policy_path=policy_file,
                manifest_hash=manifest_sha256(manifest),
                sporting_cutoff=parse_utc(manifest["time"]["sportingCutoff"], field="sportingCutoff"),
                import_completed_at=parse_utc(manifest["time"]["importCompletedAt"], field="importCompletedAt"),
                clean_rule_version=str(rules["version"]),
                isolated_question_id=int(mapping["questionId"]),
                isolated_snapshot_id=int(mapping["snapshotId"]),
            )
        except (KeyError, TypeError) as error:
            raise ValueError("replay metadata is incomplete") from error
        if context.clean_rule_version != CLEAN_RULE_VERSION or min(context.isolated_question_id, context.isolated_snapshot_id) <= 0:
            raise ValueError("replay cleaning rule version is unsupported or metadata is incomplete")
        context._check_bundle(manifest)
        return context

    def _check_bundle(self, manifest: dict[str, Any]) -> None:
        """原始与身份 sidecar 同样必须位于包中，不允许只校验运行用 fixture。"""
        if manifest_sha256(manifest) != self.manifest_hash:
            raise ValueError("replay manifest changed")
        files = {
            "rawRows": self.manifest_path.parent / "raw_rows.json",
            "fixture": self.fixture_path,
            "policy": self.policy_path,
            "identityMetadata": self.manifest_path.parent / "identity_metadata.json",
        }
        for name, path in files.items():
            if path.resolve(strict=True).parent != self.manifest_path.parent:
                raise ValueError("replay file escapes bundle directory")
            if canonical_sha256(_read_json(path)) != manifest["fileHashes"][name]:
                raise ValueError("replay bundle file hash mismatch")
        rows = _read_json(self.fixture_path)
        raw_rows = _read_json(files["rawRows"])
        policy = _read_json(self.policy_path)
        if not isinstance(rows, list) or len(rows) != manifest["counts"]["exported"]:
            raise ValueError("replay fixture count mismatch")
        if not isinstance(raw_rows, list) or len(raw_rows) != manifest["counts"]["raw"] - manifest["counts"]["duplicateMerged"]:
            raise ValueError("replay raw row count mismatch")
        raw_by_id = {str(row["_key"]): row for row in raw_rows}
        raw_hashes = {key: canonical_sha256(row) for key, row in raw_by_id.items()}
        if len(raw_hashes) != len(raw_rows):
            raise ValueError("replay source record IDs must be unique")
        sessions = set(policy["session_keys"])
        drivers = set(policy["option_drivers"].values())
        meeting = policy["meeting_key"]
        identity = manifest["identity"]
        source_sessions = {item["sessionKey"] for item in identity["sessions"]}
        source_drivers = {item["driverNumber"] for item in identity["drivers"]}
        if (policy["question_id"] != self.isolated_question_id
                or policy["question_snapshot_id"] != self.isolated_snapshot_id
                or meeting != identity["meetingIdentity"]["mysqlMeetingKey"]
                or sessions != source_sessions or drivers != source_drivers
                or {str(identity["isolatedIdMapping"]["optionIds"][source_id]): driver
                    for source_id, driver in identity["optionDrivers"].items()}
                != {str(key): value for key, value in policy["option_drivers"].items()}):
            raise ValueError("replay policy differs from verified identity")
        for row in rows:
            raw = raw_by_id.get(row["recordId"])
            if (raw is None or row["sourceContentHash"] != raw_hashes.get(row["recordId"])
                    or raw["meeting_key"] != identity["meetingIdentity"]["mongoMeetingKey"]
                    or raw["session_key"] != row["sessionKey"]
                    or raw["driver_number"] != row["driverNumber"]):
                raise ValueError("replay fixture source identity or hash differs from raw record")
            if row["meetingKey"] != meeting or row["sessionKey"] not in sessions or row["driverNumber"] not in drivers:
                raise ValueError("replay fixture identity differs from policy")
            seen = parse_utc(row["firstSeenAt"], field="firstSeenAt")
            if row["sourceEndpoint"] != SOURCE_ALIAS:
                raise ValueError("replay source alias is unsupported")
            expected = _normalize_lap(raw, meeting_key=meeting, observed=seen, source_alias=SOURCE_ALIAS)
            if row != expected or expected["isClean"] is not True:
                raise ValueError("replay fixture differs from raw lap derivation")
            exact_event = parse_utc(raw["date_start"], field="date_start")
            if (exact_event > self.sporting_cutoff
                    or exact_event + timedelta(seconds=raw["lap_duration"]) > self.sporting_cutoff):
                raise ValueError("replay fixture exceeds sporting cutoff")
            if seen < parse_utc(manifest["time"]["importStartedAt"], field="importStartedAt") or seen > self.import_completed_at:
                raise ValueError("replay observation time differs from import interval")
        if canonical_sha256(_read_json(self.manifest_path.parent / "identity_metadata.json")) != manifest["hashes"]["identityMetadata"]:
            raise ValueError("replay identity metadata mismatch")

    def verify(self, request: PredictionRequestV2, policy: SnapshotPolicy) -> None:
        """模式和身份绑定在模型调用之前核验，重试也不得换包。"""
        try:
            manifest = _read_json(self.manifest_path)
            validate_manifest(manifest)
            self._check_bundle(manifest)
        except (OSError, ValueError, KeyError, TypeError, AttributeError, IndexError) as error:
            raise VersionUnavailable("replay bundle verification failed") from error
        if request.question_id != self.isolated_question_id or request.question_snapshot_id != self.isolated_snapshot_id:
            raise UnsupportedQuestion("replay request identity differs from bundle")
        if request.data_cutoff < self.import_completed_at or request.data_cutoff > datetime.now(UTC):
            raise VersionUnavailable("replay data cutoff is not an observed import time")
        identity = manifest["identity"]
        if canonical_sha256(request.question.question_text) != identity["questionTextHash"]:
            raise UnsupportedQuestion("replay question text differs from source")
        expected_options = {
            str(identity["isolatedIdMapping"]["optionIds"][source_id]): text_hash
            for source_id, text_hash in identity["optionTextHashes"].items()
        }
        if {str(item.option_id): canonical_sha256(item.option_text) for item in request.question.options} != expected_options:
            raise UnsupportedQuestion("replay options differ from source")
        if canonical_sha256(policy.model_dump(mode="json")) != manifest["fileHashes"]["policy"]:
            raise UnsupportedQuestion("runtime replay policy differs from verified bundle")
        if request.prompt_version != "prompt-h2h-replay-v1" or request.feature_version != "feature-v1":
            raise VersionUnavailable("replay version matrix mismatch")

    def frozen_fields(self) -> dict[str, str]:
        """这些受控元数据写入 SQLite，不从请求摘要读取。"""
        return {
            "executionMode": MANIFEST_MODE,
            "manifestHash": self.manifest_hash,
            "sportingCutoff": self.sporting_cutoff.isoformat().replace("+00:00", "Z"),
            "importCompletedAt": self.import_completed_at.isoformat().replace("+00:00", "Z"),
            "sourceFirstSeenStatus": "UNKNOWN",
            "cleanRuleVersion": self.clean_rule_version,
        }

    def summary(self, model_summary: str) -> str:
        """展示标识仅供人阅读，不作为授权或模式的判据。"""
        prefix = f"HISTORICAL_ENGINEERING_REPLAY[{self.manifest_hash[:16]}] 代理清洗/未校准："
        return prefix + model_summary[: 2048 - len(prefix)]


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))
