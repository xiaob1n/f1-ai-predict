# F1 AI Predict Java 端接口设计

> 本文档依据以下资料整理：
> - `f1-ai-predict-feasibility-analysis.md`（项目可行性分析，2026-08-27）
> - `sql/002_season_round.sql`、`sql/003_question.sql`、`sql/004_prediction.sql`、`sql/005_answer_scoring.sql`、`sql/006_sync.sql`
>
> 当前实现状态：`season`、`question`、`sync`、`common`、`config` 已有源码和测试；`prediction` 已实现预测批次/任务的创建与只读查询，创建时原子写入 `PENDING` 批次、任务及请求 outbox。可选启用的 Java v2 请求发布器已实现；预测结果消费、锁定、`scoring`、`statistics` 等后续链路尚未实现。下文以 `[x]` 标记已完成的设计项，未标记项仍属于规划或部分实现。

## 一、接口分层

采用按业务领域划分的包结构：

```text
com.lbz.f1aipredict
├── season
│   ├── controller
│   ├── service
│   ├── mapper
│   ├── entity
│   └── dto
├── question
├── prediction
├── scoring
├── sync
├── statistics
├── messaging
└── common
```

Java 是业务数据唯一管理方；Python 不直接访问 MySQL。预测请求已实现 outbox 入库和可选启用的 RabbitMQ v2 发布器；Python 结果消息的 Java 消费与结果落库尚未实现。

## 二、REST 接口

### 1. 赛季、分站和 Session 接口

对应数据表：`season`、`round`、`meeting_session`

#### [x] 1.1 查询当前赛季

```http
GET /api/v1/seasons/current
```

返回当前进行中的赛季；如果没有进行中的赛季，可返回最近的即将开始赛季。

```json
{
  "id": 1,
  "year": 2026,
  "name": "2026 FIA Formula One World Championship",
  "status": "IN_PROGRESS",
  "startDate": "2026-03-06",
  "endDate": "2026-12-06"
}
```

#### [x] 1.2 查询赛季列表

```http
GET /api/v1/seasons
```

支持参数：

```text
status=IN_PROGRESS
page=0
size=20
```

#### [x] 1.3 查询赛季下的分站

```http
GET /api/v1/seasons/{seasonId}/rounds
```

#### [x] 1.4 查询分站详情

```http
GET /api/v1/rounds/{roundId}
```

#### 1.5 查询当前分站（基础查询已实现，预测/评分状态未实现）

- [x] 当前分站及所属赛季、Session、gamedayId 基础查询已实现。
- [ ] 预测截止时间、创建许可、锁定与评分状态尚未返回。

```http
GET /api/v1/rounds/current
```

返回内容建议包括：

- 分站基本信息
- 所属赛季
- 当前分站状态
- Session 列表
- gamedayId
- 预测截止时间
- 是否允许创建预测任务
- 是否已经锁定预测
- 是否已经完成评分

#### [x] 1.6 查询分站下的 Session

```http
GET /api/v1/rounds/{roundId}/sessions
```

#### [x] 1.7 查询 Session 详情

```http
GET /api/v1/sessions/{sessionId}
```

支持按照 OpenF1 标识查询：

```http
GET /api/v1/sessions/by-session-key/{sessionKey}
GET /api/v1/sessions/by-meeting-key/{meetingKey}
```

### 2. 题目和选项接口

对应数据表：`question`、`question_snapshot`、`question_option`

#### [x] 2.1 查询分站题目

```http
GET /api/v1/rounds/{roundId}/questions
```

支持参数：

```text
status=OPEN
gamedayId=100
includeOptions=true
snapshotId=10
```

返回示例：

```json
{
  "questionId": 1,
  "gamedayId": 100,
  "sourceQuestionId": 476,
  "questionNo": 1,
  "questionText": "Who will qualify on pole?",
  "subText": null,
  "questionType": "SINGLE",
  "optionTemplateId": 1,
  "choiceLimit": 1,
  "status": "OPEN",
  "latestSnapshotId": 12,
  "options": [
    {
      "optionId": 117,
      "optionNo": 0,
      "optionText": "Driver A",
      "points": 10,
      "chance": 0.73
    }
  ]
}
```

#### [x] 2.2 查询题目详情

```http
GET /api/v1/questions/{questionId}
```

#### [x] 2.3 查询题目指定快照

```http
GET /api/v1/questions/{questionId}/snapshots/{snapshotId}
```

#### [x] 2.4 查询题目快照历史

```http
GET /api/v1/questions/{questionId}/snapshots
```

返回：快照编号、内容哈希、快照原因、创建时间、选项列表、原始 Feed JSON 是否存在。

#### 2.5 查询题目当前答案状态

```http
GET /api/v1/questions/{questionId}/answer
```

说明：

- 赛前只返回 `answerAvailable=false`
- 官方答案同步后返回结构化答案
- 不建议由前端直接修改答案

> 题目快照创建应由 Feed 同步服务内部调用，不建议开放普通用户直接创建快照的接口。

### 3. Feed 同步接口（管理端）

对应数据表：`sync_record`、`feed_raw_payload`、`season`、`round`、`meeting_session`、`question`、`question_snapshot`、`question_option`

这些接口属于管理接口，首版可通过 Swagger 使用，不需要单独开发管理后台。

#### [x] 3.1 同步赛程 Feed

```http
POST /api/v1/admin/sync/schedule
```

处理内容：

1. 请求 `raceday_en.json`
2. 保存原始响应
3. 计算 `contentHash`
4. 幂等更新 `season`
5. 幂等更新 `round`
6. 幂等更新 `meeting_session`
7. 写入 `sync_record`

#### [x] 3.2 同步当前限制配置

```http
POST /api/v1/admin/sync/limits
```

#### [x] 3.3 同步指定轮次题目

```http
POST /api/v1/admin/sync/questions/{gamedayId}
```

处理内容：

1. 请求题目 Feed
2. 保存 `feed_raw_payload`
3. 根据 `contentHash` 判断是否发生变化
4. 新增或更新 `question`
5. 题目内容变化时新增 `question_snapshot`
6. 保存该快照下的 `question_option`
7. 写入 `sync_record`

#### [x] 3.4 同步全部当前 Feed

```http
POST /api/v1/admin/sync/current
```

#### [x] 3.5 查询同步记录

```http
GET /api/v1/admin/sync/records
```

支持参数：

```text
sourceType=SCHEDULE|QUESTIONS|LIMITS|MIXAPI|WEB_CONFIG
gamedayId=100
status=FAILED
page=0
size=20
```

#### [x] 3.6 查询原始 Feed 响应

```http
GET /api/v1/admin/sync/raw-payloads/{payloadId}
```

建议只允许管理端调用，因为原始 JSON 可能体积较大。

## 三、预测接口

对应数据表：`prediction_batch`、`prediction_job`、`prediction_result`、`prediction_result_item`、`prediction_evidence`

### [x] 4.1 创建整轮预测批次

```http
POST /api/v1/rounds/{roundId}/prediction-batches
```

请求示例：

```json
{
  "questionIds": [1, 2, 3, 4],
  "dataCutoff": "2026-08-28T10:00:00Z",
  "featureVersion": "feature-v1",
  "modelVersion": "qwen3-8b-lora-v1",
  "promptVersion": "f1-race-v1"
}
```

服务端处理：

1. 校验分站状态
2. 校验题目属于当前分站
3. 固定每道题使用的 `questionSnapshotId`
4. 创建 `prediction_batch`
5. 为每道题创建一条 `prediction_job`
6. 生成唯一 `predictionJobId`

当前 Java 已完成以上六步：批次和任务均以 `PENDING` 状态原子落库，并在同一事务中为每个任务写入请求 outbox，成功返回 HTTP 201。
启用 `f1.prediction.outbox.enabled` 后，独立发布器异步发送 v2 请求并等待 Broker 确认；将批次更新为 `TASK_CREATED`、消费预测结果尚未实现。不应从 201 响应推断消息已经投递或预测完成。

返回示例：

```json
{
  "batchId": 1,
  "roundId": 10,
  "status": "PENDING",
  "questionCount": 4,
  "jobIds": [
    "a7c1...",
    "b8d2..."
  ]
}
```

建议增加一个"整轮自动获取全部题目"的快捷参数：

```json
{
  "allOpenQuestions": true
}
```

但不能同时传 `allOpenQuestions=true` 和 `questionIds`。

### [x] 4.2 查询预测批次

```http
GET /api/v1/prediction-batches/{batchId}
```

当前 Java 接口返回 `PredictionBatchDetailDto` 字段：`batchId`、`roundId`、`batchNo`、`status`、`questionCount`、`dataCutoff`、`createdAt`、`updatedAt`，以及嵌套的 `statusCounts`（`pendingCount`、`runningCount`、`retryingCount`、`succeededCount`、`failedCount`、`deadLetterCount`、`unknownCount`）。

当前 DTO 不包含锁定时间或扩展分站信息；也不返回尚未实现的结果或评分信息。

### [x] 4.3 查询批次任务列表

```http
GET /api/v1/prediction-batches/{batchId}/jobs
```

支持 `status`、0-based `page`、`size` 查询参数；`size` 在 Controller 与 Service 边界裁剪，最大为 100。

### [x] 4.4 查询单个预测任务

```http
GET /api/v1/prediction-jobs/{predictionJobId}
```

路径参数使用公开业务键 `predictionJobId`，不暴露数据库自增主键。

返回示例：

```json
{
  "predictionJobId": "a7c1...",
  "questionId": 1,
  "status": "SUCCEEDED",
  "retryCount": 0,
  "workerNode": "home-gpu-01",
  "dataCutoff": "2026-08-28T10:00:00Z",
  "modelVersion": "qwen3-8b-lora-v1",
  "createdAt": "2026-08-28T10:01:00Z",
  "completedAt": "2026-08-28T10:03:20Z"
}
```

### 4.5 查询预测结果

```http
GET /api/v1/prediction-jobs/{predictionJobId}/result
```

返回：题目和题目快照、选中的选项、排序位置、置信度、分析摘要、证据、实际数据截止时间、模型版本、Agent 版本、Prompt 版本、生成时间、锁定时间。

### 4.6 查询批次全部预测结果

```http
GET /api/v1/prediction-batches/{batchId}/results
```

## 四、预测锁定接口

SQL 当前只有 `prediction_job.locked_at`、`prediction_result.locked_at`，因此可以先采用服务接口实现锁定。

### 5.1 锁定预测批次

```http
POST /api/v1/prediction-batches/{batchId}/lock
```

处理规则：

- 当前时间必须早于预测截止时间
- 批次不能是 `FAILED` 或 `DEAD_LETTER`
- 锁定后禁止修改预测结果
- 锁定操作必须幂等
- 所有任务和结果写入 `locked_at`
- 锁定后不允许重新发布同一任务

### 5.2 查询预测锁定状态

```http
GET /api/v1/prediction-batches/{batchId}/lock-status
```

> 当前 SQL 没有明确的预测截止时间字段，建议增加：
>
> ```sql
> ALTER TABLE prediction_batch
> ADD COLUMN prediction_deadline DATETIME(3) NULL
> COMMENT '预测截止时间(UTC)';
> ```
>
> 或者从 `round` / `meeting_session` 通过业务规则计算，但不建议每次动态推导。

## 五、官方答案和评分接口

对应数据表：`official_answer`、`scoring_detail`、`batch_total_score`

### 6.0 时间边界与证明等级（已实现）

本节描述已落地的持久化与 Service 层时间语义（答案同步、人工修订、批次锁定、结果受理、评分写入 `detail_json`）。目前没有答案或评分的 REST 读取接口，下文 DTO 要求针对后续接口。SQL 列注释与本节冲突时，以本节以及 `OfficialAnswer.java`、`OfficialAnswerTransactionExecutor`、`ScoringServiceImpl` 的 Javadoc 为准。

证明等级的含义：

- **系统证明**：由本服务 Java 时钟在受控事务内写入，可用于本系统内的先后判定。
- **业务输入**：操作者在请求中给定的值，不是时钟读数。
- **Worker 声明**：Python Worker 在消息中给出，Java 只做一致性校验，不能证明真实性。
- **人工声明**：通过人工修订写入，没有独立来源记录。
- **无证明力**：可以展示，但不能用于任何“早于/晚于官方事件”的推断。

#### 时间字段一览

| 字段 | 存储列与精度 | 时钟来源 | 证明等级 | 可下结论 | 不可下结论 | 状态 |
|---|---|---|---|---|---|---|
| `predictionDeadline` | `prediction_batch.prediction_deadline` DATETIME(3) NULL（009） | 无时钟：创建批次请求给定，必须为毫秒精度，且不得早于 `dataCutoff`；为空时批次不能锁定，因此不能评分 | 业务输入 | 迟到边界：`receivedAt > predictionDeadline` 为 LATE，等于截止仍有效 | 不代表官方截止、发车时间或任何赛事时间；本系统不核对它与赛程的关系 | 已实现 |
| `lockedAt` | `prediction_batch.locked_at` DATETIME(3)（009）。`prediction_job.locked_at` / `prediction_result.locked_at`（004）目前没有写入路径，恒为 NULL | Java `Instant.now().truncatedTo(MILLIS)`（管理端锁定接口），写入前已截断，不会被舍入 | 系统证明 | 锁定时间严格晚于截止（Service 校验，SQL 条件 `prediction_deadline < lockTime` 再守一次）；只记录首次锁定 | 不说明官方答案何时产生；job/result 级 `locked_at` 不可作为依据 | 已实现 |
| `receivedAt` | `prediction_outcome_receipt.received_at` DATETIME(6)（008） | Java `Instant.now()`，在持有批次行锁之后取得；DATETIME(6) 舍入误差不超过 0.5µs | 系统证明 | 结果首次被本系统受理的时间；用它与 `predictionDeadline` 判断迟到 | 不代表模型生成时间（`generatedAt` 由 Worker 声明）；不代表结果行 `created_at` | 已实现 |
| `dataCutoff` / `sourceDataCutoff` | `prediction_batch.data_cutoff`、`prediction_job.data_cutoff` DATETIME(3)（004）；`prediction_result.source_data_cutoff` DATETIME(6)（008） | 无时钟：`dataCutoff` 由请求给定且必须为毫秒精度；`sourceDataCutoff` 由 Worker 回传，Java 要求它与冻结请求中的截止一致 | 业务输入 / Worker 声明 | 特征可见性的声明边界；Java 拒绝 `firstSeenAt` 或 `eventTime` 晚于 `sourceDataCutoff` 的证据 | 不是预测业务截止，不能代替 `predictionDeadline`；不证明 Worker 实际没有读取更晚的数据 | 已实现 |
| `evidence.firstSeenAt` | `prediction_evidence.first_seen_at` DATETIME(6)（008） | Worker 消息字段（必填），值来自 Worker 侧数据行 | Worker 声明 | 只能说明 Worker 声称在该时间之前已见到证据，且不晚于 `sourceDataCutoff` | 不证明证据真实首次可见时间；不能代替证据发布时间 | 已实现（真实来源可信度待核实） |
| `evidence.publishedAt` | `prediction_evidence.published_at` DATETIME(6) NULL（008 已从 004 的 DATETIME(3) 修改） | 无：v2 结果契约的 evidence 字段集合不含 `publishedAt`（出现未知字段即拒收），Java 落库恒写 NULL | 无证明力 | 无 | 恒为 NULL；不能按 004 注释理解为“已校验早于 `source_data_cutoff`” | 已实现（恒 NULL） |
| `official_answer.createdAt` | `official_answer.created_at` DATETIME(3) NOT NULL（005） | Java `Instant.now()`，在答案同步事务内读取：先在事务外拉取 Feed，再写入整包原文、对当前答案行加行锁（`SELECT ... FOR UPDATE`），之后才读时钟，因此晚于实际拉取时刻，中间隔着解析与锁等待，差值**没有上界**。首次插入当前答案行时显式写入（不走库表 DEFAULT），写入前**未截断**，MySQL 会把超出的小数位四舍五入到毫秒；±0.5ms 只指存储值相对这次时钟读数的舍入偏差 | 系统证明（仅限“答案同步路径写入当前答案行”） | 答案同步路径写入**当前答案行**的时间（下文简称“本系统观察到当前答案行”）。Feed 修订内容不改变它；撤销会删除该行，重新公布时插入新行并重置它，更早的值见 `official_answer_history.created_at` | 不是官方公布时间；不是当前内容首次出现的时间（Feed 修订后内容可能更晚才出现，见 `syncedAt`）；不是系统任何组件首次拿到答案的时间：答案同步与题目同步读取同一个 `fetchQuestions` 响应体，每小时定时的题目同步可能早几个小时就已把含答案的整包原文写入 `feed_raw_payload`（`source_type=QUESTIONS`），而答案同步没有定时入口 | 已实现 |
| `official_answer.publishedAt` | `official_answer.published_at` DATETIME(3) NULL（005） | Feed 同步恒写 NULL（Feed 不提供）；人工修订仅在请求显式给出时写入（不校验毫秒精度，存储时会舍入）；不会用当前时间回填 | 人工声明（非 NULL 时） | 非 NULL 时只表示人工声明的公布时间 | NULL 不是“尚未公布”；非 NULL 也不是系统证明 | 已实现 |
| `official_answer.syncedAt` | `official_answer.synced_at` DATETIME(3) NOT NULL（005） | Java `Instant.now()`，写入前未截断 | 无证明力（相对官方事件） | 当前态内容最近一次被写入的时间（Feed 内容变化或人工修订）；哈希不变的重复同步不刷新 | 不能代替官方公布时间；不能代替首次观察时间 | 已实现 |
| `sync_record.synced_at` | `sync_record.synced_at` DATETIME(3)（006） | Java `Instant.now()`（`SyncPersistenceStore` 未显式设置时补齐） | 无证明力（相对官方事件） | 某次同步审计记录的写入时间 | 不能推断答案何时公布或何时变化 | 已实现 |
| `question.firstSeenAt` / `question.lastSyncedAt` | `question.first_seen_at` / `question.last_synced_at` DATETIME(3)（003） | Java `Instant.now()`，写入前未截断。`firstSeenAt` 只在首次插入时写入；`lastSyncedAt` 在每次同步到该题时刷新（内容未变也刷新） | 系统证明（仅限“本系统观察到题目”） | 本系统首次、最近一次从 Feed 看到题目的时间 | 与答案时间无关，不能用于答案时间证明 | 已实现 |
| `scoredAt` | `scoring_detail.scored_at` DATETIME(3) NULL（005） | Java `Instant.now().truncatedTo(MILLIS)`，同一批次所有行使用同一时刻 | 无证明力（相对官方事件） | 本行被计分的时间；只有 SCORED_CORRECT / SCORED_ZERO 行有值，其余为 NULL | 不能代替答案公布或观察时间；重算后它会变化 | 已实现 |

`updated_at` 一类列有的由 Java 写入，有的由数据库 `UTC_TIMESTAMP(3)` 或 `ON UPDATE CURRENT_TIMESTAMP(3)` 写入，都不参与时间证明。历史表的 `archived_at`（010/011）只表示归档时刻，同样不参与。

#### 推导规则

1. **最强可证明链**：`receivedAt <= predictionDeadline < lockedAt`。
   - 锁定严格晚于截止；`receivedAt` 等于截止的结果仍有效，严格晚于截止才是 LATE。
   - 结果受理先取批次行锁，再读取接收时间，因此准时结果一定在锁定提交之前提交；锁定之后受理的结果 `receivedAt` 必然晚于截止，只记为 LATE 并保留审计。
   - 该链依赖单一可信、不回拨的服务端时钟。多实例部署时，各实例必须通过 NTP 同步。
2. **`receivedAt < answer.createdAt`** 只能说明“结果早于答案同步路径写入当前答案行”，不能说明“早于官方公布”，也不能说明早于系统任何组件首次拿到答案（见上表 `official_answer.createdAt` 行）。`created_at` 与 `received_at` 都由 Java 时钟写入，不涉及 MySQL 时钟；多实例部署仍依赖 NTP。
3. **`publishedAt` 为 NULL 时，“预测早于官方公布”一律为 UNKNOWN。** 不得用 `createdAt`、`syncedAt`、`updatedAt`、历史表修订时间或 `scoredAt` 代替。
4. **非 NULL 的 `publishedAt` 目前只能来自人工修订**（Feed 同步恒写 NULL），只算人工声明。基于它的结论必须写成“早于人工声明的公布时间”。

对外措辞（`publishedAt = null`）：

> publishedAt 为 null 表示官方公布时间未知（Feed 不提供），不是“尚未公布”，也不能视为早于或晚于任何时间。判断预测是否早于官方公布请读 answerTimingProof，不要自行比较 createdAt/syncedAt。

#### `scoring_detail.detail_json` 中的证明字段

评分（含显式重算）时，每行 `detail_json` 根据当时的当前态答案写入以下字段。它们只陈述时间先后，不影响 `score_status` 和得分。

| 键 | 写入条件 | 取值 |
|---|---|---|
| `answerFirstObservedAt` | 存在当前态答案时写入 | `official_answer.created_at` 的 ISO-8601 UTC 字符串，表示本系统首次观察到当前答案行的时间 |
| `answerPublishedAt` | 存在当前态答案时，键必须存在 | `published_at` 的 ISO 字符串；为 NULL 时写显式 JSON `null`（不省略键） |
| `answerTimingProof` | 每行必写 | 见下表 |

`answerTimingProof` 的取值（按顺序判定）：

| 取值 | 条件 | 含义 |
|---|---|---|
| `UNKNOWN` | 无当前态答案；或无 `receivedAt`；或 `publishedAt` 与 `createdAt` 都为 NULL；或落在下文的观察时间精度带内 | 无法证明先后 |
| `BEFORE_DECLARED_PUBLICATION` | `publishedAt != null` 且 `receivedAt < publishedAt` | 接收早于**人工声明**的公布时间，不是系统证明 |
| `NOT_BEFORE_DECLARED_PUBLICATION` | `publishedAt != null` 且 `receivedAt >= publishedAt` | 接收不早于人工声明的公布时间 |
| `BEFORE_SYSTEM_OBSERVATION_ONLY` | `publishedAt == null` 且 `receivedAt < truncate_ms(createdAt) - 1ms` | 只能证明接收早于答案同步路径写入当前答案行，不证明早于官方公布，也不证明早于系统任何组件首次拿到答案（定时题目同步可能已把含答案的同端点原文写入 `feed_raw_payload`） |
| `NOT_BEFORE_SYSTEM_OBSERVATION` | `publishedAt == null` 且 `receivedAt >= createdAt` | 接收不早于本系统观察到当前答案行 |

精度余量：`created_at` 由未截断的 `Instant.now()` 写入 DATETIME(3)，MySQL 四舍五入（已在 MySQL 实测：亚毫秒部分为 600µs 时进位到下一毫秒）可能使存储值比这次时钟读数晚至多 0.5ms（这次时钟读数本身就晚于实际拉取 Feed 的时刻，且差值没有上界，见上表；余量只覆盖舍入，不覆盖这段延迟），因此 `BEFORE_SYSTEM_OBSERVATION_ONLY` 要求 `receivedAt` 早于“截断到毫秒的观察时间减 1ms”。落在 `[createdAt - 1ms, createdAt)` 之间的记为 `UNKNOWN`。`NOT_BEFORE_SYSTEM_OBSERVATION` 不加余量：它是否定性结论，在舍入的 0.5ms 内可能实际略早于这次时钟读数，但不会因此得出“早于”的结论。人工声明的 `publishedAt` 按存储值直接比较，不加余量。

不存在 `BEFORE_OFFICIAL_PUBLICATION` 之类暗示系统已证明的取值，读者也不应自行推导出这类结论。

旧评分行（D8 之前写入）缺少上述键时，读者必须视为 `answerTimingProof = UNKNOWN`，不得用同一 `detail_json` 中的 `receivedAt` 与当前答案表的时间自行补算。显式重算（`rescoreBatch`）会先归档旧行，再写入带这些键的新行。证明字段反映的是评分时刻的答案状态：如果之后人工修订了 `publishedAt`，只有重算后才会更新。

#### 已知缺口

- `AnswerUpdateRequest.publishedAt` 为 null 时表示“保留原值”，因此人工修订**无法把已写入的 `publishedAt` 清空为 NULL**。
- 下一次 Feed 内容变化（哈希改变）会把当前态 `publishedAt` 重置为 NULL，旧值（可能来自人工）只保留在 `official_answer_history`。哈希不变的重复同步不会重置。
- 人工 `publishedAt` 的来源、依据和操作者没有单独记录。只能从“Feed 恒写 NULL”推断出非 NULL 值来自人工；该值也不校验毫秒精度。
- 答案被 Feed 撤销时，当前行先归档（`revision_reason = FEED_REVOKED`，与 010 列注释“FEED 或 MANUAL”不一致，以代码为准）再删除；重新公布时插入新行，`created_at` 被重置。因此 `answerFirstObservedAt` 只代表当前答案行，更早被撤销的答案的观察时间需要查 `official_answer_history.created_at`。
- `evidence.firstSeenAt` 只是 Worker 声明，真实来源的首次可见时间待核实；`evidence.publishedAt` 恒为 NULL。

#### 与已部署 SQL 注释的冲突

已部署脚本不修改。以下注释与实现语义冲突，以本节和 `OfficialAnswer.java` 的 Javadoc 为准：

- `sql/005_answer_scoring.sql:23`：`published_at` 列注释为 `'官方答案发布/同步时间(UTC)'`。实现中它只表示官方公布时间，Feed 同步恒写 NULL，绝不写入同步时间；同步时间见 `synced_at`。
- `sql/004_prediction.sql:116`：`prediction_evidence.published_at` 列注释为 `'证据发布时间(UTC)，须早于 source_data_cutoff'`。实现中该列恒为 NULL，截止约束改为校验 `first_seen_at` 与 `event_time`（见 `sql/008_prediction_outcome.sql:20` 的说明；008 第 24 行已把列注释改为 `'证据发布时间(UTC)'`）。

### 6.1 同步官方答案

```http
POST /api/v1/admin/sync/answers/{gamedayId}
```

处理内容：

1. 获取指定轮次官方答案
2. 保存原始 Feed JSON
3. 解析题目答案
4. 幂等写入 `official_answer`
5. 更新 `question_option.is_answer`
6. 触发评分
7. 更新批次和统计结果

### 6.2 查询官方答案

```http
GET /api/v1/questions/{questionId}/official-answer
```

建议在答案同步完成后才返回具体答案内容。

### 6.3 结算预测批次

```http
POST /api/v1/admin/prediction-batches/{batchId}/settle
```

处理内容：

- 校验批次已锁定
- 校验官方答案已经存在
- 根据题型调用对应评分策略
- 写入 `scoring_detail`
- 写入 `batch_total_score`
- 更新批次结算状态

### 6.4 查询批次总分

```http
GET /api/v1/prediction-batches/{batchId}/score
```

> 时间语义见 [6.0 时间边界与证明等级](#60-时间边界与证明等级已实现)。未来的评分与答案读取 DTO 必须暴露可为 null 的 `publishedAt` 和 `answerTimingProof`；若批次总分要汇总时间证明，只能按 `answerTimingProof` 取值计数，不得由 `createdAt`/`syncedAt` 自行推断。

返回示例：

```json
{
  "batchId": 1,
  "roundId": 10,
  "totalScore": 72.0,
  "maxScore": 100.0,
  "accuracyRate": 0.72,
  "typeStats": {
    "SINGLE": {
      "score": 30,
      "maxScore": 40
    },
    "RANKING": {
      "score": 42,
      "maxScore": 60
    }
  }
}
```

### 6.5 查询单题评分明细

```http
GET /api/v1/prediction-results/{resultId}/scoring-detail
```

> 时间语义见 [6.0 时间边界与证明等级](#60-时间边界与证明等级已实现)。未来的单题明细 DTO 必须原样暴露可为 null 的 `publishedAt`（即 `answerPublishedAt`）和 `answerTimingProof`；旧行缺少该键时返回 `UNKNOWN`。

返回：题型、实际得分、满分、部分得分、评分规则版本、逐选项或逐位置评分明细、评分时间。

## 六、统计接口

### 7.1 查询分站统计

```http
GET /api/v1/statistics/rounds/{roundId}
```

返回：分站总分、满分、准确率、各题型统计、预测任务成功率、合法 JSON 率、有效选项率、证据截止时间合规率。

### 7.2 查询赛季统计

```http
GET /api/v1/statistics/seasons/{seasonId}
```

### 7.3 查询全局统计

```http
GET /api/v1/statistics/overview
```

支持参数：

```text
seasonId
modelVersion
agentVersion
promptVersion
questionType
```

### 7.4 模型版本对比

```http
GET /api/v1/statistics/model-comparison
```

用于比较：固定 Prompt 基线、Qwen3-4B、Qwen3-8B、不同 Agent 版本、不同 Prompt 版本。

## 七、RabbitMQ 内部接口

RabbitMQ 消息不是对外 REST 接口，但 Java 端必须实现消息生产者和消费者。

### 8.1 Java 发布预测任务（v2 请求发布已实现，原 v1 设计未实现）

- [x] 创建批次时为每个任务原子写入不可变的 v2 请求 outbox；启用 `f1.prediction.outbox.enabled` 后，后台发布器向 durable direct 交换机 `f1.prediction.request.v2`、路由键 `prediction.request.v2` 发送持久消息，等待发布确认并处理失败重试。
- [ ] 以下 v1 `PredictionTaskPublisher` 接口及 `republish` 方法仍为原始设计，不代表当前代码的运行契约；结果消费见 8.2，尚未实现。

原 v1 设计建议交换机：`f1.prediction.exchange`，请求路由键：`prediction.request.v1`

消息结构：

```json
{
  "messageId": "msg-001",
  "predictionJobId": "job-001",
  "batchId": 1,
  "questionId": 1,
  "questionSnapshotId": 12,
  "question": {
    "questionText": "Who will qualify on pole?",
    "questionType": "SINGLE",
    "choiceLimit": 1,
    "options": []
  },
  "raceContext": {
    "seasonId": 1,
    "roundId": 10,
    "meetingKey": 1234,
    "sessionKey": 5678
  },
  "dataCutoff": "2026-08-28T10:00:00Z",
  "featureVersion": "feature-v1",
  "modelVersion": "qwen3-8b-lora-v1",
  "promptVersion": "f1-race-v1",
  "traceId": "trace-001",
  "schemaVersion": "1"
}
```

Java 需要提供：

```java
public interface PredictionTaskPublisher {

    // 创建任务消息并发布到 RabbitMQ
    PublishResult publish(PredictionTaskMessage message);

    // 重新发布失败或未确认的任务
    PublishResult republish(String predictionJobId);
}
```

### 8.2 Java 消费预测结果

结果路由键：`prediction.result.v1`

消息结构：

```json
{
  "messageId": "result-msg-001",
  "predictionJobId": "job-001",
  "questionId": 1,
  "selectedOptions": [
    {
      "optionId": 117,
      "position": 1
    }
  ],
  "confidence": 0.73,
  "reasoningSummary": "基于近期排位速度和赛道适配性。",
  "evidence": [
    {
      "source": "OpenF1",
      "url": "https://example.com/source",
      "publishedAt": "2026-08-20T10:00:00Z"
    }
  ],
  "sourceDataCutoff": "2026-08-28T09:50:00Z",
  "model": "Qwen3-8B",
  "agentVersion": "agent-1.0.0",
  "promptVersion": "f1-race-v1",
  "featureVersion": "feature-v1",
  "rawAgentResponse": {},
  "traceId": "trace-001",
  "schemaVersion": "1"
}
```

Java 需要提供：

```java
public interface PredictionResultConsumer {

    // 校验消息、执行幂等判断，并保存最终预测结果
    ConsumeResult consume(PredictionResultMessage message);
}
```

消费处理顺序：

1. 校验 `predictionJobId`
2. 校验任务是否存在
3. 校验题目和选项是否有效
4. 校验置信度范围 `0~1`
5. 校验 `sourceDataCutoff`
6. 校验证据发布时间不晚于数据截止时间
7. 检查任务是否已经成功处理
8. 保存 `prediction_result`
9. 保存 `prediction_result_item`
10. 保存 `prediction_evidence`
11. 更新 `prediction_job`
12. 业务事务成功后 ACK

## 八、Java Service 接口

```java
public interface SeasonService {
    SeasonDto getCurrentSeason();
    PageResult<SeasonDto> page(SeasonQuery query);
}
```

- [x] 已实现 `SeasonService` 的当前赛季查询和分页查询。

```java
public interface RoundService {
    RoundDto getCurrentRound();
    RoundDto getById(Long roundId);
    List<RoundDto> listBySeasonId(Long seasonId);
    List<MeetingSessionDto> listSessions(Long roundId);
}
```

- [x] 已实现 `RoundService` 的当前分站、按 ID、按赛季和 Session 查询。

```java
public interface QuestionService {
    List<QuestionDto> listByRoundId(Long roundId, QuestionQuery query);
    QuestionDetailDto getDetail(Long questionId);
    QuestionSnapshotDto getSnapshot(Long questionId, Long snapshotId);
    List<QuestionSnapshotDto> listSnapshots(Long questionId);
}
```

- [x] 已实现 `QuestionService` 的题目、详情、指定快照和快照历史查询。

```java
public interface FeedSyncService {
    SyncResult syncSchedule();
    SyncResult syncQuestions(Integer gamedayId);
    SyncResult syncLimits();
    SyncResult syncCurrent();
    PageResult<SyncRecordDto> pageRecords(SyncRecordQuery query);
}
```

- [x] 已实现 `FeedSyncService` 的赛程、限制配置、题目、当前 Feed 同步及同步记录分页查询；原始响应查询也已实现。

```java
public interface PredictionBatchService {
    PredictionBatchDto create(CreatePredictionBatchRequest request);
    PredictionBatchDetailDto getById(Long batchId);
    List<PredictionJobDto> listJobs(Long batchId);
    void lock(Long batchId);
    void retry(Long batchId);
}
```

- [x] 实际 `PredictionBatchService` 已实现批次创建与按 ID 查询，任务分页查询由 `PredictionJobService` 提供。
- [ ] 上述 `lock`、`retry` 为设计接口，尚未实现。

```java
public interface PredictionJobService {
    PredictionJobDto getByBusinessId(String predictionJobId);
    void markRunning(String predictionJobId, String workerNode);
    void markRetrying(String predictionJobId, String errorMessage);
    void markSucceeded(String predictionJobId);
    void markFailed(String predictionJobId, String errorMessage);
}
```

- [x] 实际 `PredictionJobService` 已实现按 `predictionJobId` 查询及按批次分页查询任务。
- [ ] 上述任务状态流转方法尚未实现。

```java
public interface PredictionResultService {
    PredictionResultDto getByJobId(String predictionJobId);
    void saveFromAgent(PredictionResultMessage message);
}
```

```java
public interface OfficialAnswerService {
    SyncResult syncAnswers(Integer gamedayId);
    OfficialAnswerDto getByQuestionId(Long questionId);
}
```

```java
public interface ScoringService {
    BatchScoreDto settle(Long batchId);
    ScoringDetailDto getDetail(Long resultId);
}
```

```java
public interface StatisticsService {
    RoundStatisticsDto getRoundStatistics(Long roundId);
    SeasonStatisticsDto getSeasonStatistics(Long seasonId);
    OverviewStatisticsDto getOverview(StatisticsQuery query);
    ModelComparisonDto compareModels(ModelComparisonQuery query);
}
```

## 九、建议补充或调整的 SQL

当前 SQL 基本覆盖业务主流程，但正式编码前建议处理以下问题。

### 1. 增加预测截止时间

当前表中没有明确的截止时间字段，只保存了 `data_cutoff`。建议增加：

```sql
ALTER TABLE prediction_batch
ADD COLUMN prediction_deadline DATETIME(3) NULL
COMMENT '本批次预测截止时间(UTC)';
```

### 2. 官方答案缺少结构化答案明细

`official_answer` 当前只有 `raw_json`、`answer_content`、`official_points`。排序题、多选题、数值题和区间题不适合只保存文本。建议增加答案明细表 `official_answer_item`：

| 字段 | 说明 |
| --- | --- |
| id | 主键 |
| official_answer_id | 关联官方答案 id |
| option_id | 选项 Id |
| position | 排序位置 |
| numeric_value | 数值题答案 |
| range_min | 区间下限 |
| range_max | 区间上限 |
| created_at | 创建时间 |

否则评分器只能依赖 `raw_json` 解析，长期维护风险较高。

### 3. `question_option.is_answer` 和官方答案表存在职责重叠

建议以 `official_answer` 和 `official_answer_item` 作为官方答案唯一来源，`question_option.is_answer` 仅作为查询优化字段，不作为评分依据。

### 4. 预测结果选项最好关联题目选项主键

当前 `prediction_result_item.option_id` 对应的是 Feed 的 `question_option.option_id`，不是数据库主键 `question_option.id`。由于不同题目可能出现相同的 Feed `option_id`，建议增加 `question_option_pk` 或直接将字段改成数据库内部选项主键，另外保留 `source_option_id`。

### 5. 批次状态建议增加结算状态

当前 `prediction_batch.status`：`PENDING / TASK_CREATED / PARTIAL / COMPLETED / FAILED`，建议补充：

```text
LOCKED / SETTLING / SETTLED
```

这样可以区分：预测生成完成、预测已锁定、已获取官方答案、已完成评分。

## 十、DTO 设计要求

后续编写尚未实现的预测结果 DTO 时，需要遵守以下规则：

```java
@Data
public class PredictionResultDto {

    @JsonProperty("predictionJobId")
    private String predictionJobId;

    @JsonProperty("questionId")
    private Long questionId;

    @JsonProperty("selectedOptions")
    private List<PredictionResultItemDto> selectedOptions;

    @JsonProperty("sourceDataCutoff")
    private Instant sourceDataCutoff;

    @JsonProperty("model")
    private String model;
}
```

要求：

- [x] 已实现 DTO 的所有字段增加 `@JsonProperty`，并使用 camelCase 名称
- [x] 已实现的创建预测批次请求体使用 `@Valid @RequestBody CreatePredictionBatchRequest`
- [x] 已实现时间字段统一使用 UTC
- [x] 已实现分页使用 0-based
- [x] 已实现分页 `size` 最大不超过 100
- [ ] `confidence` 限制在 `0~1`（预测结果 DTO 尚未实现）
- [ ] `predictionJobId` 必须作为 Java 结果消费幂等键（Python 请求 inbox 已按该键去重；Java 结果消费尚未实现）
- [x] 已实现接口不直接对外返回 Entity
- [ ] RabbitMQ、Redis 不作为最终业务数据源（相关运行链路尚未实现）

## 推荐的首版开发顺序

1. [x] 赛季、分站、Session 基础查询接口
2. [x] Feed 同步接口
3. [x] 题目和快照查询接口
4. [x] 预测批次创建和任务查询接口（创建时原子写入 `PENDING` 批次、任务及请求 outbox）
5. RabbitMQ 预测任务发布与结果消费（[x] 可选启用的 v2 请求发布；[ ] 结果消费）
6. 预测结果查询和锁定接口
7. 官方答案同步
8. 评分接口
9. 分站、赛季和模型统计接口
