package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionResultItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** 预测结果选项明细 Mapper。 */
@Mapper
public interface PredictionResultItemMapper extends BaseMapper<PredictionResultItem> {

    /** 按任务读取有序选项，最多返回 100 条。 */
    @Select("SELECT item.* FROM prediction_result_item item "
            + "JOIN prediction_result result ON result.id = item.result_id "
            + "WHERE result.job_id = #{jobId} ORDER BY item.position ASC LIMIT 100")
    List<PredictionResultItem> selectByJobId(@Param("jobId") Long jobId);

    /**
     * 按结果主键集合一次读取选项，按 result_id、position 升序。
     * 入参为空集合或 null 时以 WHERE 1 = 0 短路，不会生成 IN () 这类非法 SQL。
     */
    @Select({
        "<script>",
        "SELECT * FROM prediction_result_item",
        "<where>",
        "  <if test='resultIds != null and resultIds.size() > 0'>",
        "    result_id IN",
        "    <foreach collection='resultIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach>",
        "  </if>",
        "  <if test='resultIds == null or resultIds.size() == 0'>",
        "    1 = 0",
        "  </if>",
        "</where>",
        "ORDER BY result_id ASC, position ASC",
        "</script>"
    })
    List<PredictionResultItem> selectByResultIds(@Param("resultIds") Collection<Long> resultIds);
}
