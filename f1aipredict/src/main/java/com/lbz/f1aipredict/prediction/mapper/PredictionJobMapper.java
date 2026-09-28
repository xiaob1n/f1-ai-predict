package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.PredictionJobStatus;
import com.lbz.f1aipredict.prediction.entity.PredictionJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.jdbc.SQL;

import java.util.List;
import java.util.Locale;

/**
 * 预测任务 Mapper。
 * 继承 BaseMapper 提供 insert / selectById；
 * 自定义查询覆盖业务键查找、按批次分页、一次状态聚合。
 * 禁止 XML；分页在 Provider 内裁剪 page/size，避免无界 SELECT。
 */
@Mapper
public interface PredictionJobMapper extends BaseMapper<PredictionJob> {

    /**
     * 按业务幂等键查询任务（对应唯一键 uk_job_prediction_id）。
     * 不开放数据库自增主键作为对外查询入口。
     *
     * @param predictionJobId 业务键，不得为 null
     * @return 命中的任务，无则返回 null
     */
    @Select("""
            SELECT * FROM prediction_job
            WHERE prediction_job_id = #{predictionJobId}
            LIMIT 1
            """)
    PredictionJob selectByPredictionJobId(@Param("predictionJobId") String predictionJobId);

    /** 事务内锁定业务任务，串行化同一 job 的成功/失败终态竞争。 */
    @Select("SELECT * FROM prediction_job WHERE prediction_job_id = #{predictionJobId} LIMIT 1 FOR UPDATE")
    PredictionJob selectByPredictionJobIdForUpdate(@Param("predictionJobId") String predictionJobId);

    /** 批次行已锁定且事务为 READ COMMITTED 时读取已提交的当前状态集合。 */
    @Select("SELECT * FROM prediction_job WHERE batch_id = #{batchId} ORDER BY id ASC")
    List<PredictionJob> selectAllByBatchId(@Param("batchId") Long batchId);

    /** 只允许非终态任务推进到结果或失败终态。 */
    @org.apache.ibatis.annotations.Update("UPDATE prediction_job SET status = #{status}, "
            + "completed_at = #{completedAt}, updated_at = UTC_TIMESTAMP(3) WHERE id = #{jobId} "
            + "AND status IN ('PENDING', 'RUNNING', 'RETRYING')")
    int updateTerminalStatus(@Param("jobId") Long jobId, @Param("status") String status,
                             @Param("completedAt") java.time.Instant completedAt);

    /**
     * 按批次分页查询任务，可选状态精确过滤。
     * page 为 0-based；非法 page/size 由 {@link SQLProvider} 裁剪后生成 LIMIT/OFFSET。
     *
     * @param batchId 批次主键
     * @param status  可选任务状态，空白则不过滤
     * @param page    0-based 页码
     * @param size    每页条数
     * @return 任务列表，按 id 升序
     */
    @SelectProvider(type = SQLProvider.class, method = "selectPageByBatchId")
    List<PredictionJob> selectPageByBatchId(@Param("batchId") Long batchId,
                                            @Param("status") String status,
                                            @Param("page") Integer page,
                                            @Param("size") Integer size);

    /**
     * 一次查询返回该批次每种任务状态的计数。
     * 状态集合来自 {@link PredictionJobStatus}，不在此重复常量。
     *
     * @param batchId 批次主键
     * @return 六种已知状态及未知状态计数投影
     */
    @SelectProvider(type = SQLProvider.class, method = "selectStatusCountsByBatchId")
    PredictionJobStatusCounts selectStatusCountsByBatchId(@Param("batchId") Long batchId);

    /**
     * 内嵌 SQL 提供器：分页裁剪与一次状态聚合，保证 Mapper 自包含、无 XML。
     */
    class SQLProvider {

        /** 与仓库分页约定一致：API page 0-based。 */
        private static final int DEFAULT_PAGE = 0;

        /** size 缺省或非法时回落 20。 */
        private static final int DEFAULT_SIZE = 20;

        /** size 上限，防止一次拖垮数据库。 */
        private static final int MAX_SIZE = 100;

        /**
         * 生成按批次分页查询任务的 SQL。
         * 非法 page/size 在此裁剪为有界 LIMIT，绝不生成无 LIMIT 的 SELECT。
         *
         * @param batchId 批次主键
         * @param status  可选状态过滤
         * @param page    原始页码
         * @param size    原始每页条数
         * @return 带 LIMIT/OFFSET 的查询 SQL
         */
        public String selectPageByBatchId(Long batchId, String status, Integer page, Integer size) {
            int safePage = clampPage(page);
            int safeSize = clampSize(size);
            // 先裁剪 size 再算 offset，避免超大 size 把偏移量放大成无界扫描。
            long offset = (long) safePage * (long) safeSize;
            SQL sql = new SQL() {{
                SELECT("*");
                FROM("prediction_job");
                WHERE("batch_id = #{batchId}");
                // 空白 status 视为不过滤，避免把空串写进等值条件。
                if (status != null && !status.isBlank()) {
                    WHERE("status = #{status}");
                }
                ORDER_BY("id ASC");
            }};
            // LIMIT/OFFSET 使用裁剪后的整数字面量，不是用户字符串，无注入风险。
            return sql + " LIMIT " + safeSize + " OFFSET " + offset;
        }

        /**
         * 生成一次聚合每种 job 状态计数的 SQL，未知状态单独计入 unknownCount。
         * 状态字面量来自 {@link PredictionJobStatus#values()}，禁止在此手写状态列表。
         *
         * @param batchId 批次主键（仅用于签名对齐 @SelectProvider，条件走 #{} 绑定）
         * @return 单条聚合查询
         */
        public String selectStatusCountsByBatchId(Long batchId) {
            StringBuilder sql = new StringBuilder("SELECT ");
            PredictionJobStatus[] statuses = PredictionJobStatus.values();
            for (int i = 0; i < statuses.length; i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                PredictionJobStatus status = statuses[i];
                // 枚举 name 是编译期常量，不是用户输入，可作为 CASE 比较值。
                sql.append("SUM(CASE WHEN status = '")
                        .append(status.name())
                        .append("' THEN 1 ELSE 0 END) AS ")
                        .append(countAlias(status));
            }
            // 未知状态不允许静默落入已知状态，仍由同一条 SQL 显式统计。
            sql.append(", SUM(CASE WHEN status NOT IN (");
            for (int i = 0; i < statuses.length; i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("'").append(statuses[i].name()).append("'");
            }
            sql.append(") THEN 1 ELSE 0 END) AS unknownCount");
            sql.append(" FROM prediction_job WHERE batch_id = #{batchId}");
            return sql.toString();
        }

        /**
         * 将页码裁剪为合法的 0-based 值。
         */
        private static int clampPage(Integer page) {
            if (page == null || page < 0) {
                return DEFAULT_PAGE;
            }
            return page;
        }

        /**
         * 将每页条数裁剪到 [1, 100]，非法值回落默认 20。
         */
        private static int clampSize(Integer size) {
            if (size == null || size < 1) {
                return DEFAULT_SIZE;
            }
            if (size > MAX_SIZE) {
                return MAX_SIZE;
            }
            return size;
        }

        /**
         * 将枚举名转为驼峰计数字段：DEAD_LETTER → deadLetterCount。
         */
        private static String countAlias(PredictionJobStatus status) {
            String[] parts = status.name().toLowerCase(Locale.ROOT).split("_");
            StringBuilder alias = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                alias.append(Character.toUpperCase(parts[i].charAt(0)));
                alias.append(parts[i].substring(1));
            }
            alias.append("Count");
            return alias.toString();
        }
    }
}
