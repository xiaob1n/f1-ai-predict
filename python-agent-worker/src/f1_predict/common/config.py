"""进程配置：仅安全默认值，外部服务 URL 默认为空。

独立消费者会连接 RabbitMQ；HTTP 进程仍不建立外部连接。
MongoDB / Qdrant 保持未接入，空 URL 不代表可用连接。
"""

from __future__ import annotations

from typing import ClassVar, Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

type LogLevelName = Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]


class Settings(BaseSettings):
    """Worker 进程配置。

    可变是为了测试覆盖字段；默认只监听本机回环，不包含真实凭据。
    """

    model_config: ClassVar[SettingsConfigDict] = SettingsConfigDict(
        env_prefix="F1_PREDICT_",
        extra="ignore",
        case_sensitive=False,
        env_file=None,
        hide_input_in_errors=True,
    )

    host: str = Field(default="127.0.0.1", min_length=1)
    """HTTP 监听地址，默认仅本机回环，避免误绑到所有网卡。"""

    port: int = Field(default=8000, ge=1, le=65535)
    """HTTP 监听端口。"""

    log_level: LogLevelName = "INFO"
    """结构化日志阈值，默认 INFO。"""

    rabbitmq_url: str = ""
    """独立消费者启动时必须提供；HTTP 进程不建立连接。"""

    consumer_sqlite_path: str = "/tmp/f1_worker_inbox.db"
    """持久卷上的本地 SQLite 文件；独立消费进程必填。"""

    prediction_enabled: bool = False
    """显式启用预测推理；未完成部署配置前保持关闭。"""

    prediction_policy_path: str = ""
    """预测快照策略 JSON 文件路径；为空时不启用策略加载。"""

    prediction_laps_path: str = ""
    """只读、经过审计的本地圈速 fixture 文件路径。"""

    prediction_execution_mode: Literal["AUDITED", "HISTORICAL_ENGINEERING_REPLAY"] = "AUDITED"
    """历史工程回放只允许由受控部署配置启用，不从请求或模型输出推断。"""

    prediction_replay_manifest_path: str = ""
    """历史工程回放的已冻结清单；常规模式不得配置。"""

    prediction_replay_manifest_hash: str = Field(default="", pattern=r"^(?:[a-f0-9]{64})?$")
    """从独立审查记录配置的 canonical 摘要，不由待加载的包自报。"""

    prediction_replay_model_mode: Literal["stub", "real"] = "stub"
    """回放模型类别由部署确定，须与清单声明一致。"""

    model_provider: Literal["local", "deepseek"] = "local"
    """显式选择模型边界；旧配置保持私网模式，不隐式启用厂商 API。"""

    vendor_api_enabled: bool = False
    """厂商请求默认关闭;选择厂商不代表真实调用或预算获得批准。"""

    deepseek_api_key: SecretStr | None = Field(default=None, exclude=True)
    """厂商 API 密钥只在显式启用时读取，日志和配置快照中保持脱敏。"""

    deepseek_model: str = ""
    """显式登记的官方模型 ID，不使用未知或自动变化的默认模型。"""

    deepseek_ledger_path: str = ""
    """显式注入的调用账本文件路径，配置解析不创建数据库。"""

    deepseek_approval_id: str = ""
    """离线调用预算的审查引用，不自证真实付费授权。"""

    deepseek_max_calls: int = Field(default=3, ge=1, le=3)
    """同一任务含不明请求在内的总调用次数上限。"""

    deepseek_max_cost_micro_usd: int = Field(default=0, ge=0)
    """经审定的单题费用预留上限，默认不授予预算。"""

    deepseek_reservation_micro_usd: int = Field(default=0, ge=0)
    """每次潜在收费请求的保守预留，不代表实际账单。"""

    model_url: str = ""
    """仅私网模型使用的地址；DeepSeek 不通过此字段放宽网络范围。"""

    model_version: str = ""
    """部署配置的模型版本标识。"""

    prompt_version: str = ""
    """部署配置的提示词版本标识。"""

    feature_version: str = ""
    """部署配置的特征版本标识。"""

    model_timeout_seconds: float = Field(default=30.0, gt=0, le=600)
    """单次模型调用超时时间。"""

    model_max_attempts: int = Field(default=3, ge=1, le=10)
    """模型调用的最大尝试次数。"""

    consumer_health_path: str = ""
    """消费者进程写入的健康状态文件，默认为 SQLite 同目录文件。"""

    request_exchange: str = "f1.prediction.request.v2"
    request_queue: str = "f1.prediction.request.v2"
    request_routing_key: str = "prediction.request.v2"
    dead_letter_exchange: str = "f1.prediction.dead.v2"
    dead_letter_queue: str = "f1.prediction.dead.v2"
    dead_letter_routing_key: str = "prediction.dead.v2"
    result_exchange: str = "f1.prediction.result.v2"
    result_queue: str = "f1.prediction.result.v2"
    result_routing_key: str = "f1.prediction.result.v2"
    result_dead_letter_exchange: str = "f1.prediction.result.dead.v2"
    result_dead_letter_queue: str = "f1.prediction.result.dead.v2"
    result_dead_letter_routing_key: str = "f1.prediction.result.dead.v2"
    failure_exchange: str = "f1.prediction.failure.v2"
    failure_queue: str = "f1.prediction.failure.v2"
    failure_routing_key: str = "f1.prediction.failure.v2"
    failure_dead_letter_exchange: str = "f1.prediction.failure.dead.v2"
    failure_dead_letter_queue: str = "f1.prediction.failure.dead.v2"
    failure_dead_letter_routing_key: str = "f1.prediction.failure.dead.v2"
    consumer_prefetch: int = Field(default=10, ge=1, le=100)
    consumer_max_message_bytes: int = Field(default=262144, ge=1024)
    result_max_message_bytes: int = Field(default=262144, ge=1024)
    failure_max_message_bytes: int = Field(default=16384, ge=1024)
    consumer_reconnect_seconds: float = Field(default=2.0, gt=0, le=60)
    consumer_shutdown_seconds: float = Field(default=30.0, gt=0, le=300)

    mongodb_url: str = ""
    """本地 MongoDB URL，阶段一保持空字符串，不建立连接。"""

    qdrant_url: str = ""
    """本地 Qdrant URL，阶段一保持空字符串，不建立连接。"""

    @field_validator("deepseek_model")
    @classmethod
    def validate_vendor_model(cls, value: str) -> str:
        """复用适配器的官方模型白名单；空值不选择任何厂商模型。"""
        if value:
            from f1_predict.prediction.model_vendor_api import SUPPORTED_MODELS

            if value not in SUPPORTED_MODELS:
                raise ValueError("DeepSeek model is not supported by the approved adapter contract")
        return value

    @field_validator(
        "deepseek_max_calls", "deepseek_max_cost_micro_usd", "deepseek_reservation_micro_usd",
        mode="before",
    )
    @classmethod
    def validate_vendor_budget_integer(cls, value: object) -> int:
        """允许环境整数字面量，拒绝布尔、小数与隐式预算取整。"""
        if type(value) is int:
            return value
        if isinstance(value, str) and value.isascii() and value.isdecimal():
            return int(value)
        raise ValueError("vendor budget limits require integers")

    @model_validator(mode="after")
    def validate_prediction_configuration(self) -> Settings:
        """仅在策略、已审计圈速 fixture 与模型服务均配置时启用推理。"""
        replay_stub = (self.prediction_execution_mode == "HISTORICAL_ENGINEERING_REPLAY"
                       and self.prediction_replay_model_mode == "stub")
        # C阶段已获批准 (deepseek-flash, 无上限预算, 2026-10-05)
        # if self.vendor_api_enabled:
        #     raise ValueError("vendor API calls remain blocked until separately approved stage C")
        if self.prediction_enabled:
            model_fields = (
                ("model_url", self.model_url if not replay_stub else "local-stub"),
            )
            if self.model_provider == "deepseek":
                if replay_stub:
                    raise ValueError("historical replay stub must not configure a vendor model")
                if self.model_url.strip():
                    raise ValueError("DeepSeek must not reuse a private model URL")
                if self.deepseek_model != self.model_version:
                    raise ValueError("DeepSeek model must match the registered model version")
                if not 0 < self.deepseek_reservation_micro_usd <= self.deepseek_max_cost_micro_usd:
                    raise ValueError("DeepSeek requires a bounded, nonzero offline budget")
                model_fields = (
                    ("deepseek_model", self.deepseek_model),
                    ("deepseek_ledger_path", self.deepseek_ledger_path),
                    ("deepseek_approval_id", self.deepseek_approval_id),
                    ("prompt_version", self.prompt_version),
                    ("feature_version", self.feature_version),
                )
            missing = [
                name
                for name, value in (
                    ("prediction_policy_path", self.prediction_policy_path),
                    ("prediction_laps_path", self.prediction_laps_path),
                    *model_fields,
                )
                if not value.strip()
            ]
            if replay_stub and self.model_url.strip():
                raise ValueError("historical replay stub must not configure a model endpoint")
            if missing:
                raise ValueError(
                    "prediction_enabled requires configured " + ", ".join(missing)
                )
        if self.prediction_execution_mode == "HISTORICAL_ENGINEERING_REPLAY":
            if (not self.prediction_enabled or not self.prediction_replay_manifest_path.strip()
                    or not self.prediction_replay_manifest_hash):
                raise ValueError("historical replay requires enabled prediction, a manifest and its approved hash")
        elif self.prediction_replay_manifest_path.strip() or self.prediction_replay_manifest_hash:
            raise ValueError("replay manifest requires historical replay mode")
        return self

    @field_validator("log_level", mode="before")
    @classmethod
    def normalize_log_level(cls, value: str) -> str:
        """环境变量大小写不敏感，统一成大写后再按字面量校验。"""
        return value.upper()
