"""v2 请求契约保留合法 NULL，同时不接受未知字段与版本。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from f1_predict.messaging.dto.request_v2 import PredictionRequestV2


def test_java_producer_fixture_is_valid_v2() -> None:
    fixture = (
        Path(__file__).resolve().parents[4]
        / "f1aipredict/src/test/resources/prediction_request_v2.json"
    )
    payload = json.loads(fixture.read_text(encoding="utf-8"))
    request = PredictionRequestV2.model_validate(payload)
    assert request.model_dump(mode="json", by_alias=True) == payload


def test_nullable_snapshot_fields_roundtrip(request_v2_payload: dict[str, object]) -> None:
    request = PredictionRequestV2.model_validate_json(json.dumps(request_v2_payload))
    dumped = json.loads(request.model_dump_json())
    assert dumped == request_v2_payload
    assert dumped["question"]["options"][0]["optionId"] is None
    assert dumped["raceContext"]["sessionKey"] is None
    assert dumped["dataCutoff"].endswith("Z")
    assert "rawJson" not in request.model_dump_json()


@pytest.mark.parametrize("bad_value", ["1", "3", None])
def test_unsupported_version_rejected(
    request_v2_payload: dict[str, object], bad_value: object
) -> None:
    request_v2_payload["schemaVersion"] = bad_value
    with pytest.raises(ValidationError):
        PredictionRequestV2.model_validate(request_v2_payload)


def test_missing_required_and_unknown_fields_rejected(
    request_v2_payload: dict[str, object],
) -> None:
    del request_v2_payload["questionSnapshotId"]
    with pytest.raises(ValidationError):
        PredictionRequestV2.model_validate(request_v2_payload)
    request_v2_payload["questionSnapshotId"] = 11
    request_v2_payload["rawJson"] = {"secret": "not allowed"}
    with pytest.raises(ValidationError):
        PredictionRequestV2.model_validate(request_v2_payload)
