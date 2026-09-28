package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionFailure;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 预测最终失败详情 Mapper。 */
@Mapper
public interface PredictionFailureMapper extends BaseMapper<PredictionFailure> {

    /** 按任务内部主键读取失败详情。 */
    @Select("SELECT * FROM prediction_failure WHERE job_id = #{jobId} LIMIT 1")
    PredictionFailure selectByJobId(@Param("jobId") Long jobId);
}
