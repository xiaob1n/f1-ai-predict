# 预测请求消费者

消费者是独立进程，**不要**在 Uvicorn/FastAPI 副本的启动钩子中运行。请求先写入 SQLite inbox 后 ACK，ACK 不等于预测成功；启用预测后由同一独立进程异步领取、冻结特征并写结果 outbox，再在单独的确认通道发布。现有 HTTP `/health/ready` 仍是占位接口。

## DeepSeek 接入：A 阶段仅离线验证

2026-10-02 的 A 实施选用 **DeepSeek 官方托管 API**，不要求 GPU、下载权重或训练。新增适配器与调用账本通过显式注入的 Fake transport 测试；它们不把现有 `LocalJsonModel` 的私网地址限制改成公网许可。

- `F1_PREDICT_MODEL_PROVIDER` 默认为 `local`，显式选择 `deepseek` 不等于获得外发或付费授权。
- `F1_PREDICT_VENDOR_API_ENABLED` 默认为 false；A 中设为 true 会在配置校验时拒绝。默认 Worker 的 `run` 也在读取 fixture、创建 SQLite、健康文件或连接 Broker 之前拒绝 DeepSeek 运行，不能通过修改可变 Settings 绕过。
- A 不定义或自动读取 DeepSeek API key 环境字段。测试在代码中显式传入非真实 `SecretStr`，真实秘密管理与收费调用仍待 C 独立批准。
- 官方端点固定为 `https://api.deepseek.com/chat/completions`；当前核对的适配器白名单为 `deepseek-flash` / `deepseek-v4-pro`，不默认选择其中任何一个。服务端别名不证明固定权重版本。
- DeepSeek JSON Output 不是严格 JSON Schema 保证。请求显式关闭 thinking 和 streaming，模型候选仍通过本地严格 Pydantic、单选与冻结选项白名单校验；拒绝、截断、空内容和非法输出不能成为成功结果。

离线 DeepSeek 配置还必须明确登记以下参数，与策略和注入适配器一致：

| 配置 | 约束 |
|---|---|
| `DEEPSEEK_MODEL`、`MODEL_VERSION` | 官方 ID 且两者相同，不复用 `MODEL_URL` |
| `PROMPT_VERSION`、`FEATURE_VERSION` | 与既有登记策略完全一致，不自动选择或降级 |
| `DEEPSEEK_LEDGER_PATH` | 显式私有 SQLite 文件，配置解析本身不建库 |
| `DEEPSEEK_APPROVAL_ID` | 离线预算审查引用；这个字符串不是实时操作授权 |
| `DEEPSEEK_MAX_CALLS` | 每 job 1–3 次潜在收费请求，包含结果不明请求，重启不重置 |
| `DEEPSEEK_MAX_COST_MICRO_USD` | 正的单 job 保守费用预留上限，默认 0 |
| `DEEPSEEK_RESERVATION_MICRO_USD` | 正的单请求预留，不能超过单 job 上限，默认 0 |

表中配置名均带 `F1_PREDICT_` 前缀。预算字段拒绝布尔值和小数，允许部署环境的整数字面量。预留来自审查值，不是厂商报价、实际 token 账单或不重复计费保证。

处理器通过 `DeepSeekModel.bind(ModelInvocation(...))` 绑定持久 claim 的 job/attempt/lease 与冻结截止/选项/版本。领取序号可能因存储故障超过 3；真正的厂商请求次数由账本 `call_number` 和预算单独限制，不能因“第 4 次领取”丢弃已成功的第 3 次候选。调用账本独立于 Inbox schema：先持久化 INTENT 和费用预留，再发送；成功候选可在处理终态写入失败后的租约恢复中复用。发送后超时、断线或崩溃留下的 INTENT/UNCERTAIN 不自动再次请求。明确认证/余额/权限或参数错误直接形成可靠失败事件；仅明确的 429/500/503 可进入受同一请求上限约束的有限退避。缺 token 用量标 `UNKNOWN`，不当作零费用释放预算。Broker Outbox 重发不再次调用模型，仍不承诺端到端 exactly-once。

可选依赖 `deepseek` 已在 `pyproject.toml` / `uv.lock` 声明，固定 OpenAI 兼容 SDK `2.11.0`（DeepSeek 官方示例使用该 SDK），不是默认依赖。SDK transport 懒加载，禁用 SDK 重试、环境代理和重定向，要求 TLS；**本次实际安装被权限拒绝，未安装或实测 SDK，也没有发起真实 API 请求**。Fake SDK 契约测试不能代替实际安装兼容性与 C 验收。

已有虚拟环境中可运行以下离线回归，不触发依赖同步或任何真实资源操作：

```bash
# 在 python-agent-worker/ 执行
.venv/bin/python -m pytest -q tests/common/test_deepseek_config.py \
  tests/prediction/test_model_calls.py tests/prediction/test_model_vendor_api.py \
  tests/prediction/test_vendor_processor.py tests/worker/test_vendor_bootstrap.py
```

Inbox 和调用账本都需要专属稳定持久卷及一致性备份，数据库文件 0600、账本父目录 0700；不得通过删除账本、更换路径或复制空库来清空预算。账本或 Inbox 丢失后不能承诺自动恢复所有已 ACK 任务、已知全部费用或供应商 exactly-once，需要先做人工核验与对账。

B/H1 的真实历史数据/隔离资源验收和 C/H2 的真实外发、秘密注入及收费预算必须另行批准；不能按本节配置直接启动真实 DeepSeek Worker。

## 配置与启动（既有私网／替身模式）

部署环境设置 `F1_PREDICT_RABBITMQ_URL`（AMQP 用户、虚拟主机由部署方提供，不要写入文件或日志）和 `F1_PREDICT_CONSUMER_SQLITE_PATH`（单机稳定持久卷上的绝对文件路径；父目录预先创建并授予写权限）。可选 `F1_PREDICT_CONSUMER_HEALTH_PATH` 指定同卷上的探针文件。仅运行**一个活动消费者**；不要在不同机器的两个独立 SQLite 文件上并发消费，也不要把 WAL 文件放在网络共享盘上。

```bash
uv run python -m f1_predict.worker.main
uv run python -m f1_predict.worker.main --check-live
uv run python -m f1_predict.worker.main --check-ready
```

探针命令成功退出为 0；连接中断时 ready 失败，进程崩溃后心跳过期则 live/ready 均失败。进程须由部署系统配置重启策略。RabbitMQ 交换机、请求队列、死信队列均为 durable，并采用 `common/config.py` 中的 v2 默认名称；Java 部署配置必须完全一致。`F1_PREDICT_CONSUMER_PREFETCH` 默认 10，消息体大小上限默认 262144 字节，超出大小的消息只保存有界原始内容到 quarantine 再拒绝。上线前在隔离 Broker 验证 DLX/DLQ 的实际路由及持久性。

`F1_PREDICT_PREDICTION_ENABLED` 默认为 false；启用前必须设置 `F1_PREDICT_PREDICTION_POLICY_PATH`（仅一个人工登记的单选快照）、`F1_PREDICT_PREDICTION_LAPS_PATH`（已审计且每条记录带可信首次采集时间的本地不可变 JSON 圈速夹具）、`F1_PREDICT_MODEL_URL`（本机或私有 IP 的兼容结构化 JSON schema 响应端点），以及与策略完全一致的 `F1_PREDICT_MODEL_VERSION`、`F1_PREDICT_PROMPT_VERSION`、`F1_PREDICT_FEATURE_VERSION`（旧车手题型分别为 `prompt-v1` / `feature-v1`；Q1 双车题型分别为 `prompt-q1-v1` / `feature-q1-v1`）。数据夹具不是 MongoDB 在线适配器；真实 Mongo 集合/首次采集元数据、真实题目及模型能力仍需核实，未核实前不要开启。Result/failure 分离的 v2 持久队列由进程声明；Java 已实现终态消费、幂等落库和单任务 outcome 查询，但须显式启用 `f1predict.prediction.outcome.enabled` 并核对 `sql/008_prediction_outcome.sql` 已部署。Broker 确认只表示进入队列，只有 Java 提交终态且 API 可查询才算预测闭环。关闭功能开关同时暂停新任务推理及已有 outbox 发送，保留 SQLite 和队列。部署前备份 SQLite 及 WAL；程序首次打开旧库会创建 `.pre-migration-v2.bak` 一致性备份并事务迁移，演练备份恢复后再上线。

### Q1 双车晋级是／否策略

对于“Will both Williams cars get out of Q1?”，策略文件必须显式使用 `kind: "BOTH_ADVANCE_Q1"`，登记请求中的 `question_snapshot_id`、`question_id`、`question_text`、`meeting_key`、`year`、`round_id`、赛前练习 `session_keys`、`qualifying_session_key`（请求为 null 时写 null）、`qualifying_start_at`，以及真实 `yes_option_id` / `no_option_id` 和逐字匹配的 `yes_option_text` / `no_option_text`。还要登记两名目标车手的 `target_drivers`、完整参赛车号 `participant_drivers`、赛事规则对应的 `q1_advancement_slots`、`minimum_laps`、`model_version`、`prompt_version: "prompt-q1-v1"`、`feature_version: "feature-q1-v1"`。以上均为策略配置字段，不是已查证的迈阿密真实 ID 或晋级名额；仓库不附带生产策略。未经核实不得启用。

Q1 策略只读取截止前可见的练习赛完整阵容圈速：任一参赛车手覆盖不足时持久发布 `INSUFFICIENT_DATA`，不会将缺数当成“否”。离线基线以完整阵容中位圈速排名作可重复对照，但不等同于正式排位赛结果或已校准概率。请求时的 `questionType=UNKNOWN` 本身不决定题型，必须由登记快照、选项及策略共同匹配。真实数据、模型结构化输出及隔离 Broker 尚须另行联调。

停机或 SQLite 发生故障时，未 ACK 消息在连接关闭后由 Broker 重投。迁移主机须先停止旧消费者，对 SQLite 执行 WAL checkpoint 并备份/恢复完整数据库，再启动新消费者；不得同时运行两个副本。quarantine 保留坏消息的有界原始载荷供人工排查，DLQ 重放与清理由授权运维人员操作。部署时监控心跳、队列堆积、DLQ 深度、磁盘剩余空间及 quarantine 新增记录。
