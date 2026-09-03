# sync 子域知识库

生成日期：2026-09-03。本文件属未提交工作区变更，任何情况下不执行 git 提交。
范围仅 `com.lbz.f1aipredict.sync`。全局 DTO/实体/分页/错误规范见模块根 `AGENTS.md`，本文件只补充同步子域特有语义。

## 包地图与调用路径

- `controller/` 管理端 REST；`schedule/` 定时任务；`service/`+`service/impl/` 编排；`client/` Feed 客户端；`config/` URL 配置；`store/` 持久化入口；`mapper/`+`entity/` 审计两表；`feed/` Feed JSON 模型；`dto/` 对外 DTO；`util/`；根 `FeedSyncException`。
- HTTP 路径：`SyncAdminController`（`/api/v1/admin/sync/*`）→ `FeedSyncService` 接口 → `FeedSyncServiceImpl`。Controller 只返回 DTO，不建 WebClient、不触 Mapper。
- 定时路径：`HourlyQuestionSyncScheduler`（`@Scheduled fixedDelay`）每轮只调一次 `syncCurrent()`，自带 requestId 与运行时异常捕获，不打垮调度线程。
- 编排内部分工：拉取走 `F1PredictFeedClient`；审计/留档走 `SyncPersistenceStore`；season/question 业务表直写各自域 Mapper。
- 当前 season/question 业务表的写入集中在 sync；season 仅提供 Mapper/Entity，question 另有独立只读查询链路，不要在 sync 内复制其查询 Service。

## 复用边界

- `F1PredictFeedClient` 是全仓库唯一 Feed 客户端，禁止 `new WebClient`；URL 唯一来源 `F1PredictFeedProperties`（`f1predict.feed.*`），不硬编码。
- client 目前只实现 schedule/limits/questions 三条拉取；`mixApiPath`/`webConfigPath` 尚无消费方，不得臆造调用。
- `SyncPersistenceStore` 是 `sync_record`/`feed_raw_payload` 唯一持久化入口，业务 Service 禁止绕过它直连这两个 Mapper。
- 已有 Service/Store/Client 能覆盖的职责不另起链路；子方法可复用先抽 private 共用（如 `fetchAndArchiveLimits` 供 `syncCurrent`/`syncLimits` 共用）。

## 编排、事务与失败延续

- `syncCurrent()` 固定顺序 limits → schedule → questions，禁止并行或重排。
- limits 拉取失败、JSON 畸形或解析不到 GamedayId：整体 CURRENT FAILED，不继续 schedule/questions。
- schedule 失败后仍继续 questions；两子结果均 SUCCESS 或 SKIPPED_UNCHANGED 整体才算 SUCCESS，否则 FAILED 并在 errorMessage 汇总子错误。
- `syncSchedule()`/`syncQuestions()` 各自 `@Transactional`（业务 upsert 与 SUCCESS 审计同事务）；`syncCurrent()` 无整体大事务，HTTP 拉取永不入事务。
- `syncCurrent()` 内子同步必须经 `@Lazy` 注入的 `self` 代理调用；`this.xxx()` 自调用绕过代理使事务失效。生产构造器注入 `@Lazy FeedSyncService self`，测试构造器 `self=this`，spy 场景调 `setSelfForTests()` 绑回 spy。

## 幂等、留档与审计流（每种源一致）

1. client 拉取整包原始 JSON（String）。
2. `FeedSyncUtils.sha256Hex()` 算整包 SHA-256（空串也有确定哈希）。
3. `saveRawPayload()` 写 `feed_raw_payload`，`uk_payload_hash` 冲突回读已有主键；HTTP 失败无 hash 不留档。
4. `findLatestUnchanged(sourceType, hash)` 命中则记 SKIPPED_UNCHANGED 审计并跳过业务写入。
5. 未命中则业务 upsert 后记 SUCCESS；畸形 JSON（JacksonException/IllegalArgumentException）已留档但不写业务表，记 FAILED；其余运行时异常上抛让事务回滚。
- `sync_record` 每轮必新插，纯审计不去重；判重只依赖 payload 哈希。FAILED 记录 contentHash/payloadId 可空。
- 状态串：SUCCESS / SKIPPED_UNCHANGED / FAILED；sourceType：SCHEDULE / LIMITS / QUESTIONS，CURRENT 仅出现在返回 DTO，不落库。
- 日志与对外 message 不得含 raw JSON、响应体、Feed URL；`sourceUrl` 只进库与审计。

## Feed 模型与 DTO 注解分界

- `feed/*` 面向上游读取：`@JsonProperty` 用上游真实键（PascalCase `GamedayId`/`MeetingId`，混合键 `FOMMEETINGSESSIONKEY`），类级 `@JsonIgnoreProperties(ignoreUnknown = true)`。键名五花八门，禁止按 Java 字段名反推。
- `dto/*` 面向客户端：每字段显式 `@JsonProperty("camelCase")`，值等于 Java 字段名（契约测试反射强制）。两类方向相反，勿混用。
- 模块内自建解析用 `tools.jackson.databind.ObjectMapper`（Jackson 3）；DTO/feed 注解包保持 `com.fasterxml.jackson.annotation.*`。

## 映射规则

- raceday 按同赛季内 MeetingId 分组，缺失回退 MeetingNumber；`round_number` 取 MeetingNumber；RaceId 只溯源，禁止当分组键。
- 空白或不可解析的 `FOMMEETINGSESSIONKEY` 会跳过该 session；数值 `0` 不按空值特殊处理。
- Feed 时间带偏移（如 `SessionStartDateISO8601`），一律 `OffsetDateTime.parse(...).toInstant()`，`Instant.parse` 会失败；round 起止日期取该组 session 的 UTC LocalDate min/max。
- questions 先经 `meeting_session.gameday_id` 找 round_id，无对应记录整批跳过但仍记 SUCCESS；单题按 `(gamedayId, sourceQuestionId)` 幂等。
- 单题变化：新题插 INITIAL 快照（snapshot_no 从 1 起）并回写 `latest_snapshot_id`；内容 hash 变化追加 CHANGED 快照并更新现状；未变只刷 `last_synced_at`。选项 option_no 从 0 起。
- 上游 Answer 是官方答案：本子域不解析、不持久化（scoring 域未落地）；写入选项 `is_answer` 恒为 false。
- limits 解析 GamedayId：优先 `Data.Value.GamedayId`，回退根级 `GamedayId`/`CurrentGamedayId`/`currentGamedayId`/`gamedayId`；RaceId 不当比赛日。

## 分页与查询

- `GET /api/v1/admin/sync/records`：page 0-based，size 默认 20、上限 100；`SyncRecordQuery.clampPaging()` 在 Controller 与 Store 双处调用；Store 内 MyBatis-Plus `Page.current = page + 1`。
- `GET /api/v1/admin/sync/raw-payloads/{payloadId}`：缺失抛 `ResourceNotFoundException` → 404；rawJson 仅此管理端 DTO 可暴露。

## 错误语义

- client 将 HTTP 错误/网络失败统一转 `FeedSyncException`（message 只含安全摘要与状态码，不含响应体/堆栈/URL）；Service 捕获后写 FAILED 审计并返回结果 DTO，不上抛给 Controller。
- `FeedSyncException` 的对外 HTTP 映射（502 FEED_SYNC_ERROR）在 common 全局异常完成，本包不绑状态码。

## 测试分布（镜像包，详见模块级测试约定）

- `client/F1PredictFeedClientTest`：MockWebServer 打桩，禁止向真实站点发请求。
- `controller/`、`store/`、`config/`、`schedule/`（含调度 enabled/disabled 装配）、`service/`（Schedule/Questions/Current 分文件）。
- service 测试用 9 参构造器 + Mockito/spy（`setSelfForTests`）；`mapper/` 与 `season/mapper`、`question/mapper` 的契约测试守护实体↔DDL 1:1，改实体必须同步 SQL 脚本。
