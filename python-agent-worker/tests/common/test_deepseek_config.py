"""厂商选择与秘密配置通过公开 Settings 边界验证。"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from f1_predict.common.config import Settings


def test_unknown_model_provider_is_rejected() -> None:
    """未知厂商不能被当作额外配置忽略后静默回退私网模型。"""
    with pytest.raises(ValidationError):
        Settings(model_provider="unknown-provider")


def test_deepseek_selection_does_not_enable_paid_calls() -> None:
    """厂商选择只是配置，A 阶段不能据此获得真实请求授权。"""
    settings = Settings(model_provider="deepseek")

    assert settings.vendor_api_enabled is False
    assert "deepseek_api_key" not in settings.model_dump()
    assert settings.deepseek_model == ""
    assert settings.model_url == ""


def test_unknown_deepseek_model_is_rejected_without_runtime_creation() -> None:
    """非空模型 ID 必须来自已核对的官方契约，不接受任意别名。"""
    with pytest.raises(ValidationError):
        Settings(model_provider="deepseek", deepseek_model="unknown-model")


def test_paid_api_switch_cannot_bypass_stage_a_approval() -> None:
    """环境开关不能自行授予仍待批准的 C 阶段外发和付费范围。"""
    with pytest.raises(ValidationError, match="stage C"):
        Settings(model_provider="deepseek", vendor_api_enabled=True)


def test_offline_deepseek_configuration_needs_no_private_model_url() -> None:
    """Fake 接入需明确模型和预算，不能要求伪造一个私网模型地址。"""
    settings = Settings(
        prediction_enabled=True,
        model_provider="deepseek",
        prediction_policy_path="/readonly/policy.json",
        prediction_laps_path="/readonly/laps.json",
        model_version="deepseek-flash",
        deepseek_model="deepseek-flash",
        prompt_version="prompt-v1",
        feature_version="feature-v1",
        deepseek_ledger_path="/private/test-only/calls.sqlite",
        deepseek_approval_id="offline-test-approval",
        deepseek_max_cost_micro_usd=300,
        deepseek_reservation_micro_usd=100,
    )

    assert settings.prediction_enabled is True
    assert settings.vendor_api_enabled is False
    assert settings.model_url == ""
    assert settings.deepseek_max_calls == 3


def test_configuration_errors_do_not_echo_supplied_secret() -> None:
    """即使误传了额外密钥参数，校验失败也不能复制原始输入。"""
    secret = "fake-key"
    with pytest.raises(ValidationError) as captured:
        Settings(vendor_api_enabled=True, deepseek_api_key=secret)

    assert secret not in str(captured.value)


@pytest.mark.parametrize("field", [
    "deepseek_max_calls", "deepseek_max_cost_micro_usd", "deepseek_reservation_micro_usd",
])
@pytest.mark.parametrize("value", [True, 1.0, "1.0"])
def test_budget_limits_do_not_coerce_non_integer_values(field: str, value: object) -> None:
    """预算字段只接受整数量或部署环境的整数字面量。"""
    with pytest.raises(ValidationError):
        Settings(**{field: value})


def test_integer_budget_environment_values_remain_supported(monkeypatch: pytest.MonkeyPatch) -> None:
    """严格预算校验不破坏部署环境变量的整数字面量。"""
    monkeypatch.setenv("F1_PREDICT_DEEPSEEK_MAX_CALLS", "2")
    monkeypatch.setenv("F1_PREDICT_DEEPSEEK_MAX_COST_MICRO_USD", "300")
    monkeypatch.setenv("F1_PREDICT_DEEPSEEK_RESERVATION_MICRO_USD", "100")

    settings = Settings()

    assert settings.deepseek_max_calls == 2
    assert settings.deepseek_max_cost_micro_usd == 300
    assert settings.deepseek_reservation_micro_usd == 100


def test_configuration_errors_do_not_echo_connection_secret() -> None:
    """已知连接字段也不能被模型级校验错误原样回显。"""
    secret = "K9xyZ"
    with pytest.raises(ValidationError) as captured:
        Settings(vendor_api_enabled=True, rabbitmq_url=secret)

    assert secret not in str(captured.value)
