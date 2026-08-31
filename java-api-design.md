# F1 AI Predict Java 端接口设计

> 本文档依据以下资料整理：
> - `f1-ai-predict-feasibility-analysis.md`（项目可行性分析，2026-08-27）
> - `sql/002_season_round.sql`、`sql/003_question.sql`、`sql/004_prediction.sql`、`sql/005_answer_scoring.sql`、`sql/006_sync.sql`
>
> 当前 `f1aipredict` 项目只有 Spring Boot 启动类和 MyBatis-Plus 配置，尚未存在可复用的 Controller、Service 或 Mapper，因此 Java 端接口按以下方案从零建设。

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

Java 是业务数据唯一管理方；Python 不直接访问 MySQL，预测任务通过 RabbitMQ 完成。

## 二、REST 接口

### 1. 赛季、分站和 Session 接口

对应数据表：`season`、`round`、`meeting_session`

#### 1.1 查询当前赛季

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

#### 1.2 查询赛季列表

```http
GET /api/v1/seasons
```

支持参数：

```text
status=IN_PROGRESS
page=0
size=20
```

#### 1.3 查询赛季下的分站

```http
GET /api/v1/seasons/{seasonId}/rounds
```

#### 1.4 查询分站详情

```http
GET /api/v1/rounds/{roundId}
```

#### 1.5 查询当前分站

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

#### 1.6 查询分站下的 Session

```http
GET /api/v1/rounds/{roundId}/sessions
```

#### 1.7 查询 Session 详情

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

#### 2.1 查询分站题目

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

#### 2.2 查询题目详情

```http
GET /api/v1/questions/{questionId}
```

#### 2.3 查询题目指定快照

```http
GET /api/v1/questions/{questionId}/snapshots/{snapshotId}
```

#### 2.4 查询题目快照历史

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

#### 3.1 同步赛程 Feed

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

#### 3.2 同步当前限制配置

```http
POST /api/v1/admin/sync/limits
```

#### 3.3 同步指定轮次题目

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

#### 3.4 同步全部当前 Feed

```http
POST /api/v1/admin/sync/current
```

#### 3.5 查询同步记录

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

#### 3.6 查询原始 Feed 响应

```http
GET /api/v1/admin/sync/raw-payloads/{payloadId}
```

建议只允许管理端调用，因为原始 JSON 可能体积较大。

## 三、预测接口

对应数据表：`prediction_batch`、`prediction_job`、`prediction_result`、`prediction_result_item`、`prediction_evidence`

### 4.1 创建整轮预测批次

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
7. 发布 RabbitMQ 预测任务
8. 更新批次状态为 `TASK_CREATED`

返回示例：

```json
{
  "batchId": 1,
  "roundId": 10,
  "status": "TASK_CREATED",
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

### 4.2 查询预测批次

```http
GET /api/v1/prediction-batches/{batchId}
```

返回：批次状态、分站信息、题目总数、已完成数量、失败数量、数据截止时间、创建时间、锁定时间。

### 4.3 查询批次任务列表

```http
GET /api/v1/prediction-batches/{batchId}/jobs
```

### 4.4 查询单个预测任务

```http
GET /api/v1/prediction-jobs/{predictionJobId}
```

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

### 8.1 Java 发布预测任务

建议交换机：`f1.prediction.exchange`，请求路由键：`prediction.request.v1`

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

```java
public interface RoundService {
    RoundDto getCurrentRound();
    RoundDto getById(Long roundId);
    List<RoundDto> listBySeasonId(Long seasonId);
    List<MeetingSessionDto> listSessions(Long roundId);
}
```

```java
public interface QuestionService {
    List<QuestionDto> listByRoundId(Long roundId, QuestionQuery query);
    QuestionDetailDto getDetail(Long questionId);
    QuestionSnapshotDto getSnapshot(Long questionId, Long snapshotId);
    List<QuestionSnapshotDto> listSnapshots(Long questionId);
}
```

```java
public interface FeedSyncService {
    SyncResult syncSchedule();
    SyncResult syncQuestions(Integer gamedayId);
    SyncResult syncLimits();
    SyncResult syncCurrent();
    PageResult<SyncRecordDto> pageRecords(SyncRecordQuery query);
}
```

```java
public interface PredictionBatchService {
    PredictionBatchDto create(CreatePredictionBatchRequest request);
    PredictionBatchDetailDto getById(Long batchId);
    List<PredictionJobDto> listJobs(Long batchId);
    void lock(Long batchId);
    void retry(Long batchId);
}
```

```java
public interface PredictionJobService {
    PredictionJobDto getByBusinessId(String predictionJobId);
    void markRunning(String predictionJobId, String workerNode);
    void markRetrying(String predictionJobId, String errorMessage);
    void markSucceeded(String predictionJobId);
    void markFailed(String predictionJobId, String errorMessage);
}
```

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

后续真正编写 Java DTO 时，需要遵守以下规则：

```java
@Data
public class PredictionResultDto {

    @JsonProperty("prediction_job_id")
    private String predictionJobId;

    @JsonProperty("question_id")
    private Long questionId;

    @JsonProperty("selected_options")
    private List<PredictionResultItemDto> selectedOptions;

    @JsonProperty("source_data_cutoff")
    private Instant sourceDataCutoff;

    @JsonProperty("model")
    private String model;
}
```

要求：

- 所有 DTO 字段增加 `@JsonProperty`
- 所有请求体使用 `@Valid`
- 时间统一使用 UTC
- 分页使用 0-based
- 分页 `size` 最大不超过 100
- `confidence` 限制在 `0~1`
- `predictionJobId` 必须作为幂等键
- 不直接对外返回 Entity
- 不把 RabbitMQ、Redis 作为最终业务数据源

## 推荐的首版开发顺序

1. 赛季、分站、Session 查询接口
2. Feed 同步接口
3. 题目和快照查询接口
4. 预测批次创建和任务查询接口
5. RabbitMQ 预测任务发布与结果消费
6. 预测结果查询和锁定接口
7. 官方答案同步
8. 评分接口
9. 分站、赛季和模型统计接口