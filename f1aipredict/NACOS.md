# Java 服务的 Nacos 配置

本模块使用 Spring Cloud Alibaba Nacos Config（仅配置中心，不注册服务）。Spring Boot 3.5.x 对应 Spring Cloud Alibaba 2025.0.x。生产配置通过 `spring.config.import` 从 Nacos 加载；没有配置条目或连接失败时不会静默回退到本地配置。

## 准备远端条目

1. 确认服务可访问的 Nacos 地址、服务器版本、Namespace ID 和认证方式。使用 `public` Namespace（空 ID）时，2025.0.x 客户端要求 Nacos Server 3.x；其他版本组合须先核对兼容性。
2. 在目标 Namespace 中创建 Data ID `f1aipredict.yaml`、Group `DEFAULT_GROUP`、格式 `YAML`。若条目已存在，先检查并备份，不直接覆盖。
3. 以 `nacos.example.yaml` 为迁移清单，在 Nacos 条目中填入实际运行值。示例中的 `${F1_*}` 是占位符：可以在 Nacos 端换成实际值，或在 Java 服务部署环境注入对应变量；不可原样发布后在缺少环境变量的服务中启动。数据库、RabbitMQ 和 Feed 等字段都应逐一核对。原仓库配置中的凭据已经存在于历史记录，迁移后仍需要轮换。
4. 部署 Java 服务时传入 `NACOS_SERVER_ADDR`（host:port）以及所需的 `NACOS_NAMESPACE`、`NACOS_USERNAME`、`NACOS_PASSWORD` 环境变量。public Namespace 可不设 `NACOS_NAMESPACE`；无认证时可不设用户名和密码。不要把认证值写入本地 YAML。
5. 读取已发布条目并校对 Namespace/Group/Data ID 与必需字段，再在能访问 Nacos、MySQL 的环境启动服务。配置导入禁用了自动刷新，更新 Nacos 内容后重启服务以应用变更。

`test` profile 不导入 Nacos，常规单元测试无需连接云端服务。运行真实服务前，先保证 Nacos 配置已发布并且连接信息正确。
