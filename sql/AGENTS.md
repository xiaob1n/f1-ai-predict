# sql/AGENTS.md（SQL 目录专属知识库）

范围：本文件只约束 `sql/` 下手工维护的 MySQL DDL 脚本（`001` 至 `006`）。仓库整体架构、Java 分层、DTO/Feed 约定见根 [AGENTS.md](/Users/lubinze/Desktop/f1-ai-predict/AGENTS.md)，此处不重复。

## 脚本定位

- 无 Flyway/Liquibase，无任何自动迁移机制。六份脚本是唯一 DDL 来源，手工按 `001` 至 `006` 顺序执行，不得乱序、不得跳过。
- 当前建库/建表语句使用 `IF NOT EXISTS`；这只保证对象已存在时不重复创建，不代表后续结构变更会自动迁移。
- `001_create_database.sql` 建库并 `USE f1_ai_predict`，且 002-006 每份开头都再次 `USE f1_ai_predict`，依赖该库已存在，先执行 001。
- 修改脚本时保持编号顺序与领域归属；新增脚本或迁移策略属于架构决策，不能在局部改动中自行引入。

## 脚本 / 表对照

- `001_create_database.sql`：建库 `f1_ai_predict`（utf8mb4 / utf8mb4_unicode_ci）。
- `002_season_round.sql`：`season`、`round`、`meeting_session`。赛季/分站/Session 三级，一个分站含多个 Session。
- `003_question.sql`：`question`、`question_snapshot`、`question_option`。题目当前态 + 快照历史，选项挂在快照下。
- `004_prediction.sql`：`prediction_batch`、`prediction_job`、`prediction_result`、`prediction_result_item`、`prediction_evidence`。
- `005_answer_scoring.sql`：`official_answer`、`scoring_detail`、`batch_total_score`。
- `006_sync.sql`：`sync_record`、`feed_raw_payload`。

## 已落地 Java 与仅建表边界

- 有 Entity 对应（受契约测试守护）：`season`、`round`、`meeting_session`、`question`、`question_snapshot`、`question_option`、`sync_record`、`feed_raw_payload`，Entity 在 `season/entity`、`question/entity`、`sync/entity` 三个包。
- `004_prediction.sql` 与 `005_answer_scoring.sql` 是**纯前瞻建表**：Java 侧无 `prediction`/`scoring`/`statistics` 包、无 Entity、无 Mapper。阅读或改动时不得把它们当成已落地功能，也不得因为无实现就删表或改结构。

## 表结构 / Entity 契约

- Entity 用 `@TableName` 显式点名；非主键列一律显式 `@TableField("snake_case 列名")`；DB snake_case 列映射 Java camelCase 字段。
- 纯反射持久化契约测试（`*PersistenceContractTest`）把 SQL 脚本列与 Entity 字段做 1:1 比较，二者必须逐列对齐。
- **不得为迁就测试而改 DDL**。测试是守护真实契约的，方向是改 Java 去匹配脚本，或先改脚本再同步 Java；测试注释已明确禁止为测试改脚本。
- 新增已落地领域的表/列时同步更新 SQL、Entity 与契约测试；仅做前瞻建表时必须明确标注尚无 Java 实现。

## SQL 书写约定

- MySQL / InnoDB，表级 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`。
- 标识符一律反引号 + snake_case；表名、列名、索引名全部小写。
- 主键 `id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT`；时间戳一律 `DATETIME(3)` 存 UTC，`created_at` 默认 `CURRENT_TIMESTAMP(3)`，`updated_at` 追加 `ON UPDATE CURRENT_TIMESTAMP(3)`；`DATE` 列映射 Java `LocalDate`，`DATETIME(3)` 映射 `Instant`。
- 不建物理外键。跨表引用（如 `round.season_id`、`question_snapshot.question_id`）是逻辑关联，靠注释写明"逻辑关联 x.id，不建物理外键"，一致性由应用层保证。
- 关联查询的引用列要配普通 `KEY` 索引；每张表用注释说明业务归属（`COMMENT='...'`），关键列带中文列注释。
- 改动脚本里的注释用中文，风格与现文件一致（顶部区块说明依据与业务要点）。

## 唯一键 / 哈希 / 快照语义（改动前必读）

- 唯一键承载业务幂等，不允许随手放宽或删除，逐条核对用途后再动：
  - `uk_season_year(year)`、`uk_round_season_no(season_id, round_number)`、`uk_session_key(session_key)`、`uk_question_source(gameday_id, source_question_id)`、`uk_snapshot_no(question_id, snapshot_no)`、`uk_option_snapshot(snapshot_id, option_no)`、`uk_batch_round_no(round_id, batch_no)`、`uk_job_prediction_id(prediction_job_id)`、`uk_result_job(job_id)`、`uk_item_result_pos(result_id, position)`、`uk_answer_question(question_id)`、`uk_scoring_result(result_id)`、`uk_total_batch(batch_id)`、`uk_payload_hash(content_hash)`。
- 哈希列语义区分场景，勿统一"加唯一键"：
  - `feed_raw_payload.content_hash` 是 `uk_payload_hash` 唯一键，DB 层去重。
  - `question_snapshot.content_hash`、`sync_record.content_hash` 只是普通 `KEY`；内容未变的"跳过/不新增"判断在应用层做，靠哈希查询比对，不靠唯一键。
  - `question.content_hash`、`official_answer.content_hash` 无索引，仅留档与审计比对用，不承担去重查询。
- 快照/答案关联不可松动：`question.latest_snapshot_id` 指向最新快照；`question_snapshot.snapshot_no` 同题内从 1 递增；`question_option.option_no` 对应 Feed Options 数组下标从 0 开始；`prediction_result_item.position` 从 1 开始，单选恒为 1。
- 删除某表或列前，先查 Java 侧是否有对应 Entity/字段与 `SyncPersistenceStore` 等引用面，保持一致。

## 禁止事项

- 不允许把 SQL 放进程里自动执行或宣称脚本会被应用自举；只能手工按序执行。
- 不在未确认迁移策略时自行新增脚本编号、移动既有表或引入自动迁移工具。
- 不写 DROP / TRUNCATE / 破坏性变更；不添加任何迁移框架（Flyway/Liquibase）配置。
- `application.yaml` 中的真实 MySQL 地址与口令属敏感信息，禁止写进脚本、注释或任何文档。
- 不为通过测试而改脚本结构；不因 004/005 暂无 Java 实现而删除这两份脚本。
