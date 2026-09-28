package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionEvidence;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 预测证据 Mapper。 */
@Mapper
public interface PredictionEvidenceMapper extends BaseMapper<PredictionEvidence> {

    /** 按任务读取有界证据列表。 */
    @Select("SELECT evidence.* FROM prediction_evidence evidence "
            + "JOIN prediction_result result ON result.id = evidence.result_id "
            + "WHERE result.job_id = #{jobId} ORDER BY evidence.id ASC LIMIT 100")
    List<PredictionEvidence> selectByJobId(@Param("jobId") Long jobId);
}
