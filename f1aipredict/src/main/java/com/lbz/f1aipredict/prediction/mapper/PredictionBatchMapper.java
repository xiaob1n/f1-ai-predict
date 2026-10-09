package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * 预测批次 Mapper。
 * 继承 BaseMapper 提供 insert / selectById；
 * 按 round 查询最大 batch_no，供同分站内序号递增与唯一键冲突重试。
 */
@Mapper
public interface PredictionBatchMapper extends BaseMapper<PredictionBatch> {

    /**
     * 查询指定分站当前最大批次序号。
     * 无批次时返回 {@code null}，由调用方按 1 起步。
     *
     * @param roundId 分站主键，不得为 null
     * @return 最大 {@code batch_no}，无行时返回 null
     */
    @Select("SELECT MAX(batch_no) FROM prediction_batch WHERE round_id = #{roundId}")
    Integer selectMaxBatchNo(@Param("roundId") Long roundId);

    /** 先锁批次再锁任务，串行化同批次受理与截止锁定，避免准时结果提交晚于首次评分。 */
    @Select("SELECT * FROM prediction_batch WHERE id = #{batchId} FOR UPDATE")
    PredictionBatch selectByIdForUpdate(@Param("batchId") Long batchId);

    /** 只有严格晚于截止、未锁定且版本匹配时才写入第一次锁定时间，防止重试或并发覆盖。 */
    @Update("UPDATE prediction_batch SET locked_at = #{lockTime}, lock_version = lock_version + 1, "
            + "updated_at = UTC_TIMESTAMP(3) WHERE id = #{batchId} AND locked_at IS NULL "
            + "AND lock_version = #{expectedVersion} AND prediction_deadline < #{lockTime}")
    int lockIfUnchanged(@Param("batchId") Long batchId, @Param("expectedVersion") Integer expectedVersion,
                        @Param("lockTime") Instant lockTime);

    /** 在终态事务中更新批次聚合状态。 */
    @org.apache.ibatis.annotations.Update("UPDATE prediction_batch SET status = #{status}, "
            + "updated_at = UTC_TIMESTAMP(3) WHERE id = #{batchId}")
    int updateStatus(@Param("batchId") Long batchId, @Param("status") String status);

    /**
     * 定时锁定候选：未锁定且业务截止严格早于 now 的批次主键。
     * 按主键 keyset 翻页（id &gt; afterId），由调用方轮转游标，失败批次不会长期占据第一页。
     * 本查询只做候选筛选，真正锁定仍由 {@link #lockIfUnchanged} 的条件更新判定。
     */
    @Select("SELECT id FROM prediction_batch WHERE locked_at IS NULL AND prediction_deadline IS NOT NULL "
            + "AND prediction_deadline < #{now} AND id > #{afterId} ORDER BY id ASC LIMIT #{limit}")
    List<Long> selectLockCandidateIds(@Param("now") Instant now, @Param("afterId") Long afterId,
                                      @Param("limit") int limit);

    /**
     * 定时答案同步候选分两组：pending=true 表示存在已锁定、未评分批次；否则只取没有这类批次的历史比赛日。
     * 同一比赛日即使同时包含已评分与未评分批次，也只进入优先组，避免历史批次拖慢首次评分。
     * 每组按 gameday_id keyset 翻页；排除本轮已选比赛日，防止借配额或并发评分导致分组变化时重复 HTTP。
     */
    @Select({
        "<script>",
        "SELECT DISTINCT q.gameday_id FROM prediction_batch b",
        "JOIN prediction_job j ON j.batch_id = b.id JOIN question q ON q.id = j.question_id",
        "WHERE b.locked_at IS NOT NULL AND q.gameday_id &gt; #{afterGamedayId} AND",
        "<if test='!pending'>NOT</if>",
        "EXISTS (SELECT 1 FROM prediction_batch p JOIN prediction_job pj ON pj.batch_id = p.id",
        "JOIN question pq ON pq.id = pj.question_id",
        "WHERE p.locked_at IS NOT NULL AND pq.gameday_id = q.gameday_id",
        "AND NOT EXISTS (SELECT 1 FROM batch_total_score t WHERE t.batch_id = p.id))",
        "<if test='excludedGamedayIds != null and excludedGamedayIds.size() &gt; 0'>",
        "AND q.gameday_id NOT IN",
        "<foreach collection='excludedGamedayIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</if>",
        "ORDER BY q.gameday_id ASC LIMIT #{limit}",
        "</script>"
    })
    List<Integer> selectAnswerSyncGamedayIds(@Param("pending") boolean pending,
                                             @Param("afterGamedayId") Integer afterGamedayId,
                                             @Param("limit") int limit,
                                             @Param("excludedGamedayIds") java.util.Collection<Integer> excludedGamedayIds);

    /**
     * 自动首次评分候选：已锁定、尚无总分、全部任务处于终态且至少有一条成功结果的批次。
     * 仅用于缩小扫描范围，不作为评分依据；评分事务持有批次行锁后会重新核验全部条件与答案齐备。
     * 终态字面量与 {@link PredictionJobMapper#updateTerminalStatus} 的非终态集合互补。
     */
    @Select("SELECT b.id FROM prediction_batch b "
            + "WHERE b.locked_at IS NOT NULL AND b.id > #{afterId} "
            + "AND NOT EXISTS (SELECT 1 FROM batch_total_score t WHERE t.batch_id = b.id) "
            + "AND NOT EXISTS (SELECT 1 FROM prediction_job j WHERE j.batch_id = b.id "
            + "AND j.status NOT IN ('SUCCEEDED', 'FAILED', 'DEAD_LETTER')) "
            + "AND EXISTS (SELECT 1 FROM prediction_job j JOIN prediction_result r ON r.job_id = j.id "
            + "WHERE j.batch_id = b.id) "
            + "ORDER BY b.id ASC LIMIT #{limit}")
    List<Long> selectAutoScoreCandidateIds(@Param("afterId") Long afterId, @Param("limit") int limit);
}
