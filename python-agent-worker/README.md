# f1-predict Python Worker

`python-agent-worker` 是 F1 AI 预测系统的 Python Worker 子模块。本阶段提供可安装的 FastAPI 工程骨架、统一配置、请求标识上下文、结构化日志、追踪接口、运维 REST 接口和消息 DTO，便于后续接入预测任务流程。

## 阶段一边界

- 本阶段不连接任何外部依赖，不建立消息队列、数据库或向量服务连接。
- 本阶段不实现预测接口、模型加载、模型推理、训练、RAG、消费者或发布者。
- `/health/ready` 在外部依赖尚未接入时返回 `503 DOWN`，这是预期的占位状态。
- 只提供 `/health/live`、`/health/ready` 和 `/api/v1/worker/status` 三个运维接口。
- 本阶段不包含 Docker、CI 或前端实现。

## 目录结构

```text
python-agent-worker/
├── pyproject.toml          # 工程元数据、依赖和测试配置
├── uv.lock                 # uv 依赖锁定文件
├── src/f1_predict/
│   ├── common/             # 配置、请求标识上下文和结构化日志
│   ├── reliability/        # TraceSink 与本地控制台追踪实现
│   ├── api/                # FastAPI 应用、健康检查和 Worker 状态接口
│   └── messaging/dto/      # 四类消息 DTO 与 camelCase 契约
└── tests/                  # API、基础设施、追踪和 DTO 契约测试
```

目录中的实现均以阶段一契约为边界：`common` 负责横切能力，`reliability` 只记录本地追踪信息，`api` 只提供运维接口，`messaging/dto` 只定义消息数据结构。

## 环境变量

配置由 `src/f1_predict/common/config.py` 统一读取。下面只列环境变量名，不填写任何值；阶段一无需配置外部依赖即可运行。

- `F1_PREDICT_HOST`
- `F1_PREDICT_PORT`
- `F1_PREDICT_LOG_LEVEL`
- `F1_PREDICT_RABBITMQ_URL`
- `F1_PREDICT_MONGODB_URL`
- `F1_PREDICT_QDRANT_URL`

## 本地开发

以下命令均在 `python-agent-worker/` 目录执行。

安装依赖并创建虚拟环境：

```bash
uv sync
```

启动本地开发服务：

```bash
uv run uvicorn f1_predict.api.app:app --host 127.0.0.1 --port 8000 --reload
```

运行全部测试：

```bash
uv run pytest
```

## 运维接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/health/live` | 进程存活检查，返回 `UP` |
| `GET` | `/health/ready` | 阶段一依赖就绪占位检查，返回 `503 DOWN` |
| `GET` | `/api/v1/worker/status` | 返回 Worker 占位运行状态 |

错误响应统一使用 `code` 和 `message` 字段，请求响应带有 `X-Request-Id`。
