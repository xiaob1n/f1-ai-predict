# season 域知识库

生成日期：2026-09-06
维护规则：本文件属未提交工作区变更，任何情况下不执行 git 提交。仓库级与模块级通用规范见父级 `AGENTS.md`，本文件只记录 season 特有边界。

## 领域定位

`com.lbz.f1aipredict.season` 管理 Season、Round、MeetingSession 三层赛程基础数据，目录含 controller、service、entity、mapper、dto。查询 Service 只读；Feed 驱动的 insert/update 由 `sync/FeedSyncServiceImpl` 编排，不能把 season Service 当写入入口。

实体关系：Season 1:N Round（`season_id`），Round 1:N MeetingSession（`round_id`）。Session 的 `gamedayId` 还被题目同步用于反查所属 Round。

## API 入口

- `SeasonController`：`GET /api/v1/seasons/current`、`GET /api/v1/seasons`。
- `RoundController`：`GET /api/v1/rounds/current`、`GET /api/v1/rounds/{roundId}`、`GET /api/v1/seasons/{seasonId}/rounds`。
- `MeetingSessionController`：按 round、主键、`sessionKey`（唯一）和 `meetingKey`（非唯一）查询。

数字路径使用 `\\d+` 约束；`/current` 与 `/by-*` 字面量路由必须优先于数字变量，避免类型转换或路由抢占。Controller 只依赖 Service 接口并返回 DTO。

## 当前赛季与分站

`SeasonServiceImpl.getCurrentSeason()` 一次读取候选后在内存选择：

1. `IN_PROGRESS`，并列按 `year DESC, id DESC`。
2. 当前 UTC 年份，仍按 `year DESC, id DESC`。
3. 最近未来年份，按 `year ASC, id ASC`。
4. 无结果抛 `ResourceNotFoundException`，不回退历史年份。

`RoundServiceImpl.getCurrentRound()` 一次读取 Round 与 Session，依次选择：非 `CANCELLED` 的进行中 Round、命中 Session 闭区间、命中 Round 日期闭区间、最近未来 Session、最近未来 Round。Session 的 `[startDateUtc,endDateUtc]` 是闭区间；未来 Session 必须严格晚于当前时刻。

所有当前时间来自注入的 UTC `Clock`，不得在 Service 内直接创建系统 Clock 或读取默认时区。`DATE` 使用 `LocalDate`，`DATETIME(3)` 使用 UTC `Instant`。

## 服务复用与查询规则

`MeetingSessionServiceImpl.listByRoundId()` 先复用 `RoundService.getById()` 校验父 Round，再执行一次列表查询；父级不存在时不发后续 Mapper 查询。`RoundServiceImpl.listBySeasonId()` 同样先确认父 Season。

列表查询采用一次 Mapper 读取后内存映射，禁止循环内逐条查库。排序与稳定决胜规则由 SQL/Service 契约共同保证，不能依赖数据库返回顺序或随意重排。

## 测试位置

重点测试位于 `src/test/java/com/lbz/f1aipredict/season/`：`SeasonControllerTest`、`RoundControllerTest`、`MeetingSessionControllerTest`、`SeasonRoundRoutingTest`、三个 `*ServiceImplTest`、`SeasonDtoJsonTest` 与 `SeasonPersistenceContractTest`。测试固定 UTC Clock，覆盖路由隔离、当前对象优先级、时间边界、404/空列表语义及 Entity/DDL 契约。

## 反模式（禁止）

- 在 season Service 中执行业务写入、调用 Feed Client 或创建 WebClient。
- 在 `MeetingSessionService` 绕过 `RoundService` 直接注入 RoundMapper 做父级校验。
- 用 `is_current` 或默认时区代替当前实现的状态、UTC 日期和注入 Clock 选择逻辑。
- 在列表/DTO 映射循环中逐条访问 Mapper。
- 将 `meetingKey` 非唯一查询的空结果升级为 404；只有单资源/唯一键查询才抛资源不存在。
- 把 `RaceId` 当作 Round 身份；该归并规则属于 sync 域，必须按其父级文档执行。
