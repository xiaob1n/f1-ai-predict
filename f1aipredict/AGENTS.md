# f1aipredict 模块知识库

生成日期：2026-09-03
维护规则：本文件属未提交工作区变更，任何情况下不执行 git 提交。仓库级规范以根 `AGENTS.md` 为准，本文件只补充模块内视角，不重复根知识库内容。

## 模块总览

`f1aipredict` 是本仓库唯一 Maven 模块与唯一代码实现。Java 21、Spring Boot 4.1.1（parent）、Maven Wrapper（3.9.16）。Web 层用 Spring MVC；WebFlux 仅作为 WebClient 运行时服务 Feed 拉取，不承担 Controller 职责。持久化为 spring-jdbc + MySQL Connector/J + MyBatis-Plus 3.5.17；Lombok 生成样板；测试用 JUnit 5 + Mockito + MockWebServer。已落地运行态：`season`（仅实体/Mapper）、`question`（只读查询）、`sync`（最完整）。

## 目录结构

```
f1aipredict/
├── pom.xml / mvnw / .mvn/     # Maven 入口，所有命令在本目录执行
├── src/main/java/com/lbz/f1aipredict/
│   ├── F1aipredictApplication.java   # 启动入口：@SpringBootApplication + @EnableScheduling
│   ├── common/    # 全局异常、RequestLoggingFilter/RequestId、ApiErrorResponse、ResourceNotFoundException
│   ├── config/    # MybatisPlusConfig（MapperScan + 分页 + BlockAttack）
│   ├── season/    # Season/Round/MeetingSession 实体 + Mapper，同步写入方，无查询 Service
│   ├── question/  # 题目查询：controller/service(+impl)/mapper/dto/entity
│   └── sync/      # 同步域：controller/schedule/service/client/config/store/feed 模型等
├── src/main/resources/application.yaml  # 唯一运行配置
└── src/test/      # 单测 + 反射契约测试 + application-test.yaml + feed 样例 JSON
```

注意：嵌套目录 `f1aipredict/f1aipredict/` 只剩构建产物 `target/`，不是源码根，不要在其中查找或修改源码。

## 哪里看

| 任务 | 位置 |
|------|------|
| 同步域实现细节（编排、幂等、客户端、Store） | `src/main/java/com/lbz/f1aipredict/sync/`，子知识库 `sync/AGENTS.md` |
| 题目只读查询链路 | `src/main/java/com/lbz/f1aipredict/question/`，子知识库 `question/AGENTS.md` |
| 测试约定、夹具与契约测试 | `src/test/`，子知识库 `src/test/AGENTS.md` |
| 启动装配与 MyBatis 全局配置 | `F1aipredictApplication` + `config/MybatisPlusConfig` |
| 对外错误体与请求日志 | `common/` |
| 接口分层与 REST 设计文档 | 仓库根 `java-api-design.md`（前瞻部分与代码冲突时以代码和契约测试为准） |

## 模块约定

- 入口/配置位置：主配置仅 `src/main/resources/application.yaml`；测试配置 `src/test/resources/application-test.yaml`（默认关闭调度器）。实体/mapper 与 SQL 脚本 1:1，由纯反射契约测试守护。
- 分层：Controller 只依赖 Service 接口、只返回 DTO；Service 分接口与 impl；`sync_record`/`feed_raw_payload` 只经 `SyncPersistenceStore` 访问。
- 复用优先：已有 Service/Store/Client 能承担的职责不另写链路；Feed 拉取一律复用 `F1PredictFeedClient`。
- 改动 Java 代码须补中文注释/Javadoc 说明意图，风格与现有代码一致。
- Jackson 3 注意：Boot 4.1.1 内置 Jackson 3（`tools.jackson.*`），需要直接 `new ObjectMapper()` 或做序列化断言时用 `tools.jackson.databind.ObjectMapper`；DTO 与 Feed 模型的注解包保持 `com.fasterxml.jackson.annotation.*`。classpath 无 springdoc，不加 `@Operation`。
- DTO/实体注解约定（与根知识库一致）：DTO 每字段显式 `@JsonProperty("camelCase 名")`；实体 `@TableName` + `@TableId` + 非主键列 `@TableField("snake_case 列")`。新写字段照现有类抄即可，契约测试反射强制校验。
- 测试与构建预期：普通单测与契约测试不连 MySQL（mock 或纯反射）；`./mvnw spring-boot:run` 才依赖 `application.yaml` 指向的真实 MySQL，本地无库启动即失败，属预期行为。
- 工程没有任何 formatter/coverage/CI 配置，pom 除 spring-boot 插件外无额外检查插件；不要声称存在。

## 命令（必须在模块根 `f1aipredict/` 执行）

```bash
./mvnw compile
./mvnw test
./mvnw clean verify
./mvnw spring-boot:run   # 需真实 MySQL 可用
```

## 反模式（模块内视角）

- 把嵌套 `f1aipredict/f1aipredict/`（纯 `target/` 产物）当成源码根。
- 把 `application.yaml` 中的 MySQL 地址/口令、Feed URL 写进注释、文档、日志或任何提交物。
- 在 Service/Controller 内 `new WebClient` 或拼接 Feed URL；URL 一律读 `F1PredictFeedProperties`。
- 用 `fixedRate` 或让定时任务抛异常打断调度线程；同步用 `fixedDelay` 并捕获运行时异常。
- 按 RaceId 当独立 round 处理；须按 MeetingId（缺省回退 MeetingNumber）分组。
- 把整段 `syncCurrent` 包进大事务；HTTP 拉取不放事务，子同步经 `@Lazy` 自身代理调用保持独立小事务。
- 依赖设计中才存在的领域（prediction/scoring/statistics 仅有 SQL 建表）当作已实现功能去调用。
