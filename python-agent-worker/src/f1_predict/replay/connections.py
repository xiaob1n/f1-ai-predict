"""显式注入的连接计划；导入和构造阶段绝不创建外部连接。"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class ConnectionRole(str, Enum):
    """回放过程中允许区分的资源角色。"""

    SOURCE_MYSQL = "source_mysql"
    SOURCE_MONGO = "source_mongo"
    ISOLATED_DATABASE = "isolated_database"
    ISOLATED_BROKER = "isolated_broker"


@dataclass(frozen=True, slots=True)
class ConnectionPlan:
    """不含环境发现或凭据读取的显式端点计划。"""

    role: ConnectionRole
    host: str
    port: int
    database: str
    project: str
    connector_factory: Callable[..., Any] = field(repr=False, compare=False)
    username: str | None = field(default=None, repr=False)
    password: str | None = field(default=None, repr=False)
    vhost: str | None = None
    collection: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.role, ConnectionRole):
            raise TypeError("连接角色必须显式指定")
        if (isinstance(self.port, bool) or not isinstance(self.port, int)
                or not 1 <= self.port <= 65535):
            raise ValueError("连接端口无效")
        if not callable(self.connector_factory):
            raise TypeError("必须显式注入连接工厂")
        if not isinstance(self.project, str) or not self.project.startswith("real-data-mvp-"):
            raise ValueError("连接计划必须绑定专属项目")
        if self.role in {ConnectionRole.ISOLATED_DATABASE, ConnectionRole.ISOLATED_BROKER}:
            if self.host != "127.0.0.1":
                raise ValueError("隔离服务只允许绑定 IPv4 回环")
            if self.role is ConnectionRole.ISOLATED_DATABASE and (
                    self.database != "f1_ai_predict" or self.port != 13316):
                raise ValueError("隔离数据库名称或端口不匹配")
            if self.role is ConnectionRole.ISOLATED_BROKER and (
                    self.vhost != "/f1predict-mvp" or self.port != 15683):
                raise ValueError("隔离 Broker vhost 或端口不匹配")
        elif self.role is ConnectionRole.SOURCE_MYSQL and self.database != "f1_ai_predict":
            raise ValueError("源 MySQL 仅允许指定业务数据库")
        elif self.role is ConnectionRole.SOURCE_MONGO:
            if (not isinstance(self.database, str) or not self.database.strip()
                    or self.database == "laps"):
                raise ValueError("源 Mongo 必须显式指定数据库名称，而不是 laps 集合名")
            if self.collection != "laps":
                raise ValueError("源 Mongo 集合必须固定为 laps")
        elif self.collection is not None:
            raise ValueError("仅源 Mongo 连接计划可指定集合")


def open_connection(
    plan: ConnectionPlan, *, permission: object | None = None, permission_check: Any = None
) -> Any:
    """仅由独立检查器认可显式授权后调用工厂；配置字段不构成授权证明。"""
    if permission is None or not callable(permission_check):
        raise PermissionError("缺少独立外部资源操作授权")
    try:
        permitted = permission_check(permission, plan.role.value, plan.project)
    except Exception:  # noqa: BLE001 - 不回显授权检查器内部信息。
        raise PermissionError("外部资源授权无法核验") from None
    if permitted is not True:
        raise PermissionError("外部资源操作未获授权")
    kwargs: dict[str, Any] = {
        "host": plan.host,
        "port": plan.port,
        "database": plan.database,
        "project": plan.project,
        "role": plan.role.value,
    }
    if plan.role in {ConnectionRole.SOURCE_MYSQL, ConnectionRole.SOURCE_MONGO}:
        kwargs["read_only"] = True
    if plan.role is ConnectionRole.ISOLATED_BROKER:
        kwargs["vhost"] = plan.vhost
    if plan.username is not None:
        kwargs["username"] = plan.username
    if plan.password is not None:
        kwargs["password"] = plan.password
    try:
        return plan.connector_factory(**kwargs)
    except Exception:  # noqa: BLE001 - 驱动异常不得泄漏连接串或密钥。
        raise RuntimeError("外部连接器操作失败") from None


def wrap_source(plan: ConnectionPlan, injected_resource: Any) -> Any:
    """仅将已由调用方取得的只读资源包装进既有固定查询适配器。"""
    if injected_resource is None:
        raise ValueError("源适配器需要显式注入的资源对象")
    if plan.role is ConnectionRole.SOURCE_MYSQL:
        from f1_predict.replay.source import MySqlReplaySource

        return MySqlReplaySource(injected_resource)
    if plan.role is ConnectionRole.SOURCE_MONGO:
        from f1_predict.replay.source import MongoLapSource

        return MongoLapSource(injected_resource)
    raise ValueError("仅源角色可包装只读回放适配器")
