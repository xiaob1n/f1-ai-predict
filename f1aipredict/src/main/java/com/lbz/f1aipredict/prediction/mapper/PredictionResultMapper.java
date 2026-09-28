package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 预测成功结果主记录 Mapper。 */
@Mapper
public interface PredictionResultMapper extends BaseMapper<PredictionResult> {

    /** 按任务内部主键读取唯一结果。 */
    @Select("SELECT * FROM prediction_result WHERE job_id = #{jobId} LIMIT 1")
    PredictionResult selectByJobId(@Param("jobId") Long jobId);
}
