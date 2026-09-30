# 隔离预测全链路联调

## 验证范围

本工具验证以下真实传输和持久化链路：

```text
本地合成 Feed → Java 同步 → REST 创建批次 → MySQL 请求 outbox
→ RabbitMQ → Python SQLite inbox → 现有策略/HTTP 模型适配器
→ SQLite 结果/失败 outbox → RabbitMQ → Java MySQL 终态 → REST 查询
```

MySQL 与 RabbitMQ 是专用 Compose 容器；Java、Python Worker 和 stdlib HTTP stub 是宿主进程。Feed、圈速和模型输出都是明确标记的合成数据，**不是赛事预测准确性评测，也不是生产模型验收**。官方答案、评分和统计不在本次技术闭环内。

不执行 `git commit`，不连接默认 Nacos、外部 Feed 或生产数据库，不直接写任务/结果表绕过上游。`prepare` 仅创建隔离容器；只有人工核对专属项目并单独执行 `apply-schema` 才初始化空测试库，应用本身不自动迁移数据库。

## 前置条件

- Docker Desktop / 本地 Docker Engine 已启动，Compose 支持 `up --wait`；不允许远程 Docker context。
- Java 21、Maven Wrapper；Python >= 3.12、`uv`。在 `python-agent-worker/` 已安装锁定依赖，worker 以 `uv run --offline` 启动。
- 可取得固定镜像 `mysql:8.4.7`、`rabbitmq:4.1.6-management`。它们不替代生产依赖版本决策。
- 以下回环端口未占用：MySQL `13306`，AMQP `15673`，Rabbit 管理接口 `15672`，Java `18780`，Feed/模型 stub `18781`。
- 没有另一份本工具正在执行；同一 SQLite 文件只运行一个活动 worker。

### Java 构建基线

在 `f1aipredict/` 执行：

```bash
./mvnw test
```

若源码含 Lombok 注解但增量编译报告 `Nothing to compile`，运行类却缺少 getter/builder，先检查 `target/classes` 是否被 IDE 错误桩编译覆盖。不能通过手写 getter 或修改业务断言掩盖构建问题。只有在明确同意删除该模块生成产物后，才执行 `./mvnw clean test`；不要清理其他目录。历史联调曾在单独授权后清理生成产物并重测，以后其他环境的清理仍须各自明确授权。本地完整工作区可能包含被忽略的历史测试，应以当前 checkout 的实际测试报告为准，不把历史工作区的测试数量当作交付后的覆盖数量。

## 运行命令

均在仓库根目录执行：

```bash
# 仅准备专用 MySQL/Rabbit 容器，不执行 DDL；记录输出的项目名
python3 tests/e2e/run.py prepare
python3 tests/e2e/run.py status

# 仅首次创建空库时：人工核对项目名、现有 schema 与 SQL 001–008 后显式执行一次
python3 tests/e2e/run.py apply-schema --project <status显示的完整项目名> --confirm-isolated-schema

# 经真实 Feed 同步、API 创建及两端消息链路验证预测结果
python3 tests/e2e/run.py run

# 查看本工具专用项目与schema状态
python3 tests/e2e/run.py status

# 仅停止本工具的容器；保留所有卷与SQLite证据
python3 tests/e2e/run.py stop
```

`prepare` 生成随机专属 Compose 项目名 `f1predict_e2e_<随机后缀>` 和本地随机口令，不复用其他项目容器，**也不执行 SQL**。MySQL/Rabbit 发布端口只绑定 `127.0.0.1`。`apply-schema` 是单独的人工确认动作，仅对本工具创建、标签/端口已核验且没有表的独立 MySQL 一次性运行 001–008；状态变为 `ready` 后不能重复执行。008 是非幂等增量脚本，生产库仍须按维护流程人工评审执行。

`run` 只接受 `ready` 的已初始化库，不执行 DDL；它从独立 Maven 输出目录构建并启动 Java、启动本地 stub、同步赛程和题目、登记本次快照策略、启动 Python Worker，然后创建批次并验证终态。退出时结束自己启动的应用进程，默认保留 MySQL/Rabbit 容器和数据，不自动删卷。不要使用 `docker system prune` 或全局停止容器处理本工具故障。

如果显式 `apply-schema` 在 DDL 中途失败，脚本保留 `applying` 状态与现场，拒绝盲目重跑非幂等变更；检查日志和已经应用的 schema，由操作者决定如何恢复。不能直接把状态文件改成 `ready` 冒充数据库初始化完成。重复 `prepare` 或 `run` 都不会再次执行 DDL。

## 配置隔离

- Java 使用 `deploy/e2e/application-e2e.yaml` 作为唯一 `spring.config.location`，而不是 `additional-location`，并禁用 Nacos 导入/服务发现。
- 子进程采用环境变量白名单，不继承宿主的生产 Spring、Nacos、F1、`_JAVA_OPTIONS` 等 JVM 注入配置或 Maven 选项；联调所需参数由工具明确传入。仅切换 profile 而继续加载默认 `application.yaml` 不算隔离。
- Python 使用本次本地 AMQP URL、SQLite 文件、合成圈速文件、策略文件和模型地址；不从 Java 的 Nacos 配置自动取值。
- Java 请求发布开关是 `f1.prediction.outbox.enabled`，终态消费开关是 `f1predict.prediction.outcome.enabled`，二者前缀不同。
- Python 推理及结果发送由 `F1_PREDICT_PREDICTION_ENABLED` 控制。FastAPI `/health/ready` 是占位接口，本工具使用独立 worker 就绪探针，最终以 API outcome 判定预测完成。

### 题目状态的显式映射

上游 `Status` 是数字，而预测创建需要业务状态 `OPEN`。真实数字的含义不能从一份样例猜测。

`f1predict.feed.question-status-mapping` 默认为空；未配置的数字保留原值、仍不可创建预测。本地合成 Feed 明确约定数字 `4` 表示本测试的开放题，在**隔离配置**中登记：

```yaml
f1predict:
  feed:
    question-status-mapping:
      4: OPEN
```

这不是对真实上游 `Status=4` 的语义声明，禁止照搬到生产。生产映射应先核对可信 Feed 文档/样例。对已有题目，只更改配置不会触发同步；须明确再次执行题目同步。相同源哈希重同步时仅对来源题号、round/gameday 与单题哈希一致的当前状态作映射对齐，不创建新快照或修改原始 JSON；撤销映射也应把旧 `OPEN` 回退为原数字。不要在业务库直接 UPDATE 状态绕过该约束。

## 检查项与证据

基础场景：

- 支持的合成题成功：批次 `COMPLETED`，结果选项来自冻结快照。
- 未登记题型失败：`UNSUPPORTED_QUESTION`，批次 `FAILED`，不能伪成功。
- 支持/不支持混合且所有任务已终态：按现有批次聚合规则为 `FAILED`（成功 1、失败 1）；`PARTIAL` 仅表示至少一任务仍未终态，不代表成功/失败混合后的最终状态。
- 原始消息重复投递：固定任务/消息身份；重投后等待隔离 RabbitMQ 请求队列 ACK 计数增长和积压清空，再断言终态、receipt 及 Python inbox 仍唯一，不能只等待固定秒数。重投脚本自身核验本次隔离项目、回环 Broker URL 与原 outbox 载荷，不能向其他 Broker 发布。
- MySQL 请求 outbox、Java receipt、SQLite inbox/outbox 与 REST outcome 一致；201 或 Broker confirm 不单独作为成功条件。

运行证据位于 `tests/e2e/.runtime/`，该目录不纳入 Git：

- `state.json`：专属项目与 DDL 状态。
- `compose.env`：本地随机口令，文件权限 0600；不可复制进报告或提交。
- `java.log`、`worker.log`、`stub.log`：排障日志。
- `inbox.sqlite` 及 SQLite WAL：持久接收与结果 outbox 数据，不能只拷主文件作运行中备份。
- `policy.json`、`laps.json`：本次登记策略与合成输入。
- `evidence.json`：通过场景的批次、任务与终态证据。

具体通过范围以本次执行输出及证据为准。尚未实际执行的崩溃窗口、磁盘损坏、生产升级、官方评分等场景不能因基础 E2E 通过而声称已经验证。

## 不启动依赖的回归检查

```bash
python3 -m unittest discover -s tests/e2e -p 'test_*.py' -v
```

双端模块的测试分别执行：

```bash
(cd f1aipredict && ./mvnw test)
(cd python-agent-worker && uv run pytest && uv run ruff check .)
```

这些回归检查不能替代真实 Broker/MySQL 联调。新增代码不引入第三方测试库、数据库迁移框架或模型 SDK。

## 数据库与恢复边界

- 使用现有手工 DDL `001`–`008`，不为测试改表定义。`007` 当前包含物理外键，早期 SQL 约定写“不建外键”；本工具保留实际 DDL，生产使用前另行数据库评审。
- `stop` 不删卷，应用进程退出也不删 SQLite。需要彻底移除本次资源时先查看专属项目、备份证据并明确确认删除范围；工具不提供默认清空行为。
- SQLite ACK 后的数据若丢卷且无有效备份，不能保证无损恢复；当前架构要求稳定本地卷和单活动消费者。
- 旧 job 缺少冻结 outbox 或可靠截止时间时不自动补发；不能用新的题目快照重构原消息。
- 本地样例的安全化不能撤销历史凭据泄露；部署方仍应轮换曾写入文件/历史的真实凭据。
