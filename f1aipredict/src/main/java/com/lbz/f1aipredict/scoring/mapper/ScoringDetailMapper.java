package com.lbz.f1aipredict.scoring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.scoring.entity.ScoringDetail;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/** 评分明细持久化入口，结果唯一键为 uk_scoring_result。 */
@Mapper
public interface ScoringDetailMapper extends BaseMapper<ScoringDetail> {

    /**
     * 单条语句批量插入，保证一个批次的明细要么整体写入要么整体失败。
     * 列清单须与 {@link ScoringDetail} 的非主键 {@code @TableField} 保持一致，由契约测试守护。
     *
     * @param details 非空明细列表
     * @return 实际插入行数
     */
    @Insert({
        "<script>",
        "INSERT INTO scoring_detail (batch_id, result_id, question_id, question_type, score, max_score,",
        "  partial_score, scoring_rule_version, detail_json, scored_at, created_at, updated_at, score_status)",
        "VALUES",
        "<foreach collection='details' item='d' separator=','>",
        "  (#{d.batchId}, #{d.resultId}, #{d.questionId}, #{d.questionType}, #{d.score}, #{d.maxScore},",
        "   #{d.partialScore}, #{d.scoringRuleVersion}, #{d.detailJson}, #{d.scoredAt}, #{d.createdAt},",
        "   #{d.updatedAt}, #{d.scoreStatus})",
        "</foreach>",
        "</script>"
    })
    int insertBatch(@Param("details") List<ScoringDetail> details);

    /**
     * 重算前把批次当前全部明细复制到历史表；历史列见 sql/011_scoring_status.sql，须与其保持一致。
     *
     * @return 归档行数
     */
    @Insert("""
            INSERT INTO scoring_detail_history
              (batch_id, result_id, question_id, question_type, score, max_score, partial_score,
               scoring_rule_version, detail_json, scored_at, created_at, updated_at, score_status,
               archived_at, revision_reason)
            SELECT batch_id, result_id, question_id, question_type, score, max_score, partial_score,
                   scoring_rule_version, detail_json, scored_at, created_at, updated_at, score_status,
                   #{archivedAt}, #{reason}
            FROM scoring_detail WHERE batch_id = #{batchId}
            """)
    int archiveByBatchId(@Param("batchId") Long batchId, @Param("archivedAt") Instant archivedAt,
                         @Param("reason") String reason);

    /** 归档成功后删除批次当前明细，释放 uk_scoring_result 供重算写入；只能按批次删除。 */
    @Delete("DELETE FROM scoring_detail WHERE batch_id = #{batchId}")
    int deleteByBatchId(@Param("batchId") Long batchId);
}
