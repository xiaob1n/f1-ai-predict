# 真实数据单题 MVP：离线实现与运行边界

## 当前交付

当前工作区已在已有**注入式源适配器、不可执行 staging/原子封签、隔离 SQL 装载、专属部署模板、单题编排与离线恢复模拟**基础上完成 A 离线实现与 Fake 回归：增加包内 CLI、显式连接/runtime、DeepSeek 适配器与持久调用预算。默认 CLI 不创建真实连接；`export / prepare-isolated / load-isolated / run-stub / run-real-model` 仍明确拒绝。**这不表示真实 M1/H1 或 M2/H2 已通过。**

本轮只获准 A 离线源码与测试，没有读取真实数据库或凭据、启动 Docker/Java/Worker 服务、运行真实 DDL/DML、连接 Rabbit 或调用真实模型。`dry-run` 使用合成身份和圈速、本地文件/SQLite、确定性替身；MySQL/HTTP/Broker/进程部分是内存模拟。数据库驱动未新增，真实驱动兼容性与连通性未验证。DeepSeek 的可选 SDK 声明与锁文件已增加，但实际安装被权限拒绝；未安装 SDK，Fake 契约测试不能代替真实 SDK 或 C 的付费调用验收。

## 已提交版本与本地修复范围

已提交版本 `e37bd90` 按要求仅包含非测试源码、部署模板和本手册，不包含 `test/tests` 目录、测试文件及测试放行相关 `.gitignore` 改动。配套 CLI `tests/real_data_mvp/run.py` 和新增回归测试仅保留在本地工作区；**干净检出该提交不能直接执行以下 CLI 命令，也不能据此复现下文全部测试数量**。源码中的回放与模拟模块随该提交交付，但不能把它们等同于已交付的真实资源运行入口。后续装载修复与新增回归保留在本地工作区，未自动创建新提交。

## 完整本地工作区的离线命令

以下命令适用于当前完整本地工作区；包内入口不再依赖测试 runner，旧脚本保留兼容。历史提交 `e37bd90` 本身没有新增包入口。在仓库根执行（已有虚拟环境的命令不执行依赖同步；不要因为 SDK 仍未安装就自动执行 `uv sync --extra deepseek`）：

```bash
# 稳定包入口：只读盘点；不读取厂商密钥、不连接真实资源
python-agent-worker/.venv/bin/python -m f1_predict.replay.cli inventory
python-agent-worker/.venv/bin/python -m f1_predict.replay.cli --help

# 以下旧 runner 命令保留兼容
# 无真实连接，默认在 home 的私有临时目录运行，退出后清理本次临时模拟文件
python-agent-worker/.venv/bin/python -m f1_predict.replay.cli dry-run --schema-root "$PWD/sql"

# 显式保留模拟证据；必须指定新的专属目录，不覆盖默认目录或已有目录
python-agent-worker/.venv/bin/python -m f1_predict.replay.cli dry-run --schema-root "$PWD/sql" \
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

**本地装载修复（尚未提交）**：`e37bd90` 中“检测快照元数据冲突并回滚后仍可能回读成功”的缺陷已在当前工作区修复。只有 `commit()` 本身抛错且 `rollback()` 成功结束本地事务后，才允许只读恢复；提交前校验、SQL 或最终计数异常直接传播原错误，回滚失败不把同连接可见行当作持久化成功，也不再次写入或提交。

正常装载和恢复共用快照字段比对：`snapshot_no`、`content_hash`、类型敏感的 `raw_json`、`snapshot_reason=INITIAL` 和 UTC 规范化后的 `created_at`；恢复同时核对 season 的空起止日期，避免绕过正常匹配约束。每次装载清空前次审计，只有本次明确成功才记录新的审计结果。该修复通过本地 Fake DB-API 回归，**不代表真实 MySQL 驱动、事务行为或 M1/M2 已验证**。

JSON 比对为布尔、数字、字符串、null、数组和对象保留独立类型，拒绝 `true`/`1` 混淆及 NaN/Infinity。JSON 文本中的小数通过 `Decimal` 解码和比较，避免高精度数字被 `float` 舍入后误报相等；同时保留有限数字 `1`/`1.0`、对象键顺序、空白及文本/映射表示的合法等价。新增回归覆盖普通重入和真实走 Fake SQL 读回的不明提交恢复两条路径。

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

### 5. A 的显式运行边界与厂商接线（当前工作区）

- `f1_predict.replay.cli` 是稳定包入口，旧 `tests/real_data_mvp/run.py` 是兼容薄入口。`inventory/help/verify/stop` 不连接服务；`dry-run` 必须显式给出 `--schema-root`，不从安装目录猜 SQL 来源。该参数只供离线模拟，不授权实际 DDL。
- `ConnectionPlan` / `open_connection` 只接受调用者注入的工厂和独立授权检查器，不自动发现资源或读取环境秘密。源 Mongo 的 archive 数据库需显式提供，`laps` 是固定集合，不能作为数据库名推断。隔离 MySQL/RabbitMQ 端口对齐专属模板 **13316 / 15683**，管理端口 **15682**；配置字符串不能证明实际标签、回环或资源所有权。
- 新 `RuntimeTarget.simulated=False`，不能冒充旧 `ReplayWorkflow` 的模拟目标。`OperationJournal` / `ReplayRuntime` 的 **OFFLINE_ADAPTER_TEST** 是独立 Fake 验证模式，私有状态文件与旧 **OFFLINE_SIMULATION**、真实运行记录不得混用。DDL、单题 POST、原消息重投、重启和整组停止都在外部动作前记录意图；不明结果不自动重复。
- Default Worker 的 DeepSeek 运行在创建 Inbox/Broker/健康文件之前拒绝。离线处理仅允许显式注入 `DeepSeekModel`；它绑定持久任务 claim 与冻结选项/版本，调用前预留单 job 预算，成功候选恢复复用，INTENT/UNCERTAIN 禁止盲目再发送。必要配置和 SDK 未验证边界见 `python-agent-worker/WORKER.md`。
- **SDK 实际安装仍被拒绝、真实驱动和真实 API 没有验证**；这里的 Fake 计数、私有日志和配置校验均不是 H1/H2 成功证据。A 不新增全题型、LangChain/RAG、锁定或官方评分能力，也不承诺历史无泄漏准确率。

## 真实运行仍需的条件

当前包 CLI 和兼容 runner 在真实资源阶段返回退出码 **2**，标准错误以 `BLOCKED:` 开头，不提供跳过授权参数；新增入口尚未提交 Git。接入真实 runtime 和运行前，需分别落实：

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

装载修复前的离线验证记录（2026-10-02，完整本地工作区）：

- Python 全量 `uv run --offline pytest -q --tb=short`：**281 passed**，另有 2 项 FastAPI/Starlette 依赖弃用警告。
- runner `unittest discover`：**14 passed**。
- Python 模块及 runner 的 Ruff 检查、`git diff --check`：通过。
- CLI 临时运行和 `--keep-evidence` 运行均通过；保留状态的 `verify/stop` 自检通过。单次模拟 POST、原消息重投及 SQLite 重开后冻结输入/终态一致；五个真实资源阶段仍以退出码 2 拒绝。
- 所有新增测试在完整本地工作区已放行 Git ignore，临时缓存仍忽略；按本次提交范围，测试、配套 CLI 和相关 ignore 改动仅保留本地，不纳入提交。

当前工作区装载修复验证（2026-10-02，未提交）：

- 定向 `.venv/bin/pytest tests/replay/test_isolated.py -q --tb=short`：**84 passed**，保留原 18 个用例并新增 66 个回归，覆盖事务恢复、元数据及 JSON 类型/精度边界。
- 全量 `.venv/bin/pytest -q --tb=short`：**347 passed**，仍有 2 项 FastAPI/Starlette 依赖弃用警告。
- runner 本地门禁：**14 passed**；Python 模块及 runner 的 Ruff 检查、`git diff --check`：通过。
- CLI `dry-run`：`SIMULATED_COMPLETE`，仍明确 `realResourceAccess=false`、`realModelCalls=false`、`m1Verified=false`、`m2Verified=false`；该模拟不替代装载器的 Fake DB-API 定向回归。
- 回归区分本地事务行与持久化行，覆盖提交前冲突/SQL/最终计数错误、不持久化或元数据冲突的提交错误、回滚/回读失败、原异常与游标清理；匹配的持久化提交响应丢失仍可恢复。事务/元数据旧逻辑的两轮跑红记录分别为 10 个和 7 个预期失败，JSON 类型及精度补充回归另分别复现 12 个和 5 个失败；最终套件全部通过。
- 独立只读复核确认所列边界未发现剩余反例，独立重跑定向回归 **84 passed** 与两文件 Ruff 通过；复核没有修改文件或访问真实资源。
- 本轮只修改装载源码、对应本地测试和本手册，未新增驱动、访问真实资源或执行 Git 暂存/提交；已有测试排除与 `.gitignore` 改动保持不变。

### A：DeepSeek 与显式 runtime 离线验收（2026-10-02，未提交）

以下数量来自本次主会话实际执行的稳定工作区，不是模型或真实资源验收：

| 范围 | 命令／结果 |
|---|---|
| Java 模块全量 | `./mvnw -o test`：**395 passed，0 failures / 0 errors / 0 skipped** |
| Python 模块全量 | `.venv/bin/python -m pytest -q --tb=short`：**453 passed，2 warnings** |
| Python 模块与旧 runner lint | `.venv/bin/ruff check . ../tests/real_data_mvp`：通过 |
| 标准 Python 兼容 runner | `python3 -m unittest discover -s tests/real_data_mvp -p 'test_*.py' -v`：**15 passed** |
| runner pytest（同一批测试，不重复累加） | **15 passed，5 subtests passed** |
| 补丁格式 | `git diff --check`：通过 |

Python 的两项 warning 是既有 FastAPI/Starlette/httpx 与 AnyIO 弃用提示，不是测试失败。本轮没有为消除提示安装新依赖。

独立 Fake 审查发现并关闭：双实例重复 POST/DDL、装载前置校验、原始请求首次冻结身份、同 runId 的 Worker PID 替换和安全整组停止、非法 cutoff、标准 Python 薄入口、第四次领取不能复用第三次收费候选，以及 JSON Unicode 转义密钥回显。修复过程中曾有 revision/锁 FD 的 9 项 runtime 回归失败；最终复跑全部通过，不把中间态报告当作最终证据。

最终 runtime 独立复核：并发单次 POST、完整七脚本 DDL（008 一次）；非法/错身份装载零副作用；消息八种身份/版本错配阻断；Java PID 保留、同 runId 的旧 Worker 替换为新 PID 后重开不重复重启且只停止本次登记清单，七类坏 receipt 全部拒绝。最终模型反例复跑：白名单与输入身份、发送不明、单 job 持久预算、第三次成功候选的第四次领取缓存恢复、官方 SDK context-manager Fake 形状和转义密钥解码后拒绝均通过。

`inventory/dry-run` 最终保留 `realResourceAccess=false`、`realModelCalls=false`、`m1Verified=false`、`m2Verified=false`。8 个新增 A 测试文件已逐项放行 ignore，缓存和秘密仍忽略。

**验收范围止于 A 的离线源码与 Fake 测试**：SDK 依赖仅声明/锁定，安装被权限拒绝且仍未安装；实际 SDK smoke、真实数据库/Broker/模型、H1/H2 和前向效果均未验证，仍需单独批准。所有变更保留在 `dev` 工作区，没有 git commit 或 push。

### Java resolver 的独立编译核验（2026-10-02，历史记录）

历史 7 errors 已定位为现存构建输出污染：默认命令 `./mvnw -o -Dtest=PredictionRequestSnapshotResolverTest test` 在主源码和测试编译阶段均报告 `Nothing to compile`，复用的测试 class 中直接包含抛出 `Unresolved compilation problems` 的错误桩；相关主 class 也缺少源码中 Lombok 注解应生成的方法。源码注解及测试调用本身没有显示对应缺陷，不能将旧 class 的错误误判为需要删改业务代码或依赖。

已在专属私有临时目录生成验证 POM，只引用当前 `src/main/java` 和该 resolver 的测试源码，将全部编译输出放到新目录、主/测试资源均指向空目录，再用原 Maven Wrapper 离线验证。日志确认 **131 个主源码文件和 1 个测试文件由 javac 重新编译，7 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**。未复制生产配置，未清理或替换原 `target`，未访问真实数据库/服务，也未修改仓库 POM、依赖或 Java 源码。

这关闭了 resolver 当前源码的定向验收阻塞，**不表示原模块的默认增量构建已修复，也不是 Java 全量测试通过**。原 `target` 的错误产物仍保留；后续应在明确的本地构建维护范围内排查 IDE/JDT 注解处理和输出目录污染，不能自动执行此前被拒绝的清理。验证过程中另有 javac 注解处理提示及 Mockito 动态 agent 警告，不是测试失败。

**M1、M2、真实 MySQL/Rabbit/Java 单题重投及重启恢复均未验收。**历史工程回放的分数未经校准、clean 规则只是工程代理，不是官方圈速判定，不得声称赛前准确率或真实模型能力。本次源码提交不包含测试目录或配套 CLI，不改变这些验收边界。

### 阶段 B 真实隔离单题观测（2026-10-04）

上方 2026-10-02 记录保留为历史，不代表以下实际运行后的当前状态。

- 新专属项目 `real-data-mvp-h1-20261003-fbb433b3` 真实执行七份 DDL（不含 `005`）、最小来源副本装载与 ID 读回、封签、隔离 Java/stub Worker 和一次单题 POST；一个批次/任务达到 `COMPLETED/SUCCEEDED`，MySQL result/item/receipt 各 1，SQLite inbox/outbox 各 1，87 条 evidence。
- 原 messageId/body 重投两次均有实际 `mandatory Basic.Ack`；自有 Worker 重启后继续使用同一 SQLite、输入包和冻结 feature hash，业务终态及计数不变。可控失败只覆盖 `TRANSPORT_BEFORE_SEND`，**未覆盖业务 FAILED 终态恢复**，没有第二次 POST。
- 停止观察不明时只读查回完整进程表、Broker 与持久状态；不重复发信号，不盲杀或删卷，不重跑 DDL/装载/POST/整个恢复阶段。最终已核验本次 Java/Worker 全部停止、无未决意图，容器、卷、SQLite 和封签包保留；停机前 API 与停机后持久状态证据分别记录。
- **不能据此宣称完整阶段 B 或 H1/M1 通过**：B1 来源资源独立身份验证仍缺，管理员最小权限未知；跨源映射为多字段关联而非厂商认证。B2–B4 的成功链路与恢复实证通过，B5 的历史 stub 限制保持。
- 业务厂商调用 0，`H2/M2=false`；firstSeen 仍 `UNKNOWN`，Practice 计划结束窗口偏差保留，分数未校准，不是严格赛前有效性或模型能力验收。
- 详细报告：`.omc/reports/stage-b-real-h1-observed-2026-10-04-fbb433b3.md`；私有实物现场：`~/.f1predict-h1-20261003-fbb433b3/`，包括 `final-observation.json`、原数据库/SQLite/封签审计与 `runtime-audit/` 接线和日志副本。审计副本不作为可重跑入口，秘密和生产配置不复制。
- 阶段 C 不自动启动；额外来源身份查询、独立失败任务验收，以及厂商端点/数据外发/费用分别需要明确范围。全部变更仍未执行 git commit/push。
