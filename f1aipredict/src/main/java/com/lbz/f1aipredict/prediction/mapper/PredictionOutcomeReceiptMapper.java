package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionOutcomeReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 预测终态接收凭证 Mapper。 */
@Mapper
public interface PredictionOutcomeReceiptMapper extends BaseMapper<PredictionOutcomeReceipt> {

    /** 按任务内部主键查询已接收终态凭证。 */
    @Select("SELECT * FROM prediction_outcome_receipt WHERE job_id = #{jobId} LIMIT 1")
    PredictionOutcomeReceipt selectByJobId(@Param("jobId") Long jobId);

    /** 按终态消息 ID 查询接收凭证。 */
    @Select("SELECT * FROM prediction_outcome_receipt WHERE message_id = #{messageId} LIMIT 1")
    PredictionOutcomeReceipt selectByMessageId(@Param("messageId") String messageId);
}
