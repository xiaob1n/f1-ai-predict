# AGENTS.md（仓库根知识库）

生成日期：2026-09-03
基线：commit `ece75d1`，branch `dev`
维护规则：本文件属于未提交的工作区变更，任何情况下不执行 git 提交。

## 仓库边界

仓库根不是 Maven 工程根，真正的 Maven 模块在 `f1aipredict/`（含 `pom.xml`、`mvnw`、`src/`）。`f1aipredict/f1aipredict/` 内只剩构建产物目录 `target/`，无源码。

代码分析排除：`.git/`、`.omo/`、`.opencode/`、`.idea/`、`**/target/`、`.DS_Store` 及 IDE 工程文件（`.project`、`.classpath`、`.factorypath`、`.settings/` 等）。

无 `.codegraph` 索引（仓库根与模块均无），按常规 `rg`/Read 检索即可。

## 技术栈与运行态

模块 `f1aipredict`：Spring Boot 4.1.1（parent）、Java 21、Spring Web MVC、WebFlux/WebClient（Feed 客户端）、Validation、spring-jdbc + MySQL Connector/J、MyBatis-Plus 3.5.17（`mybatis-plus-spring-boot4-starter` + jsqlparser）、Lombok、JUnit 5 + Mockito + MockWebServer。

已实现运行态：`season`、`question`、`sync` 三个领域的基础层与读写链路，外加 `common`（全局异常、请求日志/requestId）与 `config`（MyBatis-Plus）。

文档中规划但仓库内无实现：预测 `prediction`（仅 `sql/004_prediction.sql` 建表）、官方答案与评分 `scoring`（仅 `sql/005_answer_scoring.sql` 建表）、统计 `statistics`、RabbitMQ/Redis、Python Agent/LLM 训练、前端 UI、Docker/CI。这些只在设计文档里描述，阅读时不要当作已落地功能。

## 顶层文档与 SQL 边界

`java-api-design.md`：接口分层、REST 设计、Service 契约、DTO 规范。含前瞻设计，与已实现代码有出入，以代码与测试契约为准。

`f1-ai-predict-feasibility-analysis.md`：整体架构决策文档（数据源、MySQL/OpenF1 MongoDB/Redis/RabbitMQ 分工、Python 训练与部署、防泄漏）。

`sql/001..006_*.sql`：手工维护的唯一 DDL 来源，无 Flyway/Liquibase。001 建库；002 season/round/meeting_session；003 question/question_snapshot/question_option；004 prediction_*；005 official_answer/scoring_detail/batch_total_score；006 sync_record/feed_raw_payload。Entity 字段与脚本 1:1，由纯反射契约测试守护。

## 代码地图

入口 `com.lbz.f1aipredict.F1aipredictApplication`：`@SpringBootApplication` + `@EnableScheduling` + `@EnableConfigurationProperties(F1PredictFeedProperties)`。

sync 域（最完整）：
`sync/controller/SyncAdminController`：`/api/v1/admin/sync/*` 手动同步与记录查询，只依赖 `FeedSyncService`。
`sync/schedule/HourlyQuestionSyncScheduler`：每小时调 `FeedSyncService.syncCurrent()`；开关 `f1predict.sync.scheduler.enabled`（默认开启）；延迟/间隔 `initial-delay`/`fixed-delay`，默认 `PT1H`。
`sync/service/FeedSyncService(+impl)`：编排 schedule/questions/limits/current，按 SHA-256 `contentHash` 幂等 upsert；`syncCurrent` 经 `@Lazy` 注入的自身代理调用子同步，保证各自独立小事务。
`sync/client/F1PredictFeedClient`：WebClient 封装，URL 一律来自 `F1PredictFeedProperties`（`f1predict.feed.*`），阻塞取 String，错误转 `FeedSyncException`。
`sync/store/SyncPersistenceStore`：`sync_record`/`feed_raw_payload` 的唯一持久化入口，业务 Service 禁止直连这两个 Mapper。
`sync/feed/*`：Feed JSON 模型，Feed 用 PascalCase 键，均配 `@JsonProperty` 与 `@JsonIgnoreProperties(ignoreUnknown=true)`。
`sync/util/FeedSyncUtils`：SHA-256 等工具。`sync/FeedSyncException`：带 HTTP 状态的同步异常。

question 域（只读查询）：
`question/controller/QuestionController`：`/api/v1/rounds/{roundId}/questions`、`/api/v1/questions/{questionId}`、`/api/v1/questions/{questionId}/snapshots[/{snapshotId}]`。
`question/service/QuestionService(+impl)`：快照与选项在循环外批量加载，避免 N+1。
`question/mapper/Question*Mapper`：注解 SQL 与 `@SelectProvider`，无 XML。
`question/dto`：对外 DTO，`questionType` 当前固定 `UNKNOWN`。

season 域：仅 `entity`（Season/Round/MeetingSession）+ `mapper`，由同步写入，尚无查询 Service/Controller。

common/config：
`common/GlobalExceptionHandler`：`ResourceNotFoundException` → 404 `RESOURCE_NOT_FOUND`；`FeedSyncException` → 502 `FEED_SYNC_ERROR`；对外文案剥 URL。
`common/RequestLoggingFilter` + `RequestId`：`X-Request-Id` 响应头与 MDC `requestId`，入站 ID 走白名单校验。
`common/dto/ApiErrorResponse`：统一错误体 `{code, message}`。
`config/MybatisPlusConfig`：MapperScan（question/sync/season）+ MySQL 分页 + BlockAttack 防全表更新/删除。

## 项目约定（改代码必须遵守）

- DTO：Lombok `@Data` + `@Builder` + 全参/无参构造；每个字段显式 `@JsonProperty("camelCase 名")`，值必须与 Java 字段名一致（契约测试反射强制）。对外 JSON 一律 camelCase。
- Entity：`@TableName` + `@TableId(IdType.AUTO)` + 非主键列显式 `@TableField("snake_case 列")`；DB snake_case 列映射 Java camelCase 字段（配合 `map-underscore-to-camel-case: true`）。`DATE` 列映射 `LocalDate`，`DATETIME(3)` 映射 `Instant`，一律 UTC。
- 分层：Controller 只依赖 Service 接口、只返回 DTO；Service 分接口与 impl；存在 Store 时统一走 Store。
- 复用优先：已有 Service/Store/Client 能完成的职责不新写链路；`F1PredictFeedClient` 是唯一 Feed 客户端，Feed URL 不硬编码、全部读 `F1PredictFeedProperties`。
- 变更代码一律写中文注释/Javadoc 说明意图，风格与现有代码一致（类、方法、关键分支）。
- 分页：API page 0-based；size 默认 20、上限 100，Controller 与 Store 双处裁剪；MyBatis-Plus `Page.current` 从 1 开始，Store 内转换 `page + 1`。
- 错误：客户端可见 message 不允许含 SQL、堆栈、内部类名或 Feed URL；Feed 失败映射 502。
- Feed 时间带时区偏移（如 `+11:00`），必须 `OffsetDateTime.parse(...).toInstant()`，不可直接用 `Instant.parse`。

## 反模式（禁止）

- Controller/Service 内 `new WebClient` 或拼接 Feed URL。
- Service 循环内逐条访问 Mapper（N+1）；先批量查询再内存组装。
- 业务 Service 绕过 `SyncPersistenceStore` 直连 `sync_record`/`feed_raw_payload` Mapper。
- `this.syncSchedule()` 式自调用绕过 `@Transactional` 代理；需要独立事务时经 `@Lazy` 注入的自身代理调用。
- 把整段 `syncCurrent` 包进单个大事务；HTTP 拉取不放事务里。
- 把每个 RaceId 当作独立 round；须按 MeetingId（缺省回退 MeetingNumber）分组。
- DTO 暴露 `rawJson`（仅实体与管理端 `RawPayloadDto` 可用；快照 DTO 至多 `hasRawJson` 布尔）。
- 定时任务用 `fixedRate`；应使用 `fixedDelay` 防止重叠，并捕获运行时异常保护调度线程。
- 日志打印原始 Feed JSON、query string 或 `application.yaml` 中的连接信息。
- 为迁就测试而改 SQL 脚本或 `pom.xml`（现有测试注释明确禁止）。

## 命令（必须在 `f1aipredict/` 模块根执行）

Maven wrapper 不在仓库根，先进入 `f1aipredict/`：

```bash
./mvnw compile
./mvnw test
./mvnw clean verify
./mvnw spring-boot:run
```

`spring-boot:run` 依赖 `application.yaml` 指向的真实 MySQL，本地无库会启动失败。普通单测/契约测试不连库：`src/test/resources/application-test.yaml` 默认关闭调度器；`@SpringBootTest` 冒烟测试排除 DataSource 并 mock Feed 客户端；持久化契约测试为纯反射。

## Gotchas

- `application.yaml` 内含真实 MySQL 地址与账号口令，属敏感信息；不得复制进文档、日志、新代码或任何提交。
- Boot 4.1.1 内置 Jackson 3（`tools.jackson.*`）。需要直接 `new ObjectMapper()` 或做序列化断言时用 `tools.jackson.databind.ObjectMapper`（自带 java.time 支持）。API DTO 与 Feed 模型的注解包保持现状 `com.fasterxml.jackson.annotation.*`。
- classpath 无 springdoc-openapi，v1 代码不添加 `@Operation` 等 OpenAPI 注解。
- `F1PredictFeedClientTest` 用 MockWebServer 打桩，禁止向真实 f1predict 站点发请求。
- 设计文档（如 `java-api-design.md`）部分是前瞻规划；与代码冲突时以代码、契约测试为准。
