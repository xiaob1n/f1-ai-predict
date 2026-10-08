package com.lbz.f1aipredict.scoring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.scoring.entity.BatchTotalScore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

/** 批次总分持久化入口，批次唯一键为 uk_total_batch。 */
@Mapper
public interface BatchTotalScoreMapper extends BaseMapper<BatchTotalScore> {

    @Select("SELECT * FROM batch_total_score WHERE batch_id = #{batchId} LIMIT 1")
    BatchTotalScore selectByBatchId(@Param("batchId") Long batchId);

    /**
     * 重算前把批次当前总分复制到历史表；历史列见 sql/011_scoring_status.sql，须与其保持一致。
     *
     * @return 归档行数
     */
    @Insert("""
            INSERT INTO batch_total_score_history
              (batch_id, round_id, total_score, max_score, accuracy_rate, type_stats_json,
               created_at, updated_at, archived_at, revision_reason)
            SELECT batch_id, round_id, total_score, max_score, accuracy_rate, type_stats_json,
                   created_at, updated_at, #{archivedAt}, #{reason}
            FROM batch_total_score WHERE batch_id = #{batchId}
            """)
    int archiveByBatchId(@Param("batchId") Long batchId, @Param("archivedAt") Instant archivedAt,
                         @Param("reason") String reason);

    /** 归档成功后删除批次当前总分，释放 uk_total_batch 供重算写入；只能按批次删除。 */
    @Delete("DELETE FROM batch_total_score WHERE batch_id = #{batchId}")
    int deleteByBatchId(@Param("batchId") Long batchId);
}
