# 预测请求消费者

消费者是独立进程，**不要**在 Uvicorn/FastAPI 副本的启动钩子中运行。本期只把请求写入 SQLite inbox，ACK 不等于预测成功；现有 HTTP `/health/ready` 仍是占位接口。

## 配置与启动

部署环境设置 `F1_PREDICT_RABBITMQ_URL`（AMQP 用户、虚拟主机由部署方提供，不要写入文件或日志）和 `F1_PREDICT_CONSUMER_SQLITE_PATH`（单机稳定持久卷上的绝对文件路径；父目录预先创建并授予写权限）。可选 `F1_PREDICT_CONSUMER_HEALTH_PATH` 指定同卷上的探针文件。仅运行**一个活动消费者**；不要在不同机器的两个独立 SQLite 文件上并发消费，也不要把 WAL 文件放在网络共享盘上。

```bash
uv run python -m f1_predict.worker.main
uv run python -m f1_predict.worker.main --check-live
uv run python -m f1_predict.worker.main --check-ready
```

探针命令成功退出为 0；连接中断时 ready 失败，进程崩溃后心跳过期则 live/ready 均失败。进程须由部署系统配置重启策略。RabbitMQ 交换机、请求队列、死信队列均为 durable，并采用 `common/config.py` 中的 v2 默认名称；Java 部署配置必须完全一致。`F1_PREDICT_CONSUMER_PREFETCH` 默认 10，消息体大小上限默认 262144 字节，超出大小的消息只保存有界原始内容到 quarantine 再拒绝。上线前在隔离 Broker 验证 DLX/DLQ 的实际路由及持久性。

停机或 SQLite 发生故障时，未 ACK 消息在连接关闭后由 Broker 重投。迁移主机须先停止旧消费者，对 SQLite 执行 WAL checkpoint 并备份/恢复完整数据库，再启动新消费者；不得同时运行两个副本。quarantine 保留坏消息的有界原始载荷供人工排查，DLQ 重放与清理由授权运维人员操作。部署时监控心跳、队列堆积、DLQ 深度、磁盘剩余空间及 quarantine 新增记录。
