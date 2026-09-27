# Java 服务的 Nacos 配置

本模块使用 Spring Cloud Alibaba Nacos Config（仅配置中心，不注册服务）。Spring Boot 3.5.x 对应 Spring Cloud Alibaba 2025.0.x。生产配置通过 `spring.config.import` 从 Nacos 加载；没有配置条目或连接失败时不会静默回退到本地配置。

## 准备远端条目

1. 确认服务可访问的 Nacos 地址、服务器版本、Namespace ID 和认证方式。使用 `public` Namespace（空 ID）时，2025.0.x 客户端要求 Nacos Server 3.x；其他版本组合须先核对兼容性。
2. 在目标 Namespace 中创建默认 Data ID `f1_ai_predict.yaml`、Group `DEFAULT_GROUP`、格式 `YAML`。若使用其他名称或组，分别通过 `NACOS_DATA_ID`、`NACOS_GROUP` 覆盖。若条目已存在，先检查并备份，不直接覆盖。
3. 以 `nacos.example.yaml` 为迁移清单，在 Nacos 条目中填入实际运行值。示例中的 `${F1_*}` 是占位符：可以在 Nacos 端换成实际值，或在 Java 服务部署环境注入对应变量；不可原样发布后在缺少环境变量的服务中启动。数据库、RabbitMQ 和 Feed 等字段都应逐一核对；RabbitMQ 的虚拟主机位于 `spring.rabbitmq.virtual-host`，发布确认、返回回调和 mandatory 投递不可关闭。原仓库配置中的凭据已经存在于历史记录，迁移后仍需要轮换。
4. 部署 Java 服务时必须传入 `NACOS_SERVER_ADDR`（host:port），并按目标环境传入 `NACOS_NAMESPACE`、`NACOS_USERNAME`、`NACOS_PASSWORD`。public Namespace 可不设 `NACOS_NAMESPACE`；无认证时可不设用户名和密码。不要把认证值写入本地 YAML。
5. 读取已发布条目并校对 Namespace/Group/Data ID 与必需字段，再在能访问 Nacos、MySQL 的隔离环境使用非 `test` profile 验证导入和拓扑；未完成验证前不启用任务发布。配置导入禁用了自动刷新，更新 Nacos 内容后重启服务以应用变更。

`test` profile 不导入 Nacos，常规单元测试无需连接云端服务。运行真实服务前，先保证 Nacos 配置已发布并且连接信息正确。

## 预测任务消息上线顺序

1. 备份并核对已有 MySQL 表结构，在维护窗口手动执行 `sql/007_prediction_request_outbox.sql`；不要在应用启动时自动执行 DDL。
2. 保持 Java 发布开关关闭，部署新版本并验证新建批次的任务与 outbox 行在同一事务产生；界点前的旧 PENDING 任务没有冻结消息，不自动补发。
3. 启动单个独立 Python 消费进程，挂载稳定的本地 SQLite 持久卷，确认 RabbitMQ 请求队列与死信队列的持久拓扑及消费者健康状态。
4. 在隔离环境验证发布确认、mandatory 返回、Python 落盘后 ACK 和故障重投，再将 `f1.prediction.outbox.enabled` 显式设为 `true` 并重启 Java。默认扫描间隔 5 秒、租约 60 秒、确认等待 10 秒；请求交换机/队列为 `f1.prediction.request.v2`，路由键 `prediction.request.v2`，死信交换机/队列为 `f1.prediction.dead.v2`，路由键 `prediction.dead.v2`。批次创建返回成功只表示任务及待发布记录写库，不表示 Python 已完成预测。
5. 回滚时先关闭发布开关并暂停创建新批次，再回退 Java；保留 outbox、inbox、队列和未发送记录。旧版若允许继续创建批次会产生无 outbox 的新任务，因此恢复新版或完成受控补录前不得重新开放创建端点。
