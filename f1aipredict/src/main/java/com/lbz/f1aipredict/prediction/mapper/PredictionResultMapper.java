package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 预测成功结果主记录 Mapper。 */
@Mapper
public interface PredictionResultMapper extends BaseMapper<PredictionResult> {

    /** 按任务内部主键读取唯一结果。 */
    @Select("SELECT * FROM prediction_result WHERE job_id = #{jobId} LIMIT 1")
    PredictionResult selectByJobId(@Param("jobId") Long jobId);

    /** 一次读取批次内全部成功结果；结果经任务关联批次，每个任务至多一条，数量以批次题目数为界。 */
    @Select("SELECT r.* FROM prediction_result r JOIN prediction_job j ON j.id = r.job_id "
            + "WHERE j.batch_id = #{batchId} ORDER BY r.id ASC")
    List<PredictionResult> selectByBatchId(@Param("batchId") Long batchId);
}
