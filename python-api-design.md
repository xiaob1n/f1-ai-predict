# F1 AI Predict Python 端接口设计

> 本文档依据以下资料整理：
> - `f1-ai-predict-feasibility-analysis.md`（项目可行性分析，2026-08-27）
> - `java-api-design.md`（Java 端接口设计）
> - `sql/004_prediction.sql`、`sql/005_answer_scoring.sql`（预测与评分建表）
> - 当前 `f1aipredict` Java 源码（`season`、`question`、`sync` 及预测批次/任务、请求 outbox）
> - 2026-09 检索到的 LangChain / Qdrant 官方集成结论（`langchain-qdrant` 伙伴包、`QdrantVectorStore`、`RetrievalMode`、`with_structured_output`、Qdrant 异步客户端与 payload 过滤）
>
> **当前状态警告**：Java 已实现 `season`、`question`、`sync` 及预测批次/任务创建与只读查询，并有可选启用的 v2 请求 outbox 发布器；`scoring`、`statistics` 和预测结果消费尚未实现。Python 已完成工程骨架、运维 REST（就绪与 Worker 状态仍为占位）、消息 DTO、本地 TraceSink，以及独立的 v2 RabbitMQ 请求消费者（SQLite inbox 落盘后 ACK、重复请求幂等、坏消息隔离并死信）。结果发布、预测推理、RAG、Qdrant、模型服务仍未实现。本文档中未标记的内容仍属于设计稿，不得据此声称系统已具备预测能力。模型训练不在当前接口范围内。

## 一、接口分层与包结构

Java 是业务数据唯一管理方；Python 不直接访问云端 MySQL 与 Redis，不执行 F1 Predict Feed 同步，只从家庭主机本地 MongoDB 与 Qdrant 读取数据，并调用本地模型。两端通过 RabbitMQ 完成异步消息通信，生产主链路固定为：

```text
Java 组装预测任务 → RabbitMQ 任务队列 → Python Worker 消费并推理
      → Python 发布结果/失败消息 → RabbitMQ 结果队列 → Java 消费落库
```

Python 端采用按职责划分的包结构：

```text
f1_predict
├── worker                      # RabbitMQ 消费者与消息处理编排
│   ├── consumer.py             # PredictionTaskConsumer：消费任务、编排链路
│   ├── publisher.py            # PredictionResultPublisher：发布结果/失败消息
│   └── ack_flow.py             # 手动 ACK 与发布者确认的时序控制
├── messaging                   # 队列拓扑、路由键、消息 DTO
│   ├── topology.py             # 交换机/队列/路由键常量
│   └── dto                     # Pydantic 消息 DTO（见第七节）
├── rag                         # 检索增强生成
│   ├── indexer.py              # KnowledgeIndexer：MongoDB → 切块 → 嵌入 → Qdrant
│   ├── retriever.py            # CutoffAwareRetriever：截止时间强制过滤检索
│   └── vector_store.py         # QdrantVectorStore / RetrievalMode 封装
├── features                    # 特征读取（只读本地 MongoDB）
│   ├── repository.py           # FeatureRepository：赛前特征与来源引用
│   └── filters.py              # 时间边界与特征版本过滤
├── prompts                     # Prompt 版本管理
│   ├── repository.py           # PromptRepository：按版本读取不可变 Prompt
│   └── definitions.py          # PromptDefinition 与模板哈希
├── model                       # 本地模型服务网关
│   ├── gateway.py              # ModelGateway：结构化输出与健康检查
│   └── schemas.py              # 输出 Schema（Pydantic 模型）
├── chain                       # 预测链路（确定性两步 RAG / LCEL）
│   └── prediction_chain.py     # PredictionChain：检索 → 组装 → 结构化输出
├── reliability                 # 幂等、追踪、错误分类
│   ├── idempotency.py          # IdempotencyStore：predictionJobId 业务幂等
│   └── trace.py                # TraceSink：traceId/messageId/predictionJobId 关联
├── api                         # 轻量 REST（只监听本机/家庭内网）
│   ├── health.py               # /health/live、/health/ready
│   ├── models.py               # /api/v1/models/current
│   └── worker_status.py        # /api/v1/worker/status
└── common                      # 日志、配置、时间工具
```

## 二、系统边界与组件职责

### 2.1 职责划分

| 组件 | 职责 | 不负责 |
| --- | --- | --- |
| Java Spring Boot | 业务系统和核心数据唯一管理方：Feed 接入、赛季/分站/Session 管理、题目快照、任务编排、预测持久化与锁定、官方答案同步、自动评分、查询与管理接口、日志审计 | 不直接执行模型推理，不访问 Qdrant 与 MongoDB |
| Python Worker | 从 RabbitMQ 消费预测任务；读取截止时间以前的本地 MongoDB 数据；检索本地 Qdrant；组装 Prompt 并调用本地模型；将结果/失败消息发布到结果队列 | Feed 同步、核心业务数据写入、赛程和题目生命周期、答案同步与评分、锁定规则 |
| RabbitMQ | 承载 Java 发布的预测任务与 Python 发布的结果/进度/失败消息，提供异步解耦、确认、重试和死信处理 | 不作为最终业务数据库 |
| Redis（云端） | 由云端 Java 使用：缓存、任务进度、幂等键、短期锁 | 不供家庭 Python Worker 直接访问 |
| MongoDB（家庭） | OpenF1 原始文档、采集元数据、可重放的特征来源 | 强一致业务数据 |
| Qdrant（家庭） | MongoDB 数据的派生语义索引，支撑 RAG 检索 | 业务事实数据库，任何业务状态不得以 Qdrant 为准 |
| MySQL | 赛程、题目快照、预测批次、Agent 输出、官方答案、评分等强一致业务数据 | OpenF1 原始响应存放 |

### 2.2 边界约束

1. Python 不访问云端 MySQL 与 Redis，不执行任何 F1 Predict Feed 同步。
2. Python 只读家庭主机本地 MongoDB 与 Qdrant，只调用本机或本机 Docker 内网的模型服务。
3. 所有面向 Java 的消息字段一律 camelCase，时间一律 ISO 8601 UTC。
4. Qdrant 是 MongoDB 的派生索引，可整体重建；MongoDB 是规范原始来源，二者均不替代 MySQL。
5. Python 不得根据 `questionType` 自行推断题型映射；当前 Java 端 `questionType` 恒为 `UNKNOWN`（见第四节 4.7），题型映射落地前，选项数量与合法性校验归 Java 所有。

## 三、Python 端轻量 REST 接口

Python 端 REST 接口全部只监听 `127.0.0.1` 或家庭主机 Docker 内网，不经过公网反向代理，不暴露到家庭外网。这些接口供本机运维、开发调试与 Java 运维脚本通过 Tailscale 私网按需调用，不属于生产预测主链路。

### [x] 3.1 存活检查

```http
GET /health/live
```

进程存活即返回 200，不检查任何依赖：

```json
{
  "status": "UP"
}
```

### 3.2 就绪检查（占位实现，未完成）

- [x] `/health/ready` 路由和固定 `DOWN`/503 占位响应已实现。
- [ ] RabbitMQ、MongoDB、Qdrant、模型的真实依赖就绪检查未实现；独立消费者使用进程文件探针，不等同于此 HTTP 接口。

```http
GET /health/ready
```

检查 RabbitMQ 连接、本地 MongoDB、Qdrant 与模型加载状态，任一不可用返回 503：

```json
{
  "status": "DOWN",
  "checks": {
    "rabbitmq": "UP",
    "mongodb": "UP",
    "qdrant": "DOWN",
    "model": "UP"
  }
}
```

### 3.3 当前模型与版本

```http
GET /api/v1/models/current
```

返回当前加载的模型及全部关联版本，供 Java 侧核对任务版本是否匹配：

```json
{
  "modelVersion": "qwen3-8b-lora-v1",
  "adapterVersion": "lora-r1",
  "baseModel": "Qwen3-8B-Instruct",
  "defaultPromptVersion": "f1-race-v1",
  "embeddingVersion": "embedding-v1",
  "retrieverVersion": "retriever-v1",
  "device": "cuda:0",
  "loadedAt": "2026-08-28T09:00:00Z",
  "healthy": true
}
```

### 3.4 Worker 状态（占位运行态，未完成）

- [x] `/api/v1/worker/status` 占位路由已实现。
- [ ] 真实的消费者心跳、任务状态、队列深度和 GPU/模型运行态尚未接入此 HTTP 接口。

```http
GET /api/v1/worker/status
```

返回 Worker 心跳与运行态，供云端监控判断 Worker 是否在线：

```json
{
  "workerNode": "home-gpu-01",
  "status": "IDLE",
  "currentJobId": null,
  "lastHeartbeat": "2026-08-28T10:00:30Z",
  "uptimeSeconds": 86400,
  "gpu": {
    "memoryUsedBytes": 6291456000,
    "memoryTotalBytes": 12884901888,
    "utilization": 0.15
  },
  "modelLoaded": true,
  "rabbitmqConnected": true,
  "queueDepthEstimate": 0
}
```

### 3.5 本地/管理端接口（内部或后期阶段）

以下接口属于内部调试或后期管理能力，首版可以不做；实现时必须与公网彻底隔离，并在代码注释与文档中明确标记：

```http
POST /admin/rag/index/build          # 内部：构建指定数据范围的索引（后期阶段）
POST /admin/rag/index/rebuild        # 内部：按 embeddingVersion 整体重建索引（后期阶段）
GET  /admin/prompts                  # 内部：列出已登记的 Prompt 版本与 templateHash
GET  /admin/models                   # 内部：列出本机已登记的模型版本与适配器
```

这些接口不做认证仅限 `127.0.0.1` 绑定；若未来需要远程管理，必须叠加私网访问控制，不允许直接暴露。

### 3.6 本地测试专用预测接口（生产禁用）

为便于开发调试，可以提供一个同步预测入口，但它**不是生产主路径**：

```http
POST /predict
```

生产部署必须通过配置显式禁用（例如环境变量 `F1_PREDICT_LOCAL_TEST_API=false`，禁用时该路由返回 404 或 403）；即使启用也只监听 `127.0.0.1`。生产预测只能走 `Java → RabbitMQ → Python → RabbitMQ → Java` 异步链路。

## 四、Java 端可复用 REST 接口（现状）

本节列出现有 Java 已实现的 REST 接口，供 Python 团队在开发期联调、构造回放数据或排查问题时使用。**生产环境 Java 应在内部复用 `SeasonService`、`RoundService`、`MeetingSessionService`、`QuestionService` 组装 RabbitMQ 消息，Python 不需要回调这些 REST 接口。**

### [x] 4.1 赛季接口（`SeasonController`）

```text
GET /api/v1/seasons/current
GET /api/v1/seasons
```

`/api/v1/seasons` 支持 `status`、`page`（0-based，默认 0）、`size`（默认 20，上限 100）。

### [x] 4.2 分站接口（`RoundController`）

```text
GET /api/v1/rounds/current
GET /api/v1/rounds/{roundId}
GET /api/v1/seasons/{seasonId}/rounds
```

### [x] 4.3 Session 接口（`MeetingSessionController`）

```text
GET /api/v1/rounds/{roundId}/sessions
GET /api/v1/sessions/{sessionId}
GET /api/v1/sessions/by-session-key/{sessionKey}
GET /api/v1/sessions/by-meeting-key/{meetingKey}
```

`sessionKey` 唯一，未命中返回 404；`meetingKey` 非唯一，未命中返回空数组。

### [x] 4.4 题目接口（`QuestionController`）

```text
GET /api/v1/rounds/{roundId}/questions
GET /api/v1/questions/{questionId}
GET /api/v1/questions/{questionId}/snapshots/{snapshotId}
GET /api/v1/questions/{questionId}/snapshots
```

`/api/v1/rounds/{roundId}/questions` 支持 `status`、`gamedayId`、`includeOptions`（默认 true）、`snapshotId`；选项、快照在 Service 内批量加载，无 N+1。

### [x] 4.5 同步管理接口（`SyncAdminController`）

```text
POST /api/v1/admin/sync/schedule
POST /api/v1/admin/sync/limits
POST /api/v1/admin/sync/questions/{gamedayId}
POST /api/v1/admin/sync/current
GET  /api/v1/admin/sync/records
GET  /api/v1/admin/sync/raw-payloads/{payloadId}
```

这些接口属于管理端，Python 团队不应依赖，仅 Java 运维使用。

### 4.6 开发期用法

Python 团队开发早期可以用这些 REST 接口拉取题目与赛程样例做离线回放（例如把 `GET /api/v1/rounds/{roundId}/questions` 的响应存成固定 JSON 作为测试夹具）。但生产消息组装必须由 Java 侧内部完成，Python 不得在运行时回调 Java REST。

### 4.7 当前 DTO 真值

现有 Java DTO 的关键真值（以代码与契约测试为准）：

- `questionType` 首版恒为 `"UNKNOWN"`，Feed 侧类型映射未落地。Python 必须接受 `UNKNOWN`，不得自行发明 `SINGLE/MULTIPLE/RANKING` 映射，不得假设单选或多选行为。
- `optionTemplateId` 与 `choiceLimit` 只是 Java 原样保留的元数据，Python 原样携带、不据此推断题型。
- 在题型映射落地前，最终选项数量与合法性校验仍归 Java 所有；Python 只输出结构化的 `selectedOptions`，由 Java 校验。

题目列表 JSON 形状示例（与 Java 端契约测试对齐）：

```json
{
  "questionId": 1,
  "gamedayId": 100,
  "sourceQuestionId": 476,
  "questionNo": 1,
  "questionText": "Who will qualify on pole?",
  "subText": null,
  "questionType": "UNKNOWN",
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

## 五、RabbitMQ 内部接口

### 5.1 队列拓扑（v2 请求与死信已实现，以下 v1 全链路拓扑未实现）

- [x] v2 请求链路已声明持久 direct 交换机/队列 `f1.prediction.request.v2`（路由键 `prediction.request.v2`），以及持久死信交换机/队列 `f1.prediction.dead.v2`（路由键 `prediction.dead.v2`）；Java 发布器与独立 Python 消费者使用相同拓扑。
- [ ] 进度、结果、失败及延迟重试队列尚未实现。

原 v1 设计建议交换机 `f1.prediction.exchange`（topic），路由键按消息类型区分：

```text
f1.prediction.exchange
├── f1.prediction.request.queue     路由键 prediction.request.v1     Java → Python 预测任务
├── f1.prediction.progress.queue    路由键 prediction.progress.v1    Python → Java 进度事件
├── f1.prediction.result.queue      路由键 prediction.result.v1      Python → Java 预测结果
├── f1.prediction.failure.queue     路由键 prediction.failure.v1     Python → Java 失败消息
├── f1.prediction.retry.queue       延迟重试（消息 TTL 到期后经 DLX 回到 request.queue）
└── f1.prediction.dead-letter.queue 超过重试上限或不可处理的消息
```

可靠性基线沿用可行性分析：持久化队列、持久化消息、发布者确认、消费者手动确认、至少一次投递；`predictionJobId` 是双端幂等键；消息确认不等于业务事务，最终状态以 MySQL 为准。

### 5.2 通用字段约定

- [x] 已落地的请求消息使用 `schemaVersion="2"`，包含下述追踪字段、冻结题目与赛事上下文、UTC `dataCutoff` 和版本字段；Python v2 DTO 校验这些字段。
- [x] v1 消息 DTO 及其往返契约测试已存在，但线上请求消费者只接受 v2。
- [ ] 下述 `schemaVersion="1"` 属于原全链路设计；进度、结果与失败消息的运行处理尚未落地。

原设计要求所有消息 JSON 一律 camelCase，必含以下追踪与版本字段：

```text
schemaVersion      协议版本，首版固定 "1"，变更需 Java/Python 同步升级
messageId          单条消息唯一 ID（UUID），跨端排查用
predictionJobId    业务幂等键（UUID），Java/Python 双端幂等依据
batchId            所属预测批次 ID
questionId         题目 ID
questionSnapshotId 本次预测使用的题目快照 ID
traceId            一次整轮预测的追踪 ID，批次内所有消息共享
```

请求消息额外携带 `raceContext`、`dataCutoff` 与版本组合（`modelVersion`、`promptVersion`、`featureVersion`、`embeddingVersion`、`retrieverVersion`）；结果消息回填实际使用的 `sourceDataCutoff` 与各版本实值。

### 5.3 预测任务请求消息（v2 已实现，以下为原 v1 示例）

- [x] Java 将每个任务的 v2 不可变请求写入 outbox，并由可选启用的发布器发送；Python `PredictionRequestV2` DTO 校验后写入本地 inbox。题目与选项随消息完整下发，不需要 Python 回查 Java。
- [x] v1 `PredictionRequestMessage` DTO 与以下示例已有往返契约测试；该示例不是当前发布器/消费者使用的 v2 运行协议。

原 v1 设计示例：

```json
{
  "schemaVersion": "1",
  "messageId": "msg-7f3a9c2e",
  "predictionJobId": "job-4c2e8b1a",
  "batchId": 1,
  "questionId": 1,
  "questionSnapshotId": 12,
  "question": {
    "questionText": "Who will qualify on pole?",
    "subText": null,
    "questionType": "UNKNOWN",
    "optionTemplateId": 1,
    "choiceLimit": 1,
    "options": [
      {
        "optionId": 117,
        "optionNo": 0,
        "optionText": "Driver A",
        "points": 10,
        "chance": 0.73
      },
      {
        "optionId": 118,
        "optionNo": 1,
        "optionText": "Driver B",
        "points": 8,
        "chance": 0.27
      }
    ]
  },
  "raceContext": {
    "seasonId": 1,
    "year": 2026,
    "roundId": 10,
    "roundNumber": 1,
    "meetingKey": 1219,
    "sessionKey": 9161,
    "gamedayId": 100,
    "trackName": "Melbourne Grand Prix Circuit"
  },
  "dataCutoff": "2026-08-28T10:00:00Z",
  "modelVersion": "qwen3-8b-lora-v1",
  "promptVersion": "f1-race-v1",
  "featureVersion": "feature-v1",
  "embeddingVersion": "embedding-v1",
  "retrieverVersion": "retriever-v1",
  "traceId": "trace-9b1c7d3e"
}
```

字段约束：

- `dataCutoff` 是硬边界，Python 只允许使用该时间之前的数据与文档。
- `questionType` 恒为 `UNKNOWN` 时 Python 只做文本理解，不做题型假设（见 4.7）。
- `options[].optionId` 是 Feed 侧 ID，只在快照范围内有意义，Python 原样回填。

### 5.4 进度消息（PredictionProgressMessage）

Python 在长任务阶段切换时发布，Java 侧仅更新 Redis 短期进度，不作为业务事实：

```json
{
  "schemaVersion": "1",
  "messageId": "msg-progress-01",
  "predictionJobId": "job-4c2e8b1a",
  "batchId": 1,
  "questionId": 1,
  "phase": "RAG_RETRIEVAL",
  "progress": 0.4,
  "workerNode": "home-gpu-01",
  "message": "特征加载完成，开始检索",
  "startedAt": "2026-08-28T10:01:00Z",
  "traceId": "trace-9b1c7d3e"
}
```

`phase` 取值建议：`RECEIVED`、`FEATURE_LOAD`、`RAG_RETRIEVAL`、`PROMPT_BUILD`、`MODEL_INFERENCE`、`RESULT_PUBLISH`、`FAILED`。

### 5.5 预测结果消息（PredictionResultMessage）

Python 在推理成功后发布，Java 消费并落库。`selectedOptions` 用 `position` 表达排序位置，单选恒为 1；`rawAgentResponse` 保留模型原始输出供审计：

```json
{
  "schemaVersion": "1",
  "messageId": "msg-result-01",
  "predictionJobId": "job-4c2e8b1a",
  "batchId": 1,
  "questionId": 1,
  "questionSnapshotId": 12,
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
      "sourceType": "OPENF1_SESSION_RESULT",
      "sourceName": "OpenF1 session_result",
      "sourceUrl": "https://example.com/source",
      "eventTime": "2026-08-20T12:00:00Z",
      "publishedAt": "2026-08-20T12:05:00Z",
      "documentId": "doc-001",
      "chunkId": "chunk-001"
    }
  ],
  "retrievedChunkIds": [
    "chunk-001",
    "chunk-002"
  ],
  "sourceDataCutoff": "2026-08-28T10:00:00Z",
  "modelVersion": "qwen3-8b-lora-v1",
  "agentVersion": "agent-0.1.0",
  "promptVersion": "f1-race-v1",
  "featureVersion": "feature-v1",
  "embeddingVersion": "embedding-v1",
  "retrieverVersion": "retriever-v1",
  "rawAgentResponse": {
    "parsed": {
      "selectedOptions": [
        {
          "optionId": 117,
          "position": 1
        }
      ],
      "confidence": 0.73
    },
    "raw": "{\"selectedOptions\":[{\"optionId\":117,\"position\":1}],\"confidence\":0.73}"
  },
  "generatedAt": "2026-08-28T10:03:20Z",
  "traceId": "trace-9b1c7d3e"
}
```

字段约束：

- `confidence` 必须落在 `0.0 ~ 1.0`。
- 每条 `evidence.publishedAt` 与 `eventTime` 必须早于等于 `sourceDataCutoff`；违反即视为防泄漏违规，Java 侧拒绝。
- `modelVersion`、`promptVersion`、`featureVersion`、`embeddingVersion`、`retrieverVersion` 必须回填实际使用版本，并与请求指定版本一致。任一指定版本不可用时应拒绝任务并发布明确的失败消息，禁止静默切换模型、Prompt、特征或检索配置。
- `generatedAt` 为生成时间（UTC），由 Python 写入。

### 5.6 失败消息（PredictionFailureMessage）

Python 在确定无法完成该任务时发布；`errorMessage` 只允许安全摘要，禁止 SQL、堆栈、连接串、主机名、Feed URL 与密钥：

```json
{
  "schemaVersion": "1",
  "messageId": "msg-failure-01",
  "predictionJobId": "job-4c2e8b1a",
  "batchId": 1,
  "questionId": 1,
  "phase": "MODEL_INFERENCE",
  "errorCode": "MODEL_TIMEOUT",
  "errorMessage": "模型推理超时，请稍后重试",
  "retryable": true,
  "attempt": 2,
  "maxRetries": 3,
  "workerNode": "home-gpu-01",
  "traceId": "trace-9b1c7d3e"
}
```

### 5.7 手动 ACK 语义与可靠性

- [x] 当前 v2 请求消费者在 SQLite inbox 提交成功（或识别为同一请求的重复投递）后手动 ACK；非法请求先写入 quarantine，再拒绝并路由到 DLQ；SQLite 故障时不 ACK，关闭连接后由 Broker 重投。
- [ ] 以下“模型推理及结果消息发布确认后再 ACK”的设计尚未实现；当前 ACK **仅代表请求持久接收**，不代表预测成功。

#### ACK 时机（规划中的推理/结果发布链路）

1. 消费请求消息后先执行幂等检查（`IdempotencyStore.claim`）。
2. 执行特征读取、检索、Prompt 组装、模型推理。
3. 发布结果或失败消息，等待 RabbitMQ 发布者确认。
4. **确认发布成功之后**才 ACK 请求消息。
5. 任一环节失败且未确认发布：不 ACK，请求消息重新入队，由幂等键保证不重复产生业务效果。

进度消息是 best-effort 通知，允许丢失，不需要严格确认。

#### 重试与非重试错误

| 错误类别 | 示例 | 是否重试 | 处理方式 |
| --- | --- | --- | --- |
| 可重试（临时） | RabbitMQ 连接中断、MongoDB 暂时不可用、Qdrant 暂时不可用、模型服务超时、GPU OOM、磁盘暂时不足 | 是 | 指数退避重试；`retryable=true`；超过 `maxRetries`（建议 3 次）后进入死信队列 |
| 不可重试（永久） | JSON 解析失败、`schemaVersion` 不支持、必填字段缺失、`predictionJobId` 格式非法、选项列表为空且无法理解题目 | 否 | 直接发布 `retryable=false` 失败消息或进入死信队列，人工排查 |

#### 幂等与重复成功行为

- 以 `predictionJobId` 为业务幂等键，Python 与 Java 各自持久化处理记录。
- 重复消息命中已完成记录时：不重复执行模型推理；若首次结果已确认发布，则直接 ACK；若首次发布未确认（进程中断），则重新发布同一结果（`messageId` 可重新生成，`predictionJobId` 不变），Java 侧以 `predictionJobId` 去重，不产生第二条业务结果。
- 幂等存储建议用本地 MongoDB 集合或本地 SQLite，键含 `predictionJobId` 与结果 `messageId`，不依赖云端 Redis。

#### 死信行为

- 超过重试上限、`retryable=false` 或消息格式无法解析的消息进入 `f1.prediction.dead-letter.queue`。
- 死信消息保留原始消息体与投递信息，人工排查后可手动重新发布或丢弃。
- Java 侧将对应 `prediction_job` 标记为 `DEAD_LETTER`，页面按死信状态展示，不伪报成功。

## 六、Python Protocol / ABC 契约

以下为设计级接口契约，采用现代 Python 类型标注；正式实现前可按工程实际再细化。所有注释与 docstring 使用中文。消息 DTO（`PredictionRequestMessage` 等）定义见第七节。

### 6.1 PredictionTaskConsumer

```python
from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class PredictionTaskConsumer(Protocol):
    """预测任务消费者。

    从 RabbitMQ 任务队列消费 PredictionRequestMessage，
    幂等处理后调用 PredictionChain 生成结果，并保证在结果消息
    被发布者确认之后才 ACK 原任务消息。
    """

    def consume(self, request: Any) -> None:
        """消费一条预测任务消息。

        内部时序：幂等检查 -> 特征读取 -> 检索 -> 推理 ->
        发布结果 -> 等待发布者确认 -> ACK 请求消息。
        任何一步失败且未确认发布时不得 ACK。
        """
        ...
```

### 6.2 PredictionResultPublisher

```python
from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class PredictionResultPublisher(Protocol):
    """预测结果发布器。

    把结果消息、失败消息和进度消息发布到结果交换机，
    发布时必须等待 RabbitMQ 发布者确认。
    """

    def publish_result(self, result: Any) -> bool:
        """发布预测结果消息，返回是否已确认。

        返回 False 表示未确认，调用方不得 ACK 请求消息。
        """
        ...

    def publish_failure(self, failure: Any) -> bool:
        """发布失败消息，返回是否已确认。"""
        ...

    def publish_progress(self, progress: Any) -> None:
        """发布进度消息（best-effort，不要求确认）。"""
        ...
```

### 6.3 FeatureRepository

```python
from __future__ import annotations

from datetime import datetime
from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class FeatureRepository(Protocol):
    """特征仓库。

    只读取家庭主机本地 MongoDB 中 data_cutoff 之前的数据，
    按 meeting_key / session_key 组织赛前特征，并保留来源引用。
    禁止读取截止时间之后的数据，缺失特征显式标记而不是补齐。
    """

    def load_features(
        self,
        *,
        meeting_key: int | None,
        session_key: int | None,
        data_cutoff: datetime,
        feature_version: str,
    ) -> Any:
        """加载赛前特征集合（含缺失标记与来源引用）。"""
        ...

    def find_evidence(
        self,
        *,
        meeting_key: int | None,
        session_key: int | None,
        data_cutoff: datetime,
        limit: int,
    ) -> list[dict[str, Any]]:
        """按截止时间查询可用证据引用，返回 EvidenceRef 列表。"""
        ...
```

### 6.4 KnowledgeIndexer

```python
from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class KnowledgeIndexer(Protocol):
    """知识索引构建器。

    把 MongoDB 原始文档切块、嵌入并写入 Qdrant 派生索引。
    索引可整体重建，属于派生数据，不承担业务事实职责。
    """

    def build_index(
        self,
        *,
        source_type: str,
        season_id: int | None,
        embedding_version: str,
    ) -> dict[str, Any]:
        """构建指定数据范围的索引，返回构建统计。"""
        ...

    def rebuild_index(self, *, embedding_version: str) -> dict[str, Any]:
        """按 embedding_version 整体重建索引，旧版本集合可保留或清理。"""
        ...
```

### 6.5 CutoffAwareRetriever

```python
from __future__ import annotations

from datetime import datetime
from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class CutoffAwareRetriever(Protocol):
    """截止时间感知的检索器。

    所有检索必须强制 eventTime <= data_cutoff 且
    publishedAt <= data_cutoff；静态规则文档显式标记
    timeless=true 才可豁免时间过滤。
    """

    def retrieve(
        self,
        query: str,
        *,
        data_cutoff: datetime,
        top_k: int,
        embedding_version: str,
        retriever_version: str,
    ) -> list[dict[str, Any]]:
        """检索并返回 RetrievedChunk 列表（含 chunkId 与 payload 元数据）。"""
        ...
```

### 6.6 PromptRepository

```python
from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class PromptRepository(Protocol):
    """Prompt 仓库。

    按 prompt_version 读取不可变 PromptDefinition；
    禁止未跟踪的运行时编辑，版本一经发布只读。
    """

    def get(self, prompt_version: str) -> Any:
        """按版本读取 PromptDefinition，未知版本抛出异常。"""
        ...

    def list_versions(self) -> list[dict[str, Any]]:
        """列出已登记版本（含 templateHash 与兼容模型列表）。"""
        ...
```

### 6.7 PredictionChain

```python
from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class PredictionChain(Protocol):
    """预测链路。

    采用确定性两步 RAG / LCEL 编排：
    第一步用 CutoffAwareRetriever 检索，第二步把上下文与题目
    组装成 ChatPromptTemplate 后经 ModelGateway 输出结构化 JSON。
    MVP 不采用 agentic RAG（不引入工具调用循环与多 Agent 辩论）。
    """

    def invoke(self, request: Any, *, features: Any) -> dict[str, Any]:
        """执行一次预测，返回结构化输出与原始模型响应。"""
        ...
```

### 6.8 ModelGateway

```python
from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class ModelGateway(Protocol):
    """模型网关。

    封装本地模型服务调用与结构化输出解析；
    服务只绑定 127.0.0.1 或本机 Docker 内网，禁止公网暴露。
    """

    def generate_structured(self, prompt: Any, *, model_version: str) -> dict[str, Any]:
        """生成结构化输出，返回含 parsed 与 raw 的结果。"""
        ...

    def health(self) -> dict[str, Any]:
        """返回模型加载状态、设备与显存信息。"""
        ...
```

### 6.9 IdempotencyStore

```python
from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class IdempotencyStore(Protocol):
    """幂等存储。

    以 predictionJobId 为业务幂等键，记录任务处理状态与结果摘要；
    重复消息必须返回已有状态，而不是重复执行模型推理。
    """

    def claim(self, prediction_job_id: str, *, message_id: str) -> str:
        """尝试认领任务，返回 CLAIMED 或 ALREADY_DONE 等状态。"""
        ...

    def complete(self, prediction_job_id: str, *, result_message_id: str) -> None:
        """记录任务完成与已发布的结果消息 ID。"""
        ...
```

### 6.10 TraceSink（阶段一本地实现）

- [x] 已实现 `TraceSink` ABC、`TraceRecord`、`TraceSpan` 与 `ConsoleTraceSink`；实际 `record(record: TraceRecord)` 签名与以下设计级 Protocol 示例不同，跨服务追踪和持久化尚未实现。

```python
from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class TraceSink(Protocol):
    """追踪汇。

    统一记录 traceId / messageId / predictionJobId 关联的
    日志、阶段耗时与指标，支持跨云端与家庭主机排查。
    """

    def start_span(
        self,
        *,
        trace_id: str,
        message_id: str,
        prediction_job_id: str,
        phase: str,
    ) -> Any:
        """开启一个阶段跨度，返回可关闭的句柄。"""
        ...

    def record(self, *, trace_id: str, prediction_job_id: str, **fields: Any) -> None:
        """记录结构化追踪字段。"""
        ...
```

阶段一已实现本地 `TraceRecord`、`TraceSpan` 和 `ConsoleTraceSink`，支持关联字段记录与 Span 生命周期；跨服务追踪、持久化、指标和实际预测链路尚未实现。

## 七、Pydantic DTO 规范

### [x] 7.1 命名与别名

Python 属性一律 snake_case，线上 JSON 一律 camelCase 别名。采用 Pydantic v2 配置：

```python
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class PredictionResultMessage(BaseModel):
    """预测结果消息 DTO。

    属性 snake_case，线上 JSON camelCase；
    populate_by_name 允许两种名称反序列化，
    serialize_by_alias 保证序列化输出始终为 camelCase。
    """

    model_config = ConfigDict(
        populate_by_name=True,
        serialize_by_alias=True,
        extra="forbid",
        str_strip_whitespace=True,
    )

    schema_version: str = Field(alias="schemaVersion")
    message_id: str = Field(alias="messageId")
    prediction_job_id: str = Field(alias="predictionJobId")
    batch_id: int = Field(alias="batchId")
    question_id: int = Field(alias="questionId")
    confidence: float = Field(alias="confidence", ge=0.0, le=1.0)
    generated_at: datetime = Field(alias="generatedAt")
    raw_agent_response: dict[str, Any] | None = Field(alias="rawAgentResponse", default=None)
```

### [x] 7.2 强制规则（已实现的 DTO 契约部分）

- [x] 时间字段一律使用严格时区感知的 `datetime`（`datetime.UTC`），线上格式为 ISO 8601 带 `Z`；反序列化收到 naive datetime 直接拒绝。
- [x] `confidence` 用 `Field(ge=0.0, le=1.0)` 限定边界，越界拒绝。
- [x] `extra="forbid"`：未知字段直接报错，防止 Java 新增字段被静默吞掉导致跨端契约漂移。
- [x] 序列化只输出 camelCase 别名，任何场景不得在线上 JSON 出现 snake_case 键。
- [ ] 消息 DTO 与第六节的 Protocol 一一对应（Protocol 运行时实现尚未完成）；`rawAgentResponse` 仅作为 DTO 留档字段。

## 八、RAG 与向量数据库接口

### 8.1 定位

- MongoDB 是 OpenF1 数据的规范原始来源与可重放来源，承担原始数据留档与审计职责，但不承担 Java 核心业务事实。
- Qdrant 是 MongoDB 数据的**派生语义索引**，只为检索服务，可随时整体重建，不是业务数据库，不保存业务事实，更不能替代 MySQL。
- 索引构建只消费截止时间之前已落地的 MongoDB 文档；任何索引内容不得包含官方答案或赛后信息。

### 8.2 Qdrant 部署与版本

- 推荐在家庭主机本地自托管 Qdrant（Docker 或系统服务），只监听本机或家庭内网，不暴露公网；云服务器无需访问 Qdrant。
- 当前集成方案基于 Qdrant Query API，**要求 Qdrant 1.10 及以上版本**；落地前必须实际验证部署版本与 `langchain-qdrant`、`qdrant-client` 的兼容性，版本组合以安装时的官方文档为准，本文档不承诺具体版本号。
- `qdrant-client` 支持异步 API（`AsyncQdrantClient`）与 payload 过滤，Worker 内建议异步调用避免阻塞消息循环。

### 8.3 Collection 与 payload 元数据

建议按 `sourceType` 分 collection（例如 `f1_docs_openf1`、`f1_docs_static_rules`），或单 collection 加 `sourceType` 过滤。payload 统一 camelCase，跨组件契约字段：

```text
documentId        来源文档 ID（MongoDB `_id` 或来源记录 ID）
chunkId           切块唯一 ID
chunkText         切块文本（检索展示用，不参与业务事实）
chunkIndex        切块在文档内的序号
sourceType        来源类型：OPENF1_SESSION_RESULT / OPENF1_WEATHER / STATIC_RULES 等
sourceName        来源名称（OpenF1 集合名或规则文档名）
sourceUrl         来源 URL（证据回填用）
seasonId          赛季 ID，可空
roundId           分站 ID，可空
meetingKey        OpenF1 meeting_key，可空
sessionKey        OpenF1 session_key，可空
eventTime         数据事件时间(UTC)，可空（静态规则为空）
publishedAt       数据发布时间/落地时间(UTC)
dataCutoff        该文档允许被检索的截止时间上限(UTC)
sourceVersion     来源数据版本
embeddingVersion  嵌入模型版本
timeless          布尔值，静态规则显式置 true，允许豁免时间过滤
```

时间统一存 ISO 8601 UTC 字符串（字典序即时间序）或 Unix 毫秒整数，二选一后固化，过滤条件与存储格式保持一致。

### 8.4 截止时间过滤（强制）

检索时必须同时满足 `eventTime <= dataCutoff` 且 `publishedAt <= dataCutoff`；`timeless=true` 的静态规则文档豁免时间过滤。过滤条件示例（JSON 形态，落地时映射为 `qdrant-client` 的 `Filter`），语义为"要么是静态规则，要么同时通过两个时间条件"：

```json
{
  "should": [
    {
      "key": "timeless",
      "match": { "value": true }
    },
    {
      "must": [
        {
          "key": "eventTime",
          "range": { "lte": "2026-08-28T10:00:00Z" }
        },
        {
          "key": "publishedAt",
          "range": { "lte": "2026-08-28T10:00:00Z" }
        }
      ]
    }
  ]
}
```

`should` 组至少命中一条：`timeless=true` 的静态规则不参与时间比较；普通文档必须同时满足 `eventTime` 与 `publishedAt` 两个上界。实现时必须保证该语义等价且经过单测验证；任何绕过截止时间过滤的检索路径都不允许存在。

### 8.5 检索方式

- MVP 只做 Dense 检索：`langchain-qdrant` 的 `QdrantVectorStore` 配合 `RetrievalMode.DENSE`。
- Hybrid（Dense + Sparse）保留为后续优化项，不在首版承诺范围。
- LangChain 检索器是 Runnable，检索结果可直接进入 LCEL 管道。

LangChain 架构示例（设计级）：

```python
from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_core.retrievers import BaseRetriever
from langchain_qdrant import QdrantVectorStore, RetrievalMode

# 向量库封装：collection 名、嵌入模型版本与检索模式在配置中声明
vector_store: QdrantVectorStore = QdrantVectorStore.from_existing_collection(
    collection_name="f1_docs_openf1",
    embedding=embedding_model,
    retrieval_mode=RetrievalMode.DENSE,
    url=qdrant_local_url,  # 只允许本机/家庭内网地址
)

retriever: BaseRetriever = vector_store.as_retriever(search_kwargs={"k": 8})
```

检索结果统一封装为 `RetrievedChunk`，至少包含 `chunkId`、`documentId`、`score` 与上述 payload 字段，证据回填与 `retrievedChunkIds` 上报都从它派生。

### 8.6 索引构建数据流

```text
MongoDB 原始文档 → 按 sourceType 读取（截止时间之前）
  → 切块（按文档结构/窗口，记录 chunkIndex）
  → 调用本地嵌入模型生成向量（记录 embeddingVersion）
  → 写入 Qdrant collection（payload 含 8.3 全部元数据）
  → 更新索引版本记录（sourceVersion、embeddingVersion、构建时间）
```

构建与重建只允许通过本地管理接口或内部脚本触发（见 3.5），并保留旧版本索引供回滚对比。

### 8.7 特征查询与证据/引用契约

特征查询（FeatureRepository 的输入形态）：

```json
{
  "meetingKey": 1219,
  "sessionKey": 9161,
  "driverNumbers": [1, 11, 16],
  "fromTime": "2026-08-20T00:00:00Z",
  "toTime": "2026-08-28T10:00:00Z",
  "featureVersion": "feature-v1"
}
```

证据引用（EvidenceRef，进入结果消息 `evidence` 的最小契约）：

```json
{
  "sourceType": "OPENF1_SESSION_RESULT",
  "sourceName": "OpenF1 session_result",
  "sourceUrl": "https://example.com/source",
  "eventTime": "2026-08-20T12:00:00Z",
  "publishedAt": "2026-08-20T12:05:00Z",
  "documentId": "doc-001",
  "chunkId": "chunk-001"
}
```

## 九、模型推理接口

### 9.1 本地模型服务

- 模型服务（如 vLLM、TGI 或其他 OpenAI-compatible 本地推理服务）只绑定 `127.0.0.1` 或家庭主机 Docker 内网，禁止公网端口、禁止 Tailscale Funnel 等隧道暴露。
- Worker 是唯一调用方；模型服务故障按可重试错误处理（超时、OOM），并上报 `MODEL_TIMEOUT`、`GPU_OOM` 等错误码。

### 9.2 结构化输出

使用 LangChain 的 `BaseChatModel.with_structured_output`，传入 Pydantic Schema 并开启 `include_raw=True`，保留解析结果与原始输出双份：

```python
from langchain_core.prompts import ChatPromptTemplate

# 输出 Schema 用 Pydantic 模型定义（字段规范见第七节），直接传入模型类
structured_llm = model.with_structured_output(
    PredictionOutput,
    include_raw=True,
)

# 返回结构：{"raw": 原始文本, "parsed": 解析后的 Pydantic 对象}
```

解析失败的处理：保留 `raw` 留档，按不可重试或有限重试策略处理，绝不用猜测值填充 `parsed`。

### 9.3 预测链路（确定性两步 RAG / LCEL）

```python
from langchain_core.runnables import RunnablePassthrough

prompt = ChatPromptTemplate.from_messages(
    [
        ("system", "{system_instruction}"),
        ("human", "{question_and_context}"),
    ]
)

# 两步：先检索（CutoffAwareRetriever 已内置截止时间过滤），再结构化输出
chain = (
    {
        "system_instruction": RunnablePassthrough(),
        "question_and_context": RunnablePassthrough(),
    }
    | prompt
    | structured_llm
)

result = chain.invoke(
    {
        "system_instruction": prompt_definition.system_instruction,
        "question_and_context": build_question_context(request, retrieved_chunks),
    }
)
```

MVP 明确采用确定性两步 RAG/LCEL，不引入 agentic RAG（无工具调用循环、无多 Agent 辩论），保证输出可复现、可审计。

### 9.4 模型元数据

每次推理记录：`modelVersion`（含基座与适配器）、`promptVersion`、`outputSchemaVersion`、实际输入 token 数、耗时与显存峰值，随 `TraceSink` 落日志。

## 十、Prompt 工程与版本管理

### 10.1 PromptDefinition 字段

```python
from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field


class PromptDefinition(BaseModel):
    """Prompt 定义。

    一经登记即不可变；修改必须新建版本，禁止运行时编辑。
    """

    prompt_version: str = Field(description="Prompt 版本号，如 f1-race-v1")
    template_hash: str = Field(description="模板内容 SHA-256，判重与审计")
    role: str = Field(description="角色设定，如 F1 赛前预测助手")
    system_instruction: str = Field(description="系统约束正文（截止时间、证据限制、输出格式）")
    template: str = Field(description="消息模板，变量见渲染规范")
    output_schema_version: str = Field(description="结构化输出 Schema 版本")
    model_compatibility: list[str] = Field(description="兼容的模型版本列表")
    question_type_mapping: dict[str, str] | None = Field(
        default=None, description="题型映射表；Java 题型映射落地前必须为 None"
    )
    few_shot_examples: list[dict[str, Any]] = Field(default_factory=list)
    created_at: datetime = Field(description="登记时间(UTC)")
```

### 10.2 Prompt 工程规则

1. 角色与系统约束：明确"你是 F1 赛前预测助手。只使用 dataCutoff 之前的数据，必须返回合法 JSON"。
2. 截止时间强制：`dataCutoff` 必须写入系统指令；证据只允许引用截止时间之前的来源。
3. 证据限定推理：推理摘要只能基于给定证据与特征陈述，不允许模型凭记忆补充赛后事实。
4. 候选选项限定输出：输出只允许从消息中给出的选项中选择，`optionId` 必须来自 `options` 列表。
5. few-shot 示例按题型组织，但**仅在 Java 题型映射落地后**才允许按 `questionType` 挑选示例；当前 `questionType=UNKNOWN` 阶段使用通用示例或零样本。
6. `templateHash` 由模板内容哈希生成，用于跨端核对与防篡改。
7. `outputSchemaVersion` 与 `model_compatibility` 必须与模型版本、消息 `schemaVersion` 一起校验，不匹配即拒绝执行。
8. 不可变版本管理：版本一经发布只读，修改即新版本；禁止未跟踪的运行时编辑与热改模板。
9. 每次推理记录实际使用的 Prompt 版本与哈希，随结果与日志落审计。

## 十一、模型与索引版本登记边界

当前只定义在线推理依赖的版本读取，不提供模型训练、数据集构建、微调任务管理或模型产物导出接口。可部署的模型、Embedding、Prompt 和 Retriever 版本由部署流程预先登记，再由 `GET /api/v1/models/current` 和预测结果消息提供已加载版本。

模型训练相关能力属于后续独立设计，不作为本接口文档的契约；在该范围明确前，Python Worker 不应通过 RabbitMQ 或 REST 接收任何训练任务。

## 十二、可追溯性与现状对照表

### 12.1 追踪字段

| 字段 | 出现位置 | 用途 |
| --- | --- | --- |
| `traceId` | 请求/进度/结果/失败消息，日志 | 一次整轮预测的端到端追踪 |
| `messageId` | 所有消息 | 单条消息去重与排查 |
| `predictionJobId` | 所有消息，MySQL 任务表 | 双端业务幂等键 |
| `batchId` | 请求/结果消息 | 批次聚合 |
| `questionId` / `questionSnapshotId` | 请求/结果消息 | 题目与快照锁定追溯 |
| `dataCutoff` / `sourceDataCutoff` | 请求/结果消息 | 防泄漏硬边界与实值核对 |
| 版本字段 | 请求/结果消息 | 模型、Prompt、特征、嵌入、检索版本比对 |
| `rawAgentResponse` | 结果消息，`prediction_result.raw_agent_response` | 模型原始输出留档 |
| `retrievedChunkIds` | 结果消息 | 检索来源审计 |

### 12.2 现状对照

| 能力 | 当前状态 | 规划状态 | 本文档定位 |
| --- | --- | --- | --- |
| Java `season` / `question` / `sync` | 已实现（REST + Service + Mapper + 契约测试） | 稳定扩展 | 第四节描述其可复用接口 |
| Java `prediction` / `scoring` / `statistics` | `prediction` 批次/任务创建与只读查询、请求 outbox 和可选 v2 发布器已实现；结果消费、`scoring`、`statistics` 未实现 | 待开发 | 当前请求契约为 v2，预测结果链路仍按设计推进 |
| RabbitMQ / Redis | RabbitMQ v2 请求发布及 Python 接收链路已有实现；Redis 未接入 | 待开发 | 第五、十三节中 v1 全链路仍为规划 |
| Python Worker | 阶段一骨架、运维 REST、消息 DTO、本地 TraceSink、独立 v2 请求消费者及 SQLite inbox 已实现；推理和结果发布未实现 | 待开发 | 本文档核心设计对象 |
| MongoDB（OpenF1 落地区） | 项目分析确认已保存 OpenF1 数据，具体集合与运行环境不在本仓库实现 | 持续完善采集元数据、索引与备份 | 第二、八节定义边界 |
| Qdrant / RAG | 未实现 | 待开发 | 第八节定义检索契约 |
| 模型服务 | 未实现 | 待开发 | 第九节定义在线推理接口 |
| Python 轻量 REST / 健康接口 | `/health/live` 已实现；`/health/ready` 与 `/api/v1/worker/status` 为占位实现；模型接口未实现 | 待开发 | 第三节定义 |

## 十三、可靠性与安全

### 13.1 消息可靠性

- [x] v2 请求链路已实现持久化拓扑与消息、Java 发布确认、Python inbox 持久化后手动 ACK、重复请求去重及非法请求死信。
- [ ] 结果/失败消息发布确认、推理完成后 ACK、重试上限和 Java `DEAD_LETTER` 状态处理尚未实现。

以下为完整预测/结果链路的设计要求：

- 持久化队列与持久化消息；发布者确认；消费者手动 ACK；至少一次投递。
- ACK 时序：结果发布确认之后才 ACK 请求消息（见 5.7）。
- `predictionJobId` 幂等：重复投递不产生重复业务效果；重复成功不重复发布结果。
- 超过重试上限或不可处理消息进死信队列，Java 侧标记 `DEAD_LETTER`，人工排查后手动处理。

### 13.2 错误信息安全

- 对外 `errorMessage` 只允许安全摘要，禁止 SQL、堆栈、内部类名、连接串、主机名、Feed URL、模型路径与密钥。
- 失败消息必须携带 `errorCode` 与 `retryable`，便于 Java 侧分类展示与告警。
- 日志同样遵守该约束；详细堆栈只写本地日志文件，不随消息与接口外发。

### 13.3 网络与端口

- Python REST 与模型服务只绑定 `127.0.0.1` 或家庭 Docker 内网；不使用公网隧道与 Funnel。
- Worker 作为 RabbitMQ 客户端从家庭网络出站连接云端，默认经 Tailscale 私网；不开放家庭入站端口。
- Qdrant 只监听本机或家庭内网；云端与公网均不可达。
- 连接配置（RabbitMQ 凭据、MongoDB、Qdrant）经环境变量或本地密钥文件注入，不写入代码与文档。

### 13.4 降级行为

| 故障 | 行为 |
| --- | --- |
| 家庭主机关机或断网 | 不 ACK 消息，任务留在队列，恢复后继续；云端显示离线不伪报成功 |
| Worker 崩溃 | 未 ACK 消息重新入队，幂等重试 |
| MongoDB 不可用 | 不检索、不推理，上报可重试错误；禁止用缺失或赛后数据替代 |
| Qdrant 不可用 | RAG 任务上报可重试错误，不得静默绕过检索；只有任务消息显式指定已登记的无检索基线版本时，才允许执行独立基线路径 |
| 模型服务超时 / OOM | 有限重试；超过上限进死信并发布失败消息 |
| RabbitMQ 中断 | 指数退避重连；未完成任务不删除 |

## 十四、推荐的首版开发顺序

1. [x] Python 工程骨架：包结构、日志、配置与本地 `TraceSink`。
2. 轻量 REST：`/health/live` 已完成；`/health/ready` 和 `/api/v1/worker/status` 仍为占位实现。
3. [x] 消息 DTO：v1 DTO 及契约测试、v2 请求 DTO 均已实现，含 camelCase 别名；文档 v1 示例不代表当前消费者使用的 v2 运行协议。
4. RabbitMQ 请求消费骨架：（[x] v2 连接、SQLite inbox 持久化后手动 ACK、重复请求幂等、坏消息死信；[ ] 推理后的结果发布与发布者确认）。
5. 无检索基线链路：题目文本 + 选项直接经 `ModelGateway` 输出结构化 JSON，先打通端到端。
6. `FeatureRepository`：只读本地 MongoDB，截止时间过滤。
7. `PredictionResultPublisher` 与失败消息：结果确认发布后才 ACK。
8. Qdrant 部署（本地、私网、版本核实）与 `KnowledgeIndexer` 索引构建脚本。
9. `CutoffAwareRetriever` 与确定性两步 RAG/LCEL 链路。
10. 运行稳定性演练：覆盖幂等、死信、重复投递、截止时间边界、断网与 Worker 重启。

## 十五、官方参考链接

- [LangChain 检索与 RAG](https://docs.langchain.com/oss/python/langchain/retrieval)
- [LangChain 核心组件架构](https://docs.langchain.com/oss/python/langchain/component-architecture)
- [LangChain 结构化输出](https://docs.langchain.com/oss/python/langchain/structured-output)
- [langchain-qdrant 官方集成文档](https://docs.langchain.com/oss/python/integrations/vectorstores/qdrant)
- [Qdrant 官方文档（客户端与过滤）](https://qdrant.tech/documentation/)
- [Qdrant Query API 与版本说明](https://qdrant.tech/documentation/concepts/query/)
- [RabbitMQ 可靠性](https://www.rabbitmq.com/docs/reliability)
- [RabbitMQ 消费者确认与发布者确认](https://www.rabbitmq.com/docs/confirms)
- [RabbitMQ 死信交换机](https://www.rabbitmq.com/docs/dlx)
