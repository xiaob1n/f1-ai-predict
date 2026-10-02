# 真实数据单题 MVP：离线实现与运行边界

## 当前交付

本版补齐了**注入式源适配器、不可执行 staging/原子封签、隔离 SQL 装载、专属部署模板、单题编排与离线恢复模拟**。默认 CLI 不创建真实连接；`export / prepare-isolated / load-isolated / run-stub / run-real-model` 仍明确拒绝。**这不表示真实 M1 或 M2 已通过。**

本轮只获准补齐离线实现与本地测试，没有读取真实数据库或凭据、启动 Docker/Java/Worker 服务、运行真实 DDL/DML、连接 Rabbit 或调用真实模型。`dry-run` 使用合成身份和圈速、本地文件/SQLite、确定性替身；MySQL/HTTP/Broker/进程部分是内存模拟。数据库驱动未新增到依赖清单，真实驱动兼容性与连通性未验证。

## 本次提交范围

按要求，本次 Git 提交仅包含非测试源码、部署模板和本手册，不包含 `test/tests` 目录、测试文件及测试放行相关 `.gitignore` 改动。配套 CLI `tests/real_data_mvp/run.py` 和新增回归测试仅保留在本地工作区；**干净检出本次提交不能直接执行以下 CLI 命令，也不能据此复现下文全部测试数量**。源码中的回放与模拟模块随提交交付，但不能把它们等同于已交付的真实资源运行入口。

## 完整本地工作区的离线命令

以下命令只适用于保留了未提交 CLI 的完整本地工作区。在仓库根执行（Worker 环境已有依赖时也可将 `uv run --offline --project python-agent-worker python` 换成 `python-agent-worker/.venv/bin/python`）：

```bash
# 无真实连接，默认在 home 的私有临时目录运行，退出后清理本次临时模拟文件
uv run --offline --project python-agent-worker python tests/real_data_mvp/run.py dry-run

# 显式保留模拟证据；必须指定新的专属目录，不覆盖默认目录或已有目录
uv run --offline --project python-agent-worker python tests/real_data_mvp/run.py dry-run \
  --keep-evidence --run-dir "$HOME/.f1-mvp-simulation-review-001"

# 只读查看保留的模拟状态，不去真实数据库核验
python3 tests/real_data_mvp/run.py verify --run-dir "$HOME/.f1-mvp-simulation-review-001"
python3 tests/real_data_mvp/run.py stop --run-dir "$HOME/.f1-mvp-simulation-review-001"
python3 tests/real_data_mvp/run.py inventory
```

模拟成功报告包含 `mode=OFFLINE_SIMULATION`、`stage=SIMULATED_COMPLETE`、`postCalls=1`、`simulatedRecoveryVerified=true`，同时**必须**包含 `realResourceAccess=false`、`realModelCalls=false`、`m1Verified=false`、`m2Verified=false`。不要把模拟 SQL 计数、模拟 HTTP 结果或重开本地 SQLite 当作真实 Java/Rabbit/MySQL 证据。

保留证据目录中有 `workflow.json`、`staging-*`、封签 `bundle/` 和 `simulation.sqlite`。`verify` 仅接受私有的模拟状态；真实模式/权限不合格/路径链接会被拒绝。CLI `stop` 不操作 Docker 或 PID，因为该入口从未启动外部进程；模拟文件予以保留，不自动删除。

## 各层实现与约束

### 1. 源适配器：`replay/source.py`

- `MongoLapSource` 包装调用者显式注入的 collection/database；只转发固定 `laps` 范围、白名单投影、5 秒超时和 501 上限，关闭游标。构造时不连接。
- `MySqlReplaySource.read_capture(question_id, snapshot_id, option_ids)` 使用固定参数化 SELECT，不读取 `is_answer` 或完整 `raw_json`。快照只提取允许的题干字段；返回 question/snapshot/season/round/sessions/options。
- MySQL 读取要求服务器确认事务只读、UTC、无活动角色和正确数据库，再以实际 `SHOW GRANTS` 验证允许的 SELECT 权限。当前权限检查保守，只接纳实现支持的显式授予形状；不支持的角色/权限形式拒绝，不自行放宽。
- 退出总是尝试 rollback/close；清理失败也不返回成功。异常不携带 SQL、地址或凭据。
- **ID 含义分离**：`source.question.id` 是源 MySQL 主键；`source.question.sourceQuestionId` 是 Feed ID；`identity.sourceQuestionId` 对应源 MySQL 主键。真实隔离映射由装载读回，不假定源/目标主键相同。

源 adapter 不生成或认证赛事身份。可信 Practice 类型、日期及跨源 meeting 映射须由调用者另行核验并提供；哈希只证明内容一致，不能把自填 identity 当作来源认证。

### 2. 捕获与封签：`replay/bundle.py`

`capture_staging` 在调用者提供的私有根目录下独占创建 staging，先写入并 fsync 安全来源和原始圈速，再记录 `importCompletedAt`，最终写入哈希元数据和 READY 状态。没有实际隔离 ID 的 staging **不含 manifest，不可执行**。

捕获失败保留可辨识的 FAILED 状态和已经持久化的来源；`load_staging` 只接纳 READY 且时钟/哈希一致的数据。不得修改时间后将失败捕获伪装为成功，也不得把源 `date_start`、ObjectId 或赛事时间充当历史首次可见时间。`sourceFirstSeenStatus=UNKNOWN` 始终保留。

装载读回 ID 后才调用 `seal_bundle`：生成 SnapshotPolicy、原始数据/11 字段 fixture/identity/policy sidecar，在同文件系统临时目录校验完成后原子发布。文件 0600、目录 0700，拒绝符号链接、旧 E2E runtime 和覆盖已有目标。首次观察时间从 staging 复用，不随封签、重投或恢复改变。

Worker 启动必须从独立审查记录配置 `F1_PREDICT_PREDICTION_REPLAY_MANIFEST_HASH`（`manifest_sha256` 的 canonical 摘要）。Worker 每次处理前验证包、策略、题干/选项映射及原始圈速派生字段；部署 hash 仅锁定已批准包，仍不替代来源真实性或资源授权。

### 3. 隔离装载与模板

`replay/isolated.py` 提供 `TargetIdentity`、`schema_script_plan` 与 `IsolatedLoader`。接收已创建的目标 DB-API connection，代码本身不建连接、不执行 DDL。目标身份包含 scope/project/service、源/目标独立资源标识和 schema 审查记录；字段本身不自证所有权，未来运行时须独立核验实际资源。

装载前检查 information_schema 中的表、必要列、007 outbox/008 receipt 的唯一键及约束名称，随后以参数化 SQL 短事务装入最小副本。只将隔离题目设为 OPEN，源状态保留在本地审计材料。恢复通过自然唯一键与内容读回实际映射，commit 响应不明时不盲目重插。option 映射使用 `question_option.option_id`，不是选项行主键。

**已知装载限制**：当前异常回读不只用于 commit 结果不明，且对快照元数据的比对未覆盖全部字段。纯内存复核已确认：既有快照的 `created_at` 或 `snapshot_reason` 不一致时，装载检测到冲突并回滚后，回读仍可能返回成功。因此不得将当前恢复逻辑描述为完整冲突保护，也不能据此宣称完全相同导入已核验；真实运行前须限定仅对提交结果不明进行恢复，并补齐 `snapshot_no`、`snapshot_reason`、`created_at` 等元数据比对及回归验证。本次提交只记录该限制，未修复此逻辑。

专属模板位于 `deploy/real-data-mvp/`：

- Compose project/标签使用必填 `MVP_PROJECT_NAME`，项目名前缀 `real-data-mvp-`，命名卷按项目隔离；本机端口仅绑定回环。
- Java 配置必须作为**唯一** `spring.config.location`，禁用 Nacos 和定时同步，启用请求 outbox、publisher confirm/returns/mandatory 与终态消费。Feed 是不可达本机占位，不运行合成 Feed、不猜数字状态映射。
- MySQL/Rabbit 凭据只有专属环境占位；不能继承默认生产配置。`workflow.isolated_environment/worker_environment` 构造白名单环境，剔除继承的代理、JVM、Spring、源数据库和真实模型配置。历史 stub 禁止配置模型 URL。Rabbit vhost `/f1predict-mvp` 在 AMQP URL 中使用 `/%2Ff1predict-mvp` 路径；不编码会指向不同的 vhost。
- schema 白名单为 **001、002、003、004、006、007、008**，排除评分 005。本轮只规划/模拟这些 MySQL 脚本，不曾实际执行；初始化中断后状态阻止自动重跑，特别是不重复应用 008。本地 SQLite 表结构由既有 `InboxStore` 在模拟专属目录正常初始化。

不得复用 `tests/e2e/.runtime` 或执行旧 E2E 的 prepare/apply-schema/run 来代替本流程。旧 E2E 使用合成 Feed，不能证明真实数据质量。

### 4. 编排与恢复：`replay/workflow.py`

所有外部操作均通过显式注入目标；当前版本只接纳 `simulated=True` 的目标，真实目标仍拒绝。状态按私有文件原子保存。

- schema 初始化前校验专属标签/回环/空库；先保存 APPLYING 意图，中断进入 SCHEMA_UNCERTAIN 并拒绝自动重跑。
- 装载输入 hash 不变，实际 ID 后才绑定 sealed 包。
- POST 前先落提交意图，单题请求不使用 allOpenQuestions；响应丢失保留 SUBMISSION_UNCERTAIN，不再 POST。
- 终态轮询默认有界 120 秒，同时处理失败终态、进程退出、查询异常及超时。201、ACK 或 confirm 不等于成功。
- 对账任务、批次、合法选项、结果/receipt 唯一、SQLite inbox/outbox、manifest/frozen hash、版本和双 cutoff。
- 原 messageId/body 重投，一次模拟 Worker 恢复重新打开同一 SQLite，核对冻结输入和唯一终态未变。
- 停止接口先对全部目标比对已持久化的 PID/启动 token/项目/运行 ID；失配时不停止任何进程，不删除卷。

## 真实运行仍需的条件

完整本地工作区的配套 CLI 在真实资源阶段返回退出码 **2**，标准错误以 `BLOCKED:` 开头，不提供跳过授权参数；该 CLI 未纳入本次提交。接入真实 runtime 和运行前，需分别落实：

1. 本次有界源导出的明确授权及服务器侧最小权限；只读盘点许可不自动扩展。
2. 可核验的 Practice 类型、日期及 MySQL meeting `1174` ↔ Mongo meeting `1293` 来源材料；候选 `11355/11356` 不能凭编号相邻认定。
3. 新专属资源/DDL/隔离写入授权，真实 Docker context、标签、端口、账号及空库/schema 核验。
4. 经审核的真实连接工厂与 command/HTTP/Broker 目标绑定。当前提供薄驱动适配器和可注入编排，不自动发现服务或创建连接；这一接线及真实恢复演练尚未执行验证。
5. M2 单独确认模型端点、部署身份、HTTP 契约和调用预算；不复用 M1 替身证据。

## 测试与证据

以下命令及数量对应包含本地未提交测试/CLI 的完整工作区验证，不是仅检出本次提交后的测试覆盖保证。

```bash
# Python 模块内：不依赖真实服务
cd python-agent-worker
uv run pytest -q --tb=short
uv run ruff check . ../tests/real_data_mvp

# 仓库根：runner 本地门禁
python3 -m unittest discover -s tests/real_data_mvp -p 'test_*.py' -v
git diff --check
```

离线测试包含 Fake 驱动、私有暂存目录、参数化 SQL 及不明提交模拟、路径/哈希/身份污染、DDL 中断、POST 最多一次和本地 SQLite 重投恢复。网络/外部进程调用在模拟集成测试中被替换为失败断言。

本轮离线验证记录（2026-10-02）：

- Python 全量 `uv run --offline pytest -q --tb=short`：**281 passed**，另有 2 项 FastAPI/Starlette 依赖弃用警告。
- runner `unittest discover`：**14 passed**。
- Python 模块及 runner 的 Ruff 检查、`git diff --check`：通过。
- CLI 临时运行和 `--keep-evidence` 运行均通过；保留状态的 `verify/stop` 自检通过。单次模拟 POST、原消息重投及 SQLite 重开后冻结输入/终态一致；五个真实资源阶段仍以退出码 2 拒绝。
- 所有新增测试在完整本地工作区已放行 Git ignore，临时缓存仍忽略；按本次提交范围，测试、配套 CLI 和相关 ignore 改动仅保留本地，不纳入提交。

2026-09-30 的 Java 主源码编译通过；resolver 定向测试当时 7 errors，报 `Unresolved compilation problems`（Lombok builder/setter 等生成方法），不能报告该单测已通过。本轮离线补齐不修改 Java 业务或绕过被拒绝的构建清理。

**M1、M2、真实 MySQL/Rabbit/Java 单题重投及重启恢复均未验收。**历史工程回放的分数未经校准、clean 规则只是工程代理，不是官方圈速判定，不得声称赛前准确率或真实模型能力。本次源码提交不包含测试目录或配套 CLI，不改变这些验收边界。
