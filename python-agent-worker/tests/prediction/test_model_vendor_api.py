"""DeepSeek 供应商适配器的离线公开接口契约。"""

from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime
from pathlib import Path

import pytest

from f1_predict.prediction.model import ModelCandidate
from f1_predict.prediction.model_calls import CallNotPermitted, ModelCallLedger
from f1_predict.prediction.model_vendor_api import (
    DeepSeekConfig,
    DeepSeekModel,
    DeepSeekTransportError,
    ModelCallRejected,
    ModelCallUncertain,
    ModelInvocation,
    OpenAISDKDeepSeekTransport,
    RetryableModelFailure,
    create_bounded_httpx_transport,
    create_openai_sdk_transport,
)

INVOCATION = ModelInvocation(
    prediction_job_id="job-1",
    attempt=1,
    lease_token="lease-a",
    allowed_option_ids=(10, 20),
    data_cutoff=datetime(2026, 9, 1, tzinfo=UTC),
    model_version="deepseek-flash",
    prompt_version="prompt-v1",
    feature_version="feature-v1",
)


def context() -> str:
    return json.dumps({
        "question": "更快的车手？",
        "options": [
            {"optionId": 10, "optionText": "车手 11"},
            {"optionId": 20, "optionText": "车手 22"},
        ],
        "features": {"drivers": [
            {"driverNumber": 11, "cleanLapCount": 3, "medianLapSeconds": 90.0},
            {"driverNumber": 22, "cleanLapCount": 3, "medianLapSeconds": 91.0},
        ]},
    }, ensure_ascii=False)


class FakeTransport:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []
        self.response: dict[str, object] = {
            "status_code": 200,
            "body": {"model": "deepseek-flash", "usage": {"total_tokens": 12}, "choices": [{
                "finish_reason": "stop",
                "message": {"content": '{"option_ids":[10],"confidence":0.75,"reasoning_summary":"圈速中位数较低"}'},
            }]},
        }

    async def send(
        self, *, url: str, headers: dict[str, str],
        json_body: dict[str, object], timeout: float,
    ) -> dict[str, object]:
        self.calls.append({"url": url, "headers": headers, "json_body": json_body, "timeout": timeout})
        return self.response


def create_model(tmp_path: Path, transport: object, *, max_calls: int = 3, timeout_seconds: float = 30.0) -> DeepSeekModel:
    return DeepSeekModel(
        DeepSeekConfig(
            api_key="secret-test", model="deepseek-flash",
            timeout_seconds=timeout_seconds,
        ),
        transport=transport,  # type: ignore[arg-type]
        ledger=ModelCallLedger(
            tmp_path / "ledger.sqlite3", max_calls=max_calls,
            call_reserve_microunits=10, total_budget_microunits=max_calls * 10,
        ),
    )


def test_bound_gateway_sends_fixed_json_mode_request_and_validates_candidate(tmp_path: Path) -> None:
    transport = FakeTransport()
    model = create_model(tmp_path, transport)
    gateway = model.bind(INVOCATION)

    result = asyncio.run(gateway.predict(context()))

    assert result == ModelCandidate(
        option_ids=[10], confidence=0.75, reasoning_summary="圈速中位数较低"
    )
    request, = transport.calls
    body = request["json_body"]
    assert request["url"] == "https://api.deepseek.com/chat/completions"
    assert request["headers"] == {
        "Authorization": "Bearer secret-test", "Content-Type": "application/json"
    }
    assert body["model"] == "deepseek-flash"
    assert body["stream"] is False
    assert body["temperature"] == 0
    assert body["max_tokens"] > 0
    assert body["response_format"] == {"type": "json_object"}
    assert body["extra_body"] == {"thinking": {"type": "disabled"}}
    assert "json" in body["messages"][0]["content"].lower()


def test_sdk_transport_uses_bounded_streaming_and_passes_the_official_request_to_client() -> None:
    class FakeResponse:
        status_code = 200

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args: object) -> None:
            return None

        async def iter_bytes(self, *, chunk_size: int):
            assert chunk_size == 4096
            yield b'{"model":"deepseek-flash","choices":[]}'

    class FakeCompletions:
        def __init__(self) -> None:
            self.kwargs: dict[str, object] = {}
            self.with_streaming_response = self

        def create(self, **kwargs: object):
            self.kwargs = kwargs
            return FakeResponse()

    class FakeClient:
        def __init__(self) -> None:
            self.chat = type("Chat", (), {})()
            self.chat.completions = FakeCompletions()

    config = DeepSeekConfig(api_key="secret-test", model="deepseek-flash")
    client = FakeClient()
    transport = OpenAISDKDeepSeekTransport(config, client=client)
    result = asyncio.run(transport.send(
        url="https://api.deepseek.com/chat/completions",
        headers={"Authorization": "Bearer secret-test", "Content-Type": "application/json"},
        json_body={"model": "deepseek-flash", "response_format": {"type": "json_object"}},
        timeout=8,
    ))

    assert result == {"status_code": 200, "body": {"model": "deepseek-flash", "choices": []}}
    assert client.chat.completions.kwargs["model"] == "deepseek-flash"
    assert client.chat.completions.kwargs["response_format"] == {"type": "json_object"}
    assert client.chat.completions.kwargs["timeout"] == 8
    assert client.chat.completions.kwargs["extra_headers"] == {
        "Authorization": "Bearer secret-test", "Accept-Encoding": "identity",
    }


def test_sdk_http_transport_requests_identity_and_rejects_compressed_response() -> None:
    import httpx

    closed = False
    observed_encoding: str | None = None

    class CompressedBody(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield b"compressed"

        async def aclose(self) -> None:
            nonlocal closed
            closed = True

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal observed_encoding
        observed_encoding = request.headers.get("accept-encoding")
        return httpx.Response(
            400, headers={"content-encoding": "gzip"}, stream=CompressedBody(), request=request,
        )

    async def send_request() -> None:
        async with httpx.AsyncClient(
            transport=create_bounded_httpx_transport(httpx.MockTransport(handler)),
            trust_env=False,
        ) as client:
            with pytest.raises(DeepSeekTransportError):
                await client.get("https://api.deepseek.com/chat/completions")

    asyncio.run(send_request())

    assert observed_encoding == "identity"
    assert closed is True


@pytest.mark.parametrize("buffered", [False, True])
def test_sdk_http_transport_caps_error_body_before_it_is_read(buffered: bool) -> None:
    import httpx

    class ChunkedBody(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield b"x" * 20_000
            yield b"x" * 12_769

        async def aclose(self) -> None:
            return None

    class OversizedResponseTransport(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            if buffered:
                return httpx.Response(400, content=b"x" * 32_769, request=request)
            return httpx.Response(400, stream=ChunkedBody(), request=request)

        async def aclose(self) -> None:
            return None

    async def read_error_body() -> None:
        async with httpx.AsyncClient(
            transport=create_bounded_httpx_transport(OversizedResponseTransport()),
            trust_env=False,
        ) as client:
            if buffered:
                with pytest.raises(DeepSeekTransportError):
                    await client.get("https://api.deepseek.com/chat/completions")
            else:
                async with client.stream("GET", "https://api.deepseek.com/chat/completions") as response:
                    with pytest.raises(DeepSeekTransportError):
                        await response.aread()

    asyncio.run(read_error_body())


def test_missing_optional_sdk_fails_before_reserving_an_intent(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    import builtins

    original_import = builtins.__import__

    def import_without_openai(name: str, *args: object, **kwargs: object):
        if name == "openai":
            raise ImportError("optional SDK intentionally unavailable")
        return original_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", import_without_openai)
    config = DeepSeekConfig(api_key="secret-test", model="deepseek-flash")
    ledger = ModelCallLedger(
        tmp_path / "ledger.sqlite3", max_calls=3,
        call_reserve_microunits=10, total_budget_microunits=30,
    )
    gateway = DeepSeekModel(
        config, transport=OpenAISDKDeepSeekTransport(config), ledger=ledger
    ).bind(INVOCATION)

    with pytest.raises(RuntimeError, match="optional openai and httpx dependencies"):
        asyncio.run(gateway.predict(context()))

    assert ledger.snapshot().reserved_calls == 0


def test_sdk_preparation_disables_environment_and_retries_on_http_transport(monkeypatch: pytest.MonkeyPatch) -> None:
    import sys
    from types import SimpleNamespace

    import httpx

    transport_settings: dict[str, object] = {}
    sdk_settings: dict[str, object] = {}

    def reject_request(request: httpx.Request) -> httpx.Response:
        raise AssertionError("SDK preparation must not send requests")

    def fake_http_transport(**kwargs: object) -> httpx.MockTransport:
        transport_settings.update(kwargs)
        return httpx.MockTransport(reject_request)

    def fake_sdk(**kwargs: object) -> SimpleNamespace:
        sdk_settings.update(kwargs)
        http_client = kwargs["http_client"]
        assert isinstance(http_client, httpx.AsyncClient)
        return SimpleNamespace(close=http_client.aclose)

    monkeypatch.setattr(httpx, "AsyncHTTPTransport", fake_http_transport)
    monkeypatch.setitem(sys.modules, "openai", SimpleNamespace(
        AsyncOpenAI=fake_sdk, APIConnectionError=OSError, APIStatusError=RuntimeError,
    ))
    transport = create_openai_sdk_transport(DeepSeekConfig(api_key="secret-test", model="deepseek-flash"))
    try:
        transport.prepare()
        assert transport_settings == {"verify": True, "trust_env": False, "retries": 0}
        assert sdk_settings["max_retries"] == 0
    finally:
        asyncio.run(transport.aclose())


def test_sdk_transport_uses_lazy_factory_without_importing_sdk_during_creation() -> None:
    transport = create_openai_sdk_transport(
        DeepSeekConfig(api_key="secret-test", model="deepseek-flash")
    )
    assert isinstance(transport, OpenAISDKDeepSeekTransport)


def test_restarted_gateway_reuses_successful_candidate_without_second_transport_call(tmp_path: Path) -> None:
    transport = FakeTransport()
    model = create_model(tmp_path, transport)
    first = asyncio.run(model.bind(INVOCATION).predict(context()))
    second_invocation = ModelInvocation(
        prediction_job_id="job-1", attempt=2, lease_token="lease-b",
        allowed_option_ids=(10, 20), data_cutoff=INVOCATION.data_cutoff,
        model_version="deepseek-flash", prompt_version="prompt-v1", feature_version="feature-v1",
    )

    recovered = asyncio.run(create_model(tmp_path, transport).bind(second_invocation).predict(context()))

    assert recovered == first
    assert len(transport.calls) == 1


def test_third_paid_call_is_cached_on_fourth_lease_after_restart(tmp_path: Path) -> None:
    transport = FakeTransport()
    model = create_model(tmp_path, transport, max_calls=3)
    invocations = [
        ModelInvocation(
            prediction_job_id="job-1", attempt=attempt, lease_token=f"lease-{attempt}",
            allowed_option_ids=(10, 20), data_cutoff=INVOCATION.data_cutoff,
            model_version="deepseek-flash", prompt_version="prompt-v1", feature_version="feature-v1",
        )
        for attempt in (1, 2, 3)
    ]
    transport.response = {"status_code": 429, "body": {}}
    for invocation in invocations[:2]:
        with pytest.raises(RetryableModelFailure):
            asyncio.run(create_model(tmp_path, transport).bind(invocation).predict(context()))
    transport.response = FakeTransport().response
    expected = asyncio.run(create_model(tmp_path, transport).bind(invocations[2]).predict(context()))

    recovered = ModelInvocation(
        prediction_job_id="job-1", attempt=4, lease_token="lease-after-inbox-recovery",
        allowed_option_ids=(10, 20), data_cutoff=INVOCATION.data_cutoff,
        model_version="deepseek-flash", prompt_version="prompt-v1", feature_version="feature-v1",
    )
    actual = asyncio.run(create_model(tmp_path, transport).bind(recovered).predict(context()))

    assert actual == expected
    assert len(transport.calls) == 3
    snapshot = model.ledger.snapshot()
    assert snapshot.reserved_calls == 3
    assert snapshot.reserved_microunits == 30
    assert [call.state for call in snapshot.calls] == ["rejected", "rejected", "success"]


def test_unbound_prediction_is_rejected(tmp_path: Path) -> None:
    model = create_model(tmp_path, FakeTransport())
    with pytest.raises(ValueError):
        asyncio.run(model.predict(context()))


def test_hanging_transport_times_out_and_cannot_be_sent_again_under_new_lease(tmp_path: Path) -> None:
    class HangingTransport:
        def __init__(self) -> None:
            self.calls = 0
            self.never = asyncio.Event()

        async def send(self, **kwargs: object) -> dict[str, object]:
            self.calls += 1
            await self.never.wait()
            return {}

    transport = HangingTransport()
    model = create_model(tmp_path, transport, timeout_seconds=0.01)
    gateway = model.bind(INVOCATION)
    with pytest.raises(ModelCallUncertain):
        asyncio.run(gateway.predict(context()))

    next_invocation = ModelInvocation(
        prediction_job_id="job-1", attempt=2, lease_token="lease-after-timeout",
        allowed_option_ids=(10, 20), data_cutoff=INVOCATION.data_cutoff,
        model_version="deepseek-flash", prompt_version="prompt-v1", feature_version="feature-v1",
    )
    with pytest.raises(CallNotPermitted):
        asyncio.run(create_model(tmp_path, transport, timeout_seconds=0.01).bind(next_invocation).predict(context()))

    assert transport.calls == 1
    assert model.ledger.snapshot().calls[0].state == "uncertain"


def test_transport_timeout_is_uncertain_and_not_a_connection_retry(tmp_path: Path) -> None:
    class TimedOut:
        async def send(self, **kwargs: object) -> dict[str, object]:
            raise TimeoutError("secret and context must not leak")

    gateway = create_model(tmp_path, TimedOut(), timeout_seconds=0.01).bind(INVOCATION)
    with pytest.raises(ModelCallUncertain) as caught:
        asyncio.run(gateway.predict(context()))
    assert not isinstance(caught.value, ConnectionError)
    assert "secret" not in str(caught.value)


@pytest.mark.parametrize(
    ("status", "expected", "retryable"),
    [(401, ModelCallRejected, False), (402, ModelCallRejected, False),
     (403, ModelCallRejected, False), (422, ModelCallRejected, False),
     (429, RetryableModelFailure, True), (500, RetryableModelFailure, True),
     (503, RetryableModelFailure, True)],
)
def test_provider_status_retry_policy_stays_within_per_job_budget(
    tmp_path: Path, status: int, expected: type[Exception], retryable: bool,
) -> None:
    from f1_predict.prediction.model_calls import CallBudgetExceeded, CallNotPermitted

    transport = FakeTransport()
    transport.response = {"status_code": status, "body": {}}
    gateway = create_model(tmp_path, transport, max_calls=1).bind(INVOCATION)
    with pytest.raises(expected):
        asyncio.run(gateway.predict(context()))
    next_invocation = ModelInvocation(
        prediction_job_id="job-1", attempt=2, lease_token="lease-b",
        allowed_option_ids=(10, 20), data_cutoff=INVOCATION.data_cutoff,
        model_version="deepseek-flash", prompt_version="prompt-v1", feature_version="feature-v1",
    )
    next_gateway = create_model(tmp_path, transport, max_calls=1).bind(next_invocation)
    with pytest.raises(CallBudgetExceeded if retryable else CallNotPermitted):
        asyncio.run(next_gateway.predict(context()))
    assert len(transport.calls) == 1


@pytest.mark.parametrize(
    "content,finish_reason,returned_model",
    [
        ('{"option_ids":[10],"confidence":0.5,"reasoning_summary":"ok","extra":true}', "stop", "deepseek-flash"),
        ('{"option_ids":[10],"confidence":0.5,"reasoning_summary":"ok"}', "length", "deepseek-flash"),
        ("", "stop", "deepseek-flash"),
        ('{"option_ids":[10],"confidence":0.5,"reasoning_summary":"ok"}', "stop", "other-model"),
    ],
)
def test_response_shape_truncation_and_model_identity_fail_closed(
    tmp_path: Path, content: str, finish_reason: str, returned_model: str,
) -> None:
    transport = FakeTransport()
    transport.response["body"] = {"model": returned_model, "choices": [{
        "finish_reason": finish_reason, "message": {"content": content},
    }]}
    with pytest.raises(ModelCallRejected):
        asyncio.run(create_model(tmp_path, transport).bind(INVOCATION).predict(context()))


@pytest.mark.parametrize(
    "content",
    [
        '{"option_ids":[10],"confidence":0.5,"reasoning_summary":"secret-test"}',
        (
            '{"option_ids":[10],"confidence":0.5,"reasoning_summary":"'
            + "".join(chr(92) + f"u{ord(char):04x}" for char in "secret-test")
            + '"}'
        ),
    ],
    ids=["plain-key", "unicode-escaped-key"],
)
def test_response_containing_api_key_is_rejected_and_never_cached(tmp_path: Path, content: str) -> None:
    from dataclasses import replace

    transport = FakeTransport()
    transport.response["body"] = {"model": "deepseek-flash", "choices": [{
        "finish_reason": "stop", "message": {"content": content},
    }]}
    model = create_model(tmp_path, transport)

    with pytest.raises(ModelCallRejected) as caught:
        asyncio.run(model.bind(INVOCATION).predict(context()))

    assert "secret-test" not in str(caught.value)
    assert model.ledger.snapshot().calls[0].state == "rejected"
    assert not hasattr(model.ledger.snapshot().calls[0], "candidate_json")

    recovered = create_model(tmp_path, transport)
    new_lease = replace(INVOCATION, attempt=4, lease_token="lease-after-secret-rejection")
    with pytest.raises(CallNotPermitted):
        asyncio.run(recovered.bind(new_lease).predict(context()))
    assert len(transport.calls) == 1
    assert recovered.ledger.snapshot().calls[0].state == "rejected"


def test_response_must_select_an_allowed_frozen_option(tmp_path: Path) -> None:
    transport = FakeTransport()
    transport.response["body"] = {"model": "deepseek-flash", "choices": [{
        "finish_reason": "stop",
        "message": {"content": '{"option_ids":[99],"confidence":0.2,"reasoning_summary":"猜测"}'},
    }]}
    gateway = create_model(tmp_path, transport).bind(INVOCATION)
    with pytest.raises(ModelCallRejected):
        asyncio.run(gateway.predict(context()))


def test_context_rejects_unapproved_top_level_payload_before_transport(tmp_path: Path) -> None:
    transport = FakeTransport()
    gateway = create_model(tmp_path, transport).bind(INVOCATION)
    payload = json.loads(context())
    payload["sourceEvidence"] = [{"sourceName": "private-source"}]

    with pytest.raises(ModelCallRejected):
        asyncio.run(gateway.predict(json.dumps(payload)))
    assert transport.calls == []


def test_configuration_does_not_reveal_secret_or_accept_non_official_url(tmp_path: Path) -> None:
    config = DeepSeekConfig(api_key="sensitive-key", model="deepseek-flash")
    assert "sensitive-key" not in repr(config)
    assert "sensitive-key" not in config.model_dump_json()
    with pytest.raises(ValueError):
        DeepSeekConfig(api_key="sensitive-key", model="deepseek-flash", api_url="https://attacker.invalid")
