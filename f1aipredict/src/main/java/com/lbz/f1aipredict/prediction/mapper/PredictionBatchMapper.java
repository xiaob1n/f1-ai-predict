package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /** 锁定批次行，与任务锁形成 job→batch 的固定锁顺序。 */
    @Select("SELECT * FROM prediction_batch WHERE id = #{batchId} FOR UPDATE")
    PredictionBatch selectByIdForUpdate(@Param("batchId") Long batchId);

    /** 在终态事务中更新批次聚合状态。 */
    @org.apache.ibatis.annotations.Update("UPDATE prediction_batch SET status = #{status}, "
            + "updated_at = UTC_TIMESTAMP(3) WHERE id = #{batchId}")
    int updateStatus(@Param("batchId") Long batchId, @Param("status") String status);
}
