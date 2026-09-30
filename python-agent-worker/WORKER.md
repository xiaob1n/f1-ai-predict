# 预测请求消费者

消费者是独立进程，**不要**在 Uvicorn/FastAPI 副本的启动钩子中运行。请求先写入 SQLite inbox 后 ACK，ACK 不等于预测成功；启用预测后由同一独立进程异步领取、冻结特征并写结果 outbox，再在单独的确认通道发布。现有 HTTP `/health/ready` 仍是占位接口。

## 配置与启动

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
