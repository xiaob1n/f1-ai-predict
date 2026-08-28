# MySQL Configuration Plan

## Goal

Connect the Spring Boot application to the existing MySQL database `f1_ai_predict` on `47.96.110.22`, while keeping the database password out of tracked source files.

## Steps

1. Add the Spring JDBC starter and MySQL Connector/J runtime dependency to `f1aipredict/pom.xml`.
2. Add a MySQL datasource configuration to `f1aipredict/src/main/resources/application.yaml`.
3. Read the password from the `MYSQL_PASSWORD` environment variable, with the provided password used only for local runtime setup and never written into source.
4. Compile the Maven project and inspect diagnostics for the changed configuration and build files.
5. Check the working tree and do not create any Git commit.

## Constraints

- Use the database name defined by the existing SQL scripts: `f1_ai_predict`.
- Use UTC session time zone and UTF-8 connection settings.
- Do not add DTOs or new Java classes because this task only configures the datasource.
- Do not modify unrelated existing work.
