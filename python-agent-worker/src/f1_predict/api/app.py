"""FastAPI 应用入口：组装健康检查、Worker 状态、中间件与统一错误处理。

本阶段不注册 ``/predict`` 与 ``/api/v1/models/current``。
"""

from __future__ import annotations

from fastapi import FastAPI

from f1_predict.api.errors import register_exception_handlers
from f1_predict.api.health import router as health_router
from f1_predict.api.middleware import RequestContextMiddleware
from f1_predict.api.worker_status import router as worker_status_router

app = FastAPI(
    title="F1 Predict Worker",
    version="0.1.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)
app.add_middleware(RequestContextMiddleware)
register_exception_handlers(app)
app.include_router(health_router)
app.include_router(worker_status_router)
