"""进程配置：仅安全默认值，外部服务 URL 默认为空。

本阶段不建立 RabbitMQ / MongoDB / Qdrant 连接；URL 字段存在是为了
后续 todo 读取同一配置入口，调用方不得把空字符串当作成熟连接串使用。
"""

from __future__ import annotations

from typing import ClassVar, Literal

from pydantic import Field, field_validator
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
    """RabbitMQ 连接 URL，阶段一保持空字符串，不建立连接。"""

    mongodb_url: str = ""
    """本地 MongoDB URL，阶段一保持空字符串，不建立连接。"""

    qdrant_url: str = ""
    """本地 Qdrant URL，阶段一保持空字符串，不建立连接。"""

    @field_validator("log_level", mode="before")
    @classmethod
    def normalize_log_level(cls, value: str) -> str:
        """环境变量大小写不敏感，统一成大写后再按字面量校验。"""
        return value.upper()
