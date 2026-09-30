package com.lbz.f1aipredict.question.mapper;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 MyBatis 映射生成 SQL，验证状态写入的窄字段及并发条件，不连接数据库。
 */
class QuestionStatusUpdateSqlTest {

    @Test
    void statusCorrection_onlyUpdatesStatusAndSyncTimesWithExpectedRowGuard() throws Exception {
        BoundSql boundSql = mappedUpdate(true, "4", "old-content");

        assertThat(normalize(boundSql.getSql())).isEqualTo(
                "UPDATE question SET last_synced_at = ?, status = ?, updated_at = ? "
                        + "WHERE id = ? AND gameday_id = ? AND content_hash <=> ? AND status <=> ?");
        assertThat(boundSql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("now", "status", "now", "id", "gamedayId", "expectedContentHash", "expectedStatus");
    }

    @Test
    void unchangedStatus_refreshDoesNotTouchUpdatedAtAndSupportsNullExpectedStatus() throws Exception {
        BoundSql boundSql = mappedUpdate(false, null, null);

        assertThat(normalize(boundSql.getSql())).isEqualTo(
                "UPDATE question SET last_synced_at = ?, updated_at = updated_at "
                        + "WHERE id = ? AND gameday_id = ? AND content_hash <=> ? AND status <=> ?");
        assertThat(boundSql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("now", "id", "gamedayId", "expectedContentHash", "expectedStatus");
    }

    @Test
    void gamedayIdentityLookup_includesQuestionsOutsideCurrentRoundInOneQuery() throws Exception {
        assertThat(QuestionMapper.class.getDeclaredMethod("selectByGamedayId", Integer.class).getReturnType())
                .isEqualTo(List.class);
        Configuration configuration = new Configuration();
        configuration.addMapper(QuestionMapper.class);
        MappedStatement statement = configuration.getMappedStatement(
                QuestionMapper.class.getName() + ".selectByGamedayId");
        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        BoundSql boundSql = statement.getBoundSql(Map.of("gamedayId", 42));

        assertThat(normalize(boundSql.getSql())).isEqualTo(
                "SELECT * FROM question WHERE gameday_id = ? ORDER BY id ASC");
        assertThat(boundSql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("gamedayId");
    }

    private static BoundSql mappedUpdate(boolean statusChanged, String expectedStatus, String expectedHash)
            throws Exception {
        assertThat(QuestionMapper.class.getDeclaredMethod("updateStatusIfUnchanged",
                Long.class, Integer.class, String.class, String.class, String.class, Instant.class, boolean.class)
                .getReturnType()).isEqualTo(int.class);
        Configuration configuration = new Configuration();
        configuration.addMapper(QuestionMapper.class);
        MappedStatement statement = configuration.getMappedStatement(
                QuestionMapper.class.getName() + ".updateStatusIfUnchanged");
        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.UPDATE);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", 1001L);
        parameters.put("gamedayId", 42);
        parameters.put("expectedContentHash", expectedHash);
        parameters.put("expectedStatus", expectedStatus);
        parameters.put("status", "OPEN");
        parameters.put("now", Instant.parse("2026-09-30T00:00:00Z"));
        parameters.put("statusChanged", statusChanged);
        return statement.getBoundSql(parameters);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
