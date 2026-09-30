# Java 服务的 Nacos 配置

本模块使用 Spring Cloud Alibaba Nacos Config（仅配置中心，不注册服务）。Spring Boot 3.5.x 对应 Spring Cloud Alibaba 2025.0.x。生产配置通过 `spring.config.import` 从 Nacos 加载；没有配置条目或连接失败时不会静默回退到本地配置。

## 准备远端条目

1. 确认服务可访问的 Nacos 地址、服务器版本、Namespace ID 和认证方式。使用 `public` Namespace（空 ID）时，2025.0.x 客户端要求 Nacos Server 3.x；其他版本组合须先核对兼容性。
2. 在目标 Namespace 中创建默认 Data ID `f1_ai_predict.yaml`、Group `DEFAULT_GROUP`、格式 `YAML`。当前 `spring.config.import` 固定使用该名称和组，并未读取 `NACOS_DATA_ID`、`NACOS_GROUP`；需要更换时应显式覆盖 `spring.config.import`，不能只设置这两个未绑定的环境变量。若条目已存在，先检查并备份，不直接覆盖。
3. 以 `nacos.example.yaml` 为迁移清单，在 Nacos 条目中填入实际运行值。示例中的 `${F1_*}` 是占位符：可以在 Nacos 端换成实际值，或在 Java 服务部署环境注入对应变量；不可原样发布后在缺少环境变量的服务中启动。数据库、RabbitMQ 和 Feed 等字段都应逐一核对；尤其要依据真实上游契约核实题目数字状态的含义，再在 `f1predict.feed.question-status-mapping` 中显式映射为 `OPEN`/`CLOSED`。该映射默认留空，数字状态不会自动变为可预测的 `OPEN`；不能将隔离合成 Feed 中的数字含义直接用于生产。RabbitMQ 的虚拟主机位于 `spring.rabbitmq.virtual-host`，发布确认、返回回调和 mandatory 投递不可关闭。原仓库配置中的凭据已经存在于历史记录，迁移后仍需要轮换。
4. 当前 `src/main/resources/application.yaml` 的 Nacos 配置是静态属性，并未引用 `NACOS_SERVER_ADDR`、`NACOS_NAMESPACE`、`NACOS_USERNAME`、`NACOS_PASSWORD`；**只设置这些短名称不会覆盖现有连接**。部署时必须通过与 Spring 属性名匹配的环境变量 `SPRING_CLOUD_NACOS_CONFIG_SERVERADDR`、`SPRING_CLOUD_NACOS_CONFIG_NAMESPACE`、`SPRING_CLOUD_NACOS_CONFIG_USERNAME`、`SPRING_CLOUD_NACOS_CONFIG_PASSWORD`（或 `--spring.cloud.nacos.config.server-addr=...` 等对应命令行属性；Spring 的环境变量转换要去掉属性名中的连字符）显式覆盖，并在启动前核对实际目标地址、Namespace 与 Data ID。public Namespace 仍须显式设为空值或通过隔离配置替代静态值；无认证环境也须覆盖静态认证值，不得仅省略环境变量。不要把认证值写入本地 YAML；长期应将默认运行配置的静态连接值迁出并轮换历史凭据。
5. 读取已发布条目并校对 Namespace/Group/Data ID 与必需字段，再在能访问 Nacos、MySQL 的隔离环境使用非 `test` profile 验证导入和拓扑；未完成验证前不启用任务发布。配置导入禁用了自动刷新，更新 Nacos 内容后重启服务以应用变更。

`test` profile 不导入 Nacos，常规单元测试无需连接云端服务。运行真实服务前，先保证 Nacos 配置已发布并且连接信息正确。

## 预测任务消息上线顺序

1. 备份并核对已有 MySQL 表结构，在维护窗口按序手动执行尚未应用的 `sql/007_prediction_request_outbox.sql` 和 `sql/008_prediction_outcome.sql`；新建空库按 `001`–`008` 执行。不要在应用启动时自动执行 DDL；008 的变更不能因为已有表就跳过，也不能未经核查重复执行。
2. 保持 Java 发布开关和新批次入口关闭，部署新版本并验证新建批次的任务与 outbox 行在同一事务产生；界点前的旧 PENDING 任务没有冻结消息，不自动补发。
3. 显式启用 `f1predict.prediction.outcome.enabled`，确认 Java 结果/失败监听器、receipt 和隔离表可用，再启动单个独立 Python 消费进程。Python 使用稳定本地 SQLite 持久卷，并须启用预测/结果发布、配置策略和数据。仅有请求接收器就绪不代表能完成预测。
4. 在隔离环境验证发布确认、mandatory 返回、Python 落盘后 ACK、结果发布、Java 提交后 ACK 和故障重投，再将 `f1.prediction.outbox.enabled` 显式设为 `true` 并重启 Java，最后开放新批次入口。默认扫描间隔 5 秒、租约 60 秒、确认等待 10 秒；请求交换机/队列为 `f1.prediction.request.v2`，路由键 `prediction.request.v2`，死信交换机/队列为 `f1.prediction.dead.v2`，路由键 `prediction.dead.v2`。批次创建返回成功只表示任务及待发布记录写库，只有终态 API 可查询才算预测闭环。
5. 回滚时先暂停创建新批次并关闭发布，再停止不兼容消费者和回退应用；保留 outbox、inbox、持久队列、receipt、隔离记录及未发送记录。旧版若允许继续创建批次会产生无 outbox 的新任务，因此恢复新版或完成受控补录前不得重新开放创建端点。

`007` 当前含物理外键，与早期 `sql/AGENTS.md` 的不建外键约定不同。联调保留现有 DDL，不为迁就测试删约束；生产升级须在数据库评审中确认该约定。这里只描述部署步骤，不代表已执行生产迁移。

## 双端配置核对

| 环节 | Java | Python |
|---|---|---|
| 配置来源 | Nacos 导入；隔离联调用独立 `spring.config.location` 替代 | `F1_PREDICT_*` 环境变量，不自动读取 Java 的 Nacos 配置 |
| 请求发布 | `f1.prediction.outbox.enabled` | 接收消息持久化到 inbox 后 ACK |
| 推理/结果发送 | 无同名推理开关 | `F1_PREDICT_PREDICTION_ENABLED`；关闭会同时暂停推理和已有结果 outbox 发送 |
| 终态消费 | `f1predict.prediction.outcome.enabled` | result/failure v2 队列；确认发布不等于 Java 落库 |
| Broker | `spring.rabbitmq.*` | `F1_PREDICT_RABBITMQ_URL`；必须使用同一虚拟主机和 v2 拓扑 |
| 发布保证 | `publisher-confirm-type=correlated`、`publisher-returns=true`、`template.mandatory=true` | publisher confirms + mandatory |
| 运行状态 | 检查发布/消费功能实际装配，不只看 HTTP 端口 | 独立 worker 探针；FastAPI `/health/ready` 仍是占位 |

示例 `nacos.example.yaml` 只保留环境变量占位，两个消息开关默认关闭。替换示例中的历史凭据不能撤销 Git 历史中的泄露，原凭据仍需由运维轮换。
