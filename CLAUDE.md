# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 工程入口与命令

仓库根不是 Maven 工程根；Java 模块位于 `f1aipredict/`，Python 模块位于 `python-agent-worker/`。不要把 `f1aipredict/f1aipredict/` 下的 `target/` 当作源码。两个模块的命令须在各自目录执行。

```bash
# 在 f1aipredict/（Java 21，Maven Wrapper）
./mvnw compile
./mvnw test
./mvnw -Dtest=PredictionBatchServiceImplTest test
./mvnw -Dtest=PredictionBatchServiceImplTest#methodName test
./mvnw clean verify
./mvnw spring-boot:run

# 在 python-agent-worker/（Python >=3.12，uv）
uv sync
uv run pytest
uv run pytest tests/messaging/dto/test_contracts.py
uv run pytest tests/messaging/dto/test_contracts.py::test_name
uv run ruff check .
uv run uvicorn f1_predict.api.app:app --host 127.0.0.1 --port 8000 --reload
```

Java 无独立 lint/格式化插件；Python `ruff` 是开发依赖。上方 `methodName` / `test_name` 是占位符，替换为实际测试方法名。Java 单测、反射契约测试以及大部分 MockMvc 测试不连接 MySQL；`spring-boot:run` 使用实际数据库配置，数据库不可用时不能正常启动。Java Feed 客户端测试用 MockWebServer，不调用真实 Feed。

## 系统边界与数据流

- `f1aipredict/` 是 Spring Boot 3.5 / Java 21 服务：Spring MVC 提供 REST API，WebFlux 仅用于 WebClient 拉取 Feed；MyBatis-Plus + MySQL 持久化。`season` 和 `question` 提供只读查询；`sync` 从外部 Feed 拉取 schedule/questions/limits，维护赛事、题目及快照；`prediction` 已实现批次创建和批次/任务查询，但还没有任务投递、消费或推理闭环。
- Java 领域代码按 `controller → service 接口/impl → mapper/entity` 分层，对外返回 DTO。`sync` 额外通过唯一 Feed 客户端 `F1PredictFeedClient` 拉取数据，通过 `SyncPersistenceStore` 管理同步记录/原始载荷；定时器和管理端调用 `FeedSyncService`。同步按内容哈希幂等，`syncCurrent` 经代理分别调用短事务子同步，不能将 HTTP 拉取包在大事务中。
- 预测批次创建先由 `RoundService` 和 `PredictionQuestionReadService` 在事务外校验分站、OPEN 题目、最新快照及选项；`PredictionBatchTransactionExecutor` 在独立短事务中原子创建 PENDING 批次与任务，并允许批次序号唯一键冲突重试。读取题目/快照/选项时批量查询，避免逐题访问 Mapper。当前批次创建不产生 RabbitMQ 消息。
- `python-agent-worker/` 是独立的 FastAPI 阶段一骨架：`common` 配置、requestId 与日志，`reliability` 本地追踪，`api` 健康检查/状态与错误处理中间件，`messaging/dto` 定义未来 Java→Python 的消息契约。尚未接入队列、数据库或模型推理；`/health/ready` 返回 503 DOWN 是预期占位行为。
- 跨语言契约以 Java 对外 camelCase / UTC 时间和 Python Pydantic 的 camelCase alias、UTC `Z` 序列化为核心。`sql/001..006_*.sql` 是手工维护的 DDL 来源（无迁移工具）；`005_answer_scoring.sql` 的评分流程仍未实现。`java-api-design.md`、`python-api-design.md` 和可行性分析包含未来方案，若与已实现代码不一致，以代码及契约测试为准。

## 修改时需保持的约定

- Java Controller 只依赖 Service 接口；DTO 字段显式标注同名 `@JsonProperty`；Entity 列映射遵守 SQL 脚本。项目现用 Jackson 2 (`com.fasterxml.jackson.*`)；字段/映射和 SQL 的一致性由反射契约测试守护。业务代码注释和 Javadoc 用中文。
- Java 时间使用 UTC `Instant`；Feed 带偏移的时间先用 `OffsetDateTime.parse(...).toInstant()`。API 分页 page 从 0 开始，默认 size 20、最多 100；MyBatis-Plus 内部页码从 1 开始。
- `common/` 统一处理 `X-Request-Id`、日志上下文和错误响应；同步失败对外是 502。Python 的相应横切逻辑位于 `common/` 与 `api/middleware.py`。不要把尚未落地的 Python 消费者或 Java 消息发布器当作可调用组件。
- 根及各子目录 `AGENTS.md` 有更细的领域/测试约定，但其 2026-09-06 代码状态描述早于当前 `prediction` 和 Python Worker 实现，遇到状态冲突先核对现有源码。不要执行 git commit。
