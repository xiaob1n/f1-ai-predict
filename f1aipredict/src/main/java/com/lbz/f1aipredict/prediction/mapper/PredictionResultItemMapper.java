package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionResultItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 预测结果选项明细 Mapper。 */
@Mapper
public interface PredictionResultItemMapper extends BaseMapper<PredictionResultItem> {

    /** 按任务读取有序选项，最多返回 100 条。 */
    @Select("SELECT item.* FROM prediction_result_item item "
            + "JOIN prediction_result result ON result.id = item.result_id "
            + "WHERE result.job_id = #{jobId} ORDER BY item.position ASC LIMIT 100")
    List<PredictionResultItem> selectByJobId(@Param("jobId") Long jobId);
}
