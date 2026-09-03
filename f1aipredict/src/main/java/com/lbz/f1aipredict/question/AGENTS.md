# question/AGENTS.md（题目只读查询域专属知识库）

范围：只约束 `com.lbz.f1aipredict.question` 只读查询链路。仓库全局约定见根 `AGENTS.md`，模块视角见 `f1aipredict/AGENTS.md`，表结构见 `sql/AGENTS.md`（`003_question.sql`）。此处只写本子域边界，不重复上述内容。

## 域定位

- question 域是纯只读查询切片：14 个 Java 文件（controller 1、service 2、mapper 3、entity 3、dto 5），完整垂直分层。
- 写入全部归 sync 域（`FeedSyncService` 编排同步、幂等 upsert 与快照生成），本域零写方法、无 Store、无事务、无 Feed 客户端依赖。
- 本域代码改动须补中文注释/Javadoc，风格与现有类一致（类、方法、关键分支）。

## Controller：仅四个 GET 路由

`QuestionController`（`@RestController`，前缀 `/api/v1`）只依赖 `QuestionService` 接口、只返回 DTO、无任何 success/包装层：

1. `GET /api/v1/rounds/{roundId}/questions` 委托 `listByRoundId(roundId, QuestionQuery)`
2. `GET /api/v1/questions/{questionId}` 委托 `getDetail(questionId)`
3. `GET /api/v1/questions/{questionId}/snapshots/{snapshotId}` 委托 `getSnapshot(questionId, snapshotId)`
4. `GET /api/v1/questions/{questionId}/snapshots` 委托 `listSnapshots(questionId)`

- 路由 1 的 status/gamedayId/includeOptions/snapshotId 经 `@ModelAttribute` 绑定单个 `QuestionQuery`，禁止拆成散参数或改 `@RequestBody`。
- 无 answer、write、admin、预测提交等任何其他端点；对四个 GET 路径发 POST 由 Spring 拒绝为 405，且不触碰 Service（有测试守护）。

## Entity：当前态 + 快照历史模型

- `Question`（`question` 表）：题目当前状态，含 `roundId`、`status`、`latestSnapshotId`（指向当前生效快照）等。
- `QuestionSnapshot`（`question_snapshot` 表）：某次同步的不可变历史，`snapshotNo` 同题内从 1 递增，`rawJson` 存同步原文，`contentHash` 用于判重。
- `QuestionOption`（`question_option` 表）：挂在快照下（`snapshotId` 归属），同快照内 `optionNo` 唯一；`optionId` 是 Feed 侧 ID，仅在快照范围内有意义，可跨快照重复。
- 关系：question 1:N snapshot（`uk_snapshot_no`），snapshot 1:N option（`uk_option_snapshot`）；最新态即 `Question.latestSnapshotId` 所指快照。改动归属或唯一键前先看 `sql/AGENTS.md` 唯一键语义。

## Service：快照语义与边界行为

- `QuestionService` 接口四方法一一对应 Controller 四路由；`QuestionServiceImpl` 构造器注入 3 个 Mapper。
- 最新快照语义：路由 1 默认附各题 `latestSnapshotId` 快照下的选项；路由 2 返回题目字段 + 最新快照选项。
- DB 允许题目暂无最新快照（`latestSnapshotId` 为 null）：详情照常返回、`options` 为空，不报错。
- 快照归属校验：路由 3 先查题目再查快照，快照不存在或其 `questionId` 与路径 `questionId` 不一致均抛 `ResourceNotFoundException`（404）。
- 未知分站（`selectByRound` 无结果）或列表指定快照不存在/不属于该分站题目：路由 1 一律返回空列表，不抛 404。
- 题目不存在：路由 2/3/4 抛 `ResourceNotFoundException`（404）。
- 排序稳定：题目 `question_no ASC, id ASC`；快照 `snapshot_no ASC, id ASC`；选项 `snapshot_id ASC, option_no ASC`。SQL 层排序，Service 内存映射不重排。
- 禁止在 DTO 映射循环内逐条访问 Mapper（N+1）。快照与选项一律循环外批量加载：
  - 路由 1：`selectByRound` 一次 → 收集去重 `latestSnapshotId` → `selectSnapshotByIds` 一次批量确认存在 → `selectBySnapshotIds` 一次 → 内存 `LinkedHashMap` 按 snapshotId 分组。
  - 路由 3/4：先取目标快照或全量快照，再对全部 snapshotId 一次 `selectBySnapshotIds`。
  - 同一 snapshotId 的分组结果按 option_no 原序保留，不得按 optionId 去重。

## DTO：JSON 契约

- 5 个 DTO：`QuestionQuery`、`QuestionDto`、`QuestionDetailDto`、`QuestionSnapshotDto`、`QuestionOptionDto`，均为 `@Data + @Builder + 全参/无参构造`。
- 每个字段显式 `@JsonProperty("camelCase 名")`，注解值必须与 Java 字段名一致（反射契约测试强制）。
- `options` 一律 `List`，默认初始化为空列表，未附带时序列化 `[]` 而非 null；可空字段序列化为显式 null。
- 不回传实体、不回传 `rawJson` 本体；快照 DTO 仅暴露 `hasRawJson` 布尔（由实体 `rawJson != null` 推导）。
- `questionType` 首版固定 `UNKNOWN`（DTO 字段默认值），Feed 侧类型映射未落地，不得自行猜测填值。
- `QuestionQuery.includeOptions` 默认 true，显式传 false 必须保留；`QuestionDto` 与 `QuestionDetailDto` 字段集合同构，仅语义不同。

## Mapper：注解 SQL，无 XML

- 3 个 Mapper 均 `extends BaseMapper<实体>`，全部用 `@Select` / `@SelectProvider` + `@Results` 注解 SQL，无任何 XML mapper 文件。
- `QuestionMapper.selectByRound` 走内嵌 `SQLProvider` 类动态拼接可选 status/gamedayId 过滤（MyBatis SQL 构造器 + `#{}` 预编译），并内联 `@Results` 列映射。
- 批量 `IN` 查询（`selectSnapshotByIds` / `selectBySnapshotIds`）用 `<script>` + `foreach` 参数绑定，空集合以 `WHERE 1 = 0` 短路，绝不生成 `IN ()`。
- `QuestionMapper.selectByGamedayIdAndSourceQuestionId`、`QuestionSnapshotMapper.selectMaxSnapshotNo` 供 sync 域复用（幂等查找、快照序号递增），本域不调用也不删除。
- 命名 `selectSnapshotByIds` 是为避开父接口 `BaseMapper.selectByIds` 的泛型擦除冲突，勿改回 `selectByIds`。

## 测试

- controller：`QuestionControllerTest`（standalone MockMvc + mock Service + 真实 `GlobalExceptionHandler`）：四路由 JSON 形状、`@ModelAttribute` 绑定与 `includeOptions` 默认、404 错误体、POST 405 写保护、无 success 包装。
- service：`QuestionServiceImplTest`（Mockito）：批量 Mapper 调用恰一次、空列表/404 各分支、快照归属校验、`hasRawJson` 推导、选项分组不去重。
- dto：`QuestionDtoJsonTest`（`tools.jackson` ObjectMapper + 反射）：每字段同名显式 `@JsonProperty`、无 `rawJson` 字段、字段集合/类型精确、序列化形状对齐 `java-api-design.md` 2.1（questionType=UNKNOWN）、`options` 恒为数组。
- mapper/entity：`QuestionPersistenceContractTest`（纯反射）：`@TableName`/`@TableId(AUTO)`/字段精确类型、Mapper 方法签名与注解、SQL 脚本空集短路与排序分支。
