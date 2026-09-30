"""本地合成 Feed 与模型协议服务；所有数据均非真实赛事或真实模型输出。"""

from __future__ import annotations

import hashlib
import json
import time
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 18781
MAX_MODEL_BODY_BYTES = 32768
MODEL_BODY_READ_TIMEOUT_SECONDS = 2
MODEL_BODY_TOTAL_TIMEOUT_SECONDS = 2
MODEL_BODY_READ_CHUNK_BYTES = 4096
TEST_DAY = datetime.now(UTC).date() + timedelta(days=7)
GAMEDAY = int(TEST_DAY.strftime("%Y%m%d"))
MEETING = GAMEDAY + 200000000
PRACTICE_SESSION = GAMEDAY + 100000000
QUESTION_TEXT = "Synthetic: which driver records the faster qualifying lap?"
UNSUPPORTED_TEXT = "Synthetic: will the safety car appear?"
CUTOFF = f"{TEST_DAY.isoformat()}T12:00:00.000Z"
MODEL_VERSION = "e2e-local-model-v1"
PROMPT_VERSION = "prompt-v1"
FEATURE_VERSION = "feature-v1"
OPTIONS = ((91001, "Driver 11"), (91002, "Driver 22"))


def feed_payloads() -> dict[str, object]:
    """保持与现有 Feed JSON 模型一致，题目状态值由 Java 正式映射，不篡改数据库。"""
    def session(race_id: int, gameday: int, session_key: int, name: str, start: str) -> dict:
        end = (datetime.fromisoformat(start) + timedelta(hours=1)).isoformat()
        return {
            "RaceId": race_id, "GamedayId": gameday, "Season": start[:4],
            "FOMMEETINGSESSIONKEY": str(session_key), "SessionName": name,
            "SessionType": name, "SessionStartDateISO8601": start,
            "SessionEndDateISO8601": end, "MeetingId": MEETING,
            "MeetingNumber": TEST_DAY.timetuple().tm_yday, "MeetingName": "Synthetic Grand Prix",
            "MeetingOfficialName": "SYNTHETIC TEST EVENT", "MeetingLocation": "Test City",
            "CountryName": "Test Country", "CircuitOfficialName": "Synthetic Circuit",
            "IsBonusRound": 0,
        }

    def question(source_id: int, number: int, text: str, options: tuple) -> dict:
        return {
            "Id": source_id, "No": number, "Text": text,
            "SubText": f"Isolated test gameday {GAMEDAY}",
            "OptionTemplateId": 2, "Status": 4, "Config": {"ChoiceLimit": 1},
            "Options": [
                {"Id": option_id, "Value": label, "Points": "1", "Chance": "50"}
                for option_id, label in options
            ], "Answer": [],
        }

    schedule = {
        "Data": {"Value": [
            session(912001, GAMEDAY, PRACTICE_SESSION, "Practice 3",
                    f"{TEST_DAY.isoformat()}T09:00:00+00:00"),
            session(912002, GAMEDAY + 1, PRACTICE_SESSION + 1, "Qualifying",
                    f"{(TEST_DAY + timedelta(days=1)).isoformat()}T16:00:00+00:00"),
        ]}
    }
    return {
        "/feeds/schedule/raceday_en.json": schedule,
        "/feeds/limits/constraints.json": {
            "Data": {"Value": {"GamedayId": GAMEDAY, "MatchdayId": 1, "PhaseId": 1}},
            "Meta": {"Success": True, "StatusCode": 200},
        },
        f"/feeds/questions/questions_{GAMEDAY}_en.json": {
            "Data": {"Value": {"Questions": [
                question(91101, 1, QUESTION_TEXT, OPTIONS),
                question(91102, 2, UNSUPPORTED_TEXT, ((92001, "Yes"), (92002, "No"))),
            ]}}
        },
    }


def audited_laps() -> list[dict]:
    """合成截止前与截止后圈速，用模型实际输入验证 as-of 过滤。"""
    rows = []
    for driver, median in ((11, 91.1), (22, 91.8)):
        for lap in range(1, 4):
            event = datetime.fromisoformat(f"{TEST_DAY.isoformat()}T10:{lap * 3:02d}:00+00:00")
            duration = median + lap / 10
            lap_end = event + timedelta(seconds=duration)
            first_seen = lap_end + timedelta(seconds=2)
            record = f"synthetic-{driver}-{lap}"
            rows.append({
                "recordId": record, "meetingKey": MEETING,
                "sessionKey": PRACTICE_SESSION, "driverNumber": driver,
                "eventTime": event.isoformat(), "firstSeenAt": first_seen.isoformat(),
                "lapEnd": lap_end.isoformat(), "durationSeconds": duration,
                "isClean": True, "sourceEndpoint": "synthetic://e2e/audited-laps",
                "sourceContentHash": hashlib.sha256(record.encode()).hexdigest(),
            })
    # 异常短圈速若错误进入模型输入，会改变圈数和中位数，不能被常规样例掩盖。
    event = datetime.fromisoformat(f"{TEST_DAY.isoformat()}T11:59:00+00:00")
    lap_end = event + timedelta(seconds=60)
    record = "synthetic-post-cutoff-driver-11"
    rows.append({
        "recordId": record, "meetingKey": MEETING,
        "sessionKey": PRACTICE_SESSION, "driverNumber": 11,
        "eventTime": event.isoformat(), "firstSeenAt": (lap_end + timedelta(seconds=2)).isoformat(),
        "lapEnd": lap_end.isoformat(), "durationSeconds": 60.0,
        "isClean": True, "sourceEndpoint": "synthetic://e2e/post-cutoff-lap",
        "sourceContentHash": hashlib.sha256(record.encode()).hexdigest(),
    })
    return rows


class Handler(BaseHTTPRequestHandler):
    """只响应明确的本地 Feed 路径及模型协议，不进行代理或重定向。"""

    def log_message(self, format: str, *args: object) -> None:
        return

    def send_json(self, status: int, body: object, *, close: bool = False) -> None:
        encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
        if close:
            self.close_connection = True
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        if close:
            self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json(200, {"status": "isolated-stub"})
        elif self.path in feed_payloads():
            self.send_json(200, feed_payloads()[self.path])
        else:
            self.send_json(404, {"error": "unknown fixture path"})

    def do_POST(self) -> None:
        if self.path != "/model":
            self.send_json(404, {"error": "unknown endpoint"}, close=True)
            return
        try:
            raw_length = self.headers.get("Content-Length")
            if (not isinstance(raw_length, str) or not raw_length.isascii()
                    or not raw_length.isdecimal()):
                raise ValueError("invalid content length")
            content_length = int(raw_length)
            if content_length <= 0:
                raise ValueError("invalid content length")
        except (TypeError, ValueError):
            self.send_json(400, {"error": "invalid local model input"}, close=True)
            return
        if content_length > MAX_MODEL_BODY_BYTES:
            self.send_json(413, {"error": "invalid local model input"}, close=True)
            return

        deadline = time.monotonic() + MODEL_BODY_TOTAL_TIMEOUT_SECONDS
        previous_timeout = self.connection.gettimeout()
        body = bytearray()
        try:
            while len(body) < content_length:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("request body deadline exceeded")
                self.connection.settimeout(min(MODEL_BODY_READ_TIMEOUT_SECONDS, remaining))
                chunk = self.rfile.read1(
                    min(MODEL_BODY_READ_CHUNK_BYTES, content_length - len(body))
                )
                if not chunk:
                    raise ValueError("truncated request body")
                body.extend(chunk)
                if time.monotonic() >= deadline:
                    raise TimeoutError("request body deadline exceeded")
            request = json.loads(body.decode("utf-8"))
            messages = request["messages"]
            if (request["model"] != MODEL_VERSION or not isinstance(messages, list)
                    or len(messages) != 2 or messages[1]["role"] != "user"):
                raise ValueError("unexpected model envelope")
            context = json.loads(messages[1]["content"])
            options = context["options"]
            drivers = context["features"]["drivers"]
            expected_drivers = [
                {"driverNumber": 11, "cleanLapCount": 3, "medianLapSeconds": 91.3},
                {"driverNumber": 22, "cleanLapCount": 3, "medianLapSeconds": 92.0},
            ]
            if (context["question"] != QUESTION_TEXT
                    or not isinstance(options, list)
                    or [item["optionId"] for item in options] != [item[0] for item in OPTIONS]
                    or not isinstance(drivers, list) or len(drivers) != len(expected_drivers)):
                raise ValueError("unexpected model context")
            for actual, expected in zip(drivers, expected_drivers, strict=True):
                if (actual["driverNumber"] != expected["driverNumber"]
                        or actual["cleanLapCount"] != expected["cleanLapCount"]
                        or abs(actual["medianLapSeconds"] - expected["medianLapSeconds"]) > 0.001):
                    raise ValueError("cutoff lap leaked into model features")
        except (ValueError, KeyError, IndexError, TypeError, OSError):
            self.send_json(400, {"error": "invalid local model input"}, close=True)
            return
        finally:
            self.connection.settimeout(previous_timeout)
        candidate = {"option_ids": [OPTIONS[0][0]], "confidence": 0.72,
                     "reasoning_summary": "Synthetic deterministic model fixture; not a calibrated probability."}
        self.send_json(200, {"choices": [{"message": {"content": json.dumps(candidate)}}]})


def serve() -> None:
    with ThreadingHTTPServer((HOST, PORT), Handler) as server:
        server.serve_forever()


if __name__ == "__main__":
    serve()
