# MySQL 配置计划

## 目标

将 Spring Boot 应用连接到现有 MySQL 数据库 `f1_ai_predict`，数据库地址为 `47.96.110.22`，同时确保数据库密码不写入受版本控制的源文件。

## 步骤

1. 在 `f1aipredict/pom.xml` 中加入 Spring JDBC starter 和 MySQL Connector/J 运行时依赖。
2. 在 `f1aipredict/src/main/resources/application.yaml` 中增加 MySQL 数据源配置。
3. 从环境变量 `MYSQL_PASSWORD` 读取密码；用户提供的密码只用于本地运行时设置，绝不写入源代码。
4. 编译 Maven 项目，并检查变更后的配置文件和构建文件诊断结果。
5. 检查工作区，禁止创建任何 Git 提交。

## 约束

- 使用现有 SQL 脚本定义的数据库名：`f1_ai_predict`。
- 使用 UTC 会话时区和 UTF-8 连接参数。
- 本次只配置数据源，不新增 DTO 或 Java 类。
- 不修改无关的已有工作。
