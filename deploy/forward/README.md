# 真实前向 DeepSeek 调用：用户自行运行指南

本目录只说明如何由**用户本人**在目标隔离环境中启动一次真实厂商调用的前向预测 Worker。仓库与自动化代理不保存密钥、不代为调用真实模型、Feed、数据库或 RabbitMQ。

## 0. 结论边界（先读）

- 工程上模型调用成功 ≠ 赛前预测有效。有效性还要求：题目真实、`data_cutoff` 早于可信发布时间/锁定时间、圈速数据在截止前已可见且来源可审计。
- 历史采样、历史工程回放（`HISTORICAL_ENGINEERING_REPLAY`）都**不是**真实前向。Worker 在回放模式下拒绝开启厂商调用。
- 在没有真实赛事、可信发布时间与赛后答案证据之前，D 阶段完整验收仍为**阻塞**；本指南只提供运行入口与证据采集方式，不能用于预先标记验收通过。

## 1. 当前实现现状（2026-10-08 离线核对）

| 项 | 现状 |
| --- | --- |
| 启动入口 | `python -m f1_predict.worker.main`（工作目录 `python-agent-worker/`）；`--preflight` 为不联网预检，默认只读，加 `--init-ledger` 才创建账本（见第 5 节）；`--help` 列出全部参数 |
| SDK | `python-agent-worker/.venv` 已装 `openai 2.11.0`、`httpx 0.28.1`（与 `uv.lock` 及 `deepseek` 额外依赖锁定一致）；运行时只确认所需 SDK 符号可导入（范围以 pyproject 的 `openai>=2.11.0` 为准，不建客户端、不联网）；`--preflight` 额外按包元数据报告是否与精确锁定一致（`matchesLockedPins`），不一致只给警告、不拒绝；适配器只在锁定版本上验证过，不一致时由用户决定是否继续 |
| 模型白名单 | `deepseek-flash`、`deepseek-v4-pro`（与官方 Models & Pricing 页一致）；`F1_PREDICT_DEEPSEEK_MODEL` 必须等于 `F1_PREDICT_MODEL_VERSION` 且等于策略文件的 `model_version` |
| 请求形态 | 固定官方端点 `https://api.deepseek.com`，JSON 输出模式、`temperature=0`、关闭 thinking、`max_tokens=512`、SDK 内部重试为 0、响应体上限 32 KiB |
| 调用账本 | 独立 SQLite，发送前持久化意图并预留费用；每个任务最多 3 次（`F1_PREDICT_DEEPSEEK_MAX_CALLS` 取 1–3）；结果不确定的调用不会自动重发 |
| 预算作用域 | 次数与费用预留按单个预测任务（prediction_job_id）计；真实 DeepSeek 入口另把账本固定为**一账本一任务**：首个预留的任务绑定账本，其他任务在写入意图前被拒并以 `MODEL_FAILED` 结束、零外发。绑定持久保存，重启或改配置都不能放宽。没有跨账本的全局费用上限 |

开启厂商调用的门禁与检查时点（任何一项不满足都拒绝启动，且都在连接 Broker 之前）：

1. `F1_PREDICT_MODEL_PROVIDER=deepseek` 且 `F1_PREDICT_VENDOR_API_ENABLED=true`；只开其一都拒绝。
2. `F1_PREDICT_PREDICTION_ENABLED=true` 且执行模式为 `AUDITED`（默认值）。
3. 密钥非空；审批标记 `F1_PREDICT_DEEPSEEK_APPROVAL_ID` 非空。
4. `0 < 每次预留 ≤ 单任务费用上限`，均为整数（微美元）。
5. 策略文件、圈速 fixture 与版本一致性。

启动 Worker 时，第 1–4 项在创建账本、inbox 之前检查；第 5 项在账本与 inbox 已创建之后才检查（会留下空账本/空 inbox 文件）。预检按同样的门禁检查但默认不创建这些文件，并要求已存在的账本与当前配置一致且没有任何调用记录。

本地/替身模型（`local`）及其历史回放不经过上述厂商门禁，也不会构造任何厂商对象。

### 本指南只支持“单题、单批次、单任务”

本指南只覆盖每个 `RUN_ID` **恰好一个题目、一个批次、一个任务、一个新账本**的运行：

- 策略文件只登记一个题目快照，不匹配的请求在调用模型前被拒绝；
- 账本绑定**第一个**发起预留的任务，因此同一账本的总预留不超过 `min(单任务上限, 次数上限 × 每次预留)`，不会被旧队列消息放大；
- 绑定**不是授权**：如果队列里有同题的旧 PENDING/SENDING outbox 或旧消息，它可能先到达并占用账本，你真正想要的新任务反而被拒。因此第 3 节的隔离要求是硬前提，不满足就停止，不要启动；
- 预检要求本次账本为全新（`ledgerReservedCalls=0`），并输出 `budgetScope`、`maxDistinctJobsPerLedger=1` 与 `requiredJavaBatch`；
- 多任务运行不在本指南范围内；不能把多个账本合起来称为有全局费用上限。

## 2. 运行前必须由用户提供的输入

以下内容没有可用默认值，代码与本指南都不替用户猜测：

1. **真实题目与策略文件**（`F1_PREDICT_PREDICTION_POLICY_PATH`）：人工登记的 JSON，`question_snapshot_id`、`question_id`、`question_text`、选项编号/文本、`meeting_key`、`session_keys` 必须与 Java 侧该题最新快照逐字一致。版本组合只支持：
   - 单选车手题：`prompt_version=prompt-v1`、`feature_version=feature-v1`
   - Q1 双人晋级题（`kind=BOTH_ADVANCE_Q1`）：`prompt-q1-v1` / `feature-q1-v1`
2. **审计圈速 fixture**（`F1_PREDICT_PREDICTION_LAPS_PATH`）：JSON 数组，≤500 行，每行恰好含 `recordId, meetingKey, sessionKey, driverNumber, eventTime, firstSeenAt, lapEnd, durationSeconds, isClean, sourceEndpoint, sourceContentHash`。只有 `firstSeenAt`、圈结束时间都不晚于请求 `data_cutoff` 的干净圈会被使用；来源与哈希由用户负责审计。
3. **可信截止时间**：`data_cutoff` 由用户在 Java 创建批次时提交（`POST /api/v1/rounds/{roundId}/prediction-batches`，snake_case 字段 `data_cutoff`、`model_version`、`prompt_version`、`feature_version`）。必须带时区，且早于可信发布/锁定时间；Worker 只信任消息里的这个值。
4. **预算数字**：`F1_PREDICT_DEEPSEEK_RESERVATION_MICRO_USD`（每次调用预留）与 `F1_PREDICT_DEEPSEEK_MAX_COST_MICRO_USD`（单任务上限）必须由用户依据自己的审批给出。估算方法（不是批准值）：官方价格以“美元 / 百万 token”给出，数值上等于“微美元 / token”，因此单次保守预留 ≥ 输入 token 上限 × 输入单价（缓存未命中、高峰）+ 512 × 输出单价（高峰）。上下文上限为 16000 字符。价格以调用当日官方页面为准。
5. **审批标记**：用户自己的审查/审批引用，仅作审计记录，不代表代码已自证授权。
6. **密钥**：用户自有 DeepSeek API Key。

## 3. 目标隔离环境

以下任一项不满足就**停止，不启动 Worker**：

- 专用的 MySQL 库、RabbitMQ vhost 与请求队列，只给本次运行使用；Java 侧 outbox 发布（`f1.prediction.outbox.enabled=true`，当前工作区实现）与本 Worker 指向同一个隔离环境，不要指向生产或共享队列。
- 唯一生产者：只有本次运行的这一个 Java 实例向该队列发布请求。
- 启动 Worker 前，在隔离库中确认没有该题或其他题的旧 PENDING/SENDING outbox 记录、未终态任务，并在 Broker 管理界面确认请求队列与死信队列为空。只在启动前检查一次存在竞态，不能当作硬保证；“一账本一任务”只能限制总调用量，不能保证被处理的是你想要的那个任务。
- 每次运行使用新的运行 ID 和独立目录，例如 `<持久目录>/<RUN_ID>/`，其中：
  - `ledger/`：权限必须是 `700` 且属于当前用户，账本文件由 Worker 以 `600` 创建；路径各级不能是符号链接。
  - `inbox/`：Worker inbox SQLite 与健康文件所在目录，需持久，不要使用默认的 `/tmp/f1_worker_inbox.db`。
- 同一账本文件一旦写入预算配置，之后不同预算会被拒绝；改预算请换新运行 ID 与新账本。

## 4. 环境变量（只列名称）

真实运行必须在**用户自己的终端**设置，不要在 Claude Code 会话里用 `!` 设置或回显，避免密钥进入对话记录。不要执行 `env`、`printenv`、`set -x` 等会打印变量的命令。

| 变量 | 说明 |
| --- | --- |
| `F1_PREDICT_RABBITMQ_URL` | 目标隔离环境 Broker（含凭据，勿回显） |
| `F1_PREDICT_CONSUMER_SQLITE_PATH` | `<RUN_DIR>/inbox/worker.sqlite3` |
| `F1_PREDICT_CONSUMER_HEALTH_PATH` | 可选，默认与 inbox 同目录 |
| `F1_PREDICT_PREDICTION_ENABLED` | `true` |
| `F1_PREDICT_MODEL_PROVIDER` | `deepseek` |
| `F1_PREDICT_VENDOR_API_ENABLED` | `true` 才允许外发；只在启动时读取，运行中修改不生效 |
| `F1_PREDICT_DEEPSEEK_API_KEY` | 密钥 |
| `F1_PREDICT_DEEPSEEK_MODEL`、`F1_PREDICT_MODEL_VERSION` | 白名单内同一模型 ID |
| `F1_PREDICT_PROMPT_VERSION`、`F1_PREDICT_FEATURE_VERSION` | 与策略文件一致 |
| `F1_PREDICT_PREDICTION_POLICY_PATH`、`F1_PREDICT_PREDICTION_LAPS_PATH` | 第 2 节输入 |
| `F1_PREDICT_DEEPSEEK_LEDGER_PATH` | `<RUN_DIR>/ledger/calls.sqlite3` |
| `F1_PREDICT_DEEPSEEK_APPROVAL_ID` | 审批标记 |
| `F1_PREDICT_DEEPSEEK_MAX_CALLS` | 1–3，默认 3 |
| `F1_PREDICT_DEEPSEEK_RESERVATION_MICRO_USD`、`F1_PREDICT_DEEPSEEK_MAX_COST_MICRO_USD` | 用户批准的整数 |
| `F1_PREDICT_MODEL_TIMEOUT_SECONDS`、`F1_PREDICT_MODEL_MAX_ATTEMPTS` | 可选；有效尝试次数取 `min(MODEL_MAX_ATTEMPTS, DEEPSEEK_MAX_CALLS)` |

密钥可用不回显的方式读入当前 shell，例如：

```bash
read -rs F1_PREDICT_DEEPSEEK_API_KEY && export F1_PREDICT_DEEPSEEK_API_KEY
```

## 5. 开始

以下命令的工作目录均为 `/Users/lubinze/Desktop/f1-ai-predict/python-agent-worker`。

1. 离线回归（可在 Claude Code 中用 `!` 运行，不需要任何密钥或外部服务）：

   ```bash
   uv run pytest tests/worker tests/common tests/prediction
   ```

2. 在用户终端设置第 4 节变量后执行不联网预检。预检复用启动门禁、报告 SDK 版本、加载策略与圈速，不连接 Broker、不建 inbox、不发送厂商请求，输出不含密钥与 Broker URL。

   - 默认只读：账本已存在时以 SQLite 一致只读快照核对持久预算、“一账本一任务”绑定与调用数（必须为 0），不迁移或修改账本业务数据；WAL 模式下 SQLite 可能读取或短暂创建 `-shm` sidecar，因此不保证目录内零文件变化。账本不存在时只校验目录权限，**不创建账本**，输出 `ledgerInitialized=false`。此时 Worker 会在第 3 步启动时按当前配置创建账本。
   - 只读预检不是排他授权或运行锁：检查快照之后仍可能有其他进程预留调用。确保只有本次 Worker 使用该账本；预检通过后不要启动并发 Worker，也不要把预检结果当作队列隔离或目标任务授权的证明。
   - 如希望在启动前就冻结账本配置，可显式加 `--init-ledger`：预检通过后创建空账本，并持久冻结预算与绑定（之后改预算必须换新账本）。`--init-ledger` 单独使用会被拒绝。

   ```bash
   set -o pipefail
   uv run python -m f1_predict.worker.main --preflight | tee "${RUN_DIR:?先设置 RUN_DIR}/preflight.json"
   # 可选：显式初始化账本
   uv run python -m f1_predict.worker.main --preflight --init-ledger | tee "${RUN_DIR:?先设置 RUN_DIR}/preflight-init.json"
   ```

   任何缺项都会以非零退出码拒绝（`pipefail` 保证 `tee` 不吞掉退出码；未设置 `RUN_DIR` 时 shell 直接拒绝）。确认输出中的 `model`、`approvalId`、预算、`questionSnapshotId`、`requiredJavaBatch`、`ledgerReservedCalls=0` 符合预期，并检查 `matchesLockedPins` 与 `warnings` 后再继续。Worker 中途异常退出后，用同一组变量直接重启第 3 步即可：账本绑定与已用次数会保留。不要重跑预检（账本已非空会被拒绝），也不要换新账本绕过已用次数。

3. 启动 Worker（前台运行，日志写入本次运行目录）：

   ```bash
   uv run python -m f1_predict.worker.main 2>&1 | tee "${RUN_DIR:?先设置 RUN_DIR}/worker.log"
   ```

4. 在 Java 侧对目标分站**只创建一个批次**，`question_ids` 只填预检输出中的那一个题目 ID（不要用 `all_open_questions`，不要重复提交），并填写可信 `data_cutoff`。提交成功后记录批次 ID 与任务 ID 到本次运行目录；如提交结果不明，先用查询接口确认，不要直接重试创建。

## 6. 停止

- 正常停止：在 Worker 终端按 `Ctrl+C`，或对其进程发送 `SIGTERM`。消费者取消订阅；未确认消息留在队列，已写入 inbox 的任务在用同一 inbox 重启后继续处理。
- 停止时若某次调用正在进行，账本会把它记为 `uncertain`（可能已计费）；该任务不会自动重发，最终以 `MODEL_FAILED` 结束。
- 紧急停用外发：**先**在 Worker 终端按 `Ctrl+C` 或发送 `SIGTERM` 停掉进程，这是唯一立即生效的方式。修改 `F1_PREDICT_VENDOR_API_ENABLED` 只影响下一次启动：设为 `false` 或取消设置后，DeepSeek 配置的 Worker 拒绝启动。可同时关闭 Java outbox 发布阻止新请求。

## 7. 证据采集与脱敏

只采集本次 `RUN_ID` 目录内的产物；分享前确认不含凭据：

```bash
grep -c 'amqp://' "${RUN_DIR:?先设置 RUN_DIR}/worker.log"   # 期望 0；非 0 时先人工删除对应行再分享
```

账本只读摘要（不含密钥、提示原文与租约令牌）：

```bash
sqlite3 -readonly -header -csv "${F1_PREDICT_DEEPSEEK_LEDGER_PATH:?先设置账本路径}" \
  "SELECT prediction_job_id, call_number, attempt, state, retryable, requested_model, returned_model,
          prompt_version, feature_version, data_cutoff, allowed_option_ids, prompt_hash, input_hash,
          reserved_microunits, usage_json, created_at
     FROM model_call_ledger ORDER BY created_at, call_number" > "$RUN_DIR/ledger-summary.csv"
```

Java 侧用 `GET /api/v1/prediction-batches/{batchId}/jobs`、`GET /api/v1/prediction-jobs/{predictionJobId}/outcome` 记录任务终态。证据需包含：预检 JSON、账本摘要、Java 任务终态、策略与圈速文件的哈希、可信发布时间来源。

## 8. 按本次 ID 处理失败

- 账本行与 inbox 是审计依据，不要删除或修改；`uncertain` 行表示可能已计费，必须在厂商控制台按时间核对后人工记录。
- 厂商返回 429/500/503 时账本记为 `rejected` 且 `retryable=1`，在单任务次数与预算上限内自动重试；其他拒绝、结果不确定、预算耗尽均以 `MODEL_FAILED` 终止，不重试。
- 需要重新预测时：换新的 `RUN_ID` 与新账本，在 Java 侧新建批次（新任务 ID），并重新满足第 3 节的隔离条件；旧账本已绑定旧任务，不能复用。
- 如果账本里绑定的 `prediction_job_id` 不是你创建的那个任务，说明隔离失败：立即停止 Worker，按上述方式记录后换新环境重来。
- 只清理本次运行自己创建的目录与队列消息，不触碰其他运行或共享环境的数据。
