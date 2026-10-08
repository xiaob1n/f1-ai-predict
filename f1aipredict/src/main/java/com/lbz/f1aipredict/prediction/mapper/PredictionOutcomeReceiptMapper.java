package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionOutcomeReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** 预测终态接收凭证 Mapper。 */
@Mapper
public interface PredictionOutcomeReceiptMapper extends BaseMapper<PredictionOutcomeReceipt> {

    /** 按任务内部主键查询已接收终态凭证。 */
    @Select("SELECT * FROM prediction_outcome_receipt WHERE job_id = #{jobId} LIMIT 1")
    PredictionOutcomeReceipt selectByJobId(@Param("jobId") Long jobId);

    /** 按终态消息 ID 查询接收凭证。 */
    @Select("SELECT * FROM prediction_outcome_receipt WHERE message_id = #{messageId} LIMIT 1")
    PredictionOutcomeReceipt selectByMessageId(@Param("messageId") String messageId);

    /**
     * 按任务主键集合一次读取接收凭证（每个任务至多一条）。
     * 入参为空集合或 null 时以 WHERE 1 = 0 短路，不会生成 IN () 这类非法 SQL。
     */
    @Select({
        "<script>",
        "SELECT * FROM prediction_outcome_receipt",
        "<where>",
        "  <if test='jobIds != null and jobIds.size() > 0'>",
        "    job_id IN",
        "    <foreach collection='jobIds' item='jid' open='(' separator=',' close=')'>#{jid}</foreach>",
        "  </if>",
        "  <if test='jobIds == null or jobIds.size() == 0'>",
        "    1 = 0",
        "  </if>",
        "</where>",
        "ORDER BY job_id ASC",
        "</script>"
    })
    List<PredictionOutcomeReceipt> selectByJobIds(@Param("jobIds") Collection<Long> jobIds);
}
