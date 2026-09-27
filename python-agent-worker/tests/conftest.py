"""跨消息契约、幂等暂存与消费测试共用的 v2 请求。"""

from __future__ import annotations

import pytest


@pytest.fixture
def request_v2_payload() -> dict[str, object]:
    return {
        "schemaVersion": "2",
        "messageId": "msg-1",
        "predictionJobId": "job-1",
        "batchId": 3,
        "questionId": 7,
        "questionSnapshotId": 11,
        "traceId": "trace-1",
        "question": {
            "questionText": "Who will win?",
            "subText": None,
            "questionType": "UNKNOWN",
            "optionTemplateId": None,
            "choiceLimit": None,
            "options": [
                {
                    "optionId": None,
                    "optionNo": 0,
                    "optionText": None,
                    "points": None,
                    "chance": None,
                }
            ],
        },
        "raceContext": {
            "seasonId": 1,
            "year": 2026,
            "roundId": 2,
            "roundNumber": 1,
            "meetingKey": None,
            "sessionKey": None,
            "gamedayId": 24,
            "trackName": "Sample Circuit",
        },
        "dataCutoff": "2026-09-27T08:00:00Z",
        "modelVersion": "model-v1",
        "promptVersion": "prompt-v1",
        "featureVersion": "feature-v1",
        "embeddingVersion": None,
        "retrieverVersion": None,
    }
