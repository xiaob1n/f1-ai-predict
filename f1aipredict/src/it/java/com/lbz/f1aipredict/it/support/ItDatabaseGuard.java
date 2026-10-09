package com.lbz.f1aipredict.it.support;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * 上下文启动时核实连接的是本次专属 IT 库：本地模式限制为回环地址与业务库，远程模式限制为随机测试库；
 * 两种模式都要求对应 schema 中的唯一 run 标记与环境变量一致。任一条件不满足即让上下文启动失败。
 */
public class ItDatabaseGuard implements InitializingBean {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public ItDatabaseGuard(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        String expectedRunId = ItEnvironment.requireDedicatedDatabase();
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        boolean remoteMode = "true".equals(System.getenv(ItEnvironment.REMOTE_MODE));
        String markerSchema;
        if (remoteMode) {
            markerSchema = ItEnvironment.validateRemote(
                    hikari.getJdbcUrl(), System.getenv(ItEnvironment.DATABASE_NAME),
                    System.getenv(ItEnvironment.MARKER_SCHEMA));
        } else {
            if (!ItEnvironment.isLoopbackDedicatedUrl(hikari.getJdbcUrl())) {
                throw new IllegalStateException("数据源地址不是本机回环专属 IT 库，拒绝启动");
            }
            markerSchema = "f1_it_guard";
        }
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        String expectedDatabase = remoteMode ? System.getenv(ItEnvironment.DATABASE_NAME) : ItEnvironment.DATABASE;
        if (!expectedDatabase.equals(database)) {
            throw new IllegalStateException("当前库与隔离 IT 配置不一致，拒绝启动");
        }
        List<String> markers = jdbcTemplate.queryForList(
                "SELECT run_id FROM `" + markerSchema + "`.it_run_marker", String.class);
        if (markers.size() != 1 || !expectedRunId.equals(markers.getFirst())) {
            throw new IllegalStateException("守护库 run 标记与 F1_IT_RUN_ID 不一致，拒绝在该库执行集成测试");
        }
    }
}
