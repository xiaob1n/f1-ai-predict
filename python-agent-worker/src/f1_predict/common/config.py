"""进程配置：仅安全默认值，外部服务 URL 默认为空。

独立消费者会连接 RabbitMQ；HTTP 进程仍不建立外部连接。
MongoDB / Qdrant 保持未接入，空 URL 不代表可用连接。
"""

from __future__ import annotations

from typing import ClassVar, Literal

from pydantic import Field, field_validator, model_validator
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
    )

    host: str = Field(default="127.0.0.1", min_length=1)
    """HTTP 监听地址，默认仅本机回环，避免误绑到所有网卡。"""

    port: int = Field(default=8000, ge=1, le=65535)
    """HTTP 监听端口。"""

    log_level: LogLevelName = "INFO"
    """结构化日志阈值，默认 INFO。"""

    rabbitmq_url: str = ""
    """独立消费者启动时必须提供；HTTP 进程不建立连接。"""

    consumer_sqlite_path: str = ""
    """持久卷上的本地 SQLite 文件；独立消费进程必填。"""

    prediction_enabled: bool = False
    """显式启用预测推理；未完成部署配置前保持关闭。"""

    prediction_policy_path: str = ""
    """预测快照策略 JSON 文件路径；为空时不启用策略加载。"""

    prediction_laps_path: str = ""
    """只读、经过审计的本地圈速 fixture 文件路径。"""

    model_url: str = ""
    """推理服务地址；为空时不得请求外部模型。"""

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
    result_routing_key: str = "prediction.result.v2"
    result_dead_letter_exchange: str = "f1.prediction.result.dead.v2"
    result_dead_letter_queue: str = "f1.prediction.result.dead.v2"
    result_dead_letter_routing_key: str = "prediction.result.dead.v2"
    failure_exchange: str = "f1.prediction.failure.v2"
    failure_queue: str = "f1.prediction.failure.v2"
    failure_routing_key: str = "prediction.failure.v2"
    failure_dead_letter_exchange: str = "f1.prediction.failure.dead.v2"
    failure_dead_letter_queue: str = "f1.prediction.failure.dead.v2"
    failure_dead_letter_routing_key: str = "prediction.failure.dead.v2"
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

    @model_validator(mode="after")
    def validate_prediction_configuration(self) -> Settings:
        """仅在策略、已审计圈速 fixture 与模型服务均配置时启用推理。"""
        if self.prediction_enabled:
            missing = [
                name
                for name, value in (
                    ("prediction_policy_path", self.prediction_policy_path),
                    ("prediction_laps_path", self.prediction_laps_path),
                    ("model_url", self.model_url),
                )
                if not value.strip()
            ]
            if missing:
                raise ValueError(
                    "prediction_enabled requires configured " + ", ".join(missing)
                )
        return self

    @field_validator("log_level", mode="before")
    @classmethod
    def normalize_log_level(cls, value: str) -> str:
        """环境变量大小写不敏感，统一成大写后再按字面量校验。"""
        return value.upper()
