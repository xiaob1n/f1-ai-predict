package com.lbz.f1aipredict.prediction.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 原子租约更新保证多个发布实例不同时领取同一消息。 */
@Mapper
public interface PredictionRequestOutboxMapper extends BaseMapper<PredictionRequestOutbox> {

    /** 读取不可变请求载荷，用于校验消息与创建时冻结上下文一致。 */
    @Select("SELECT * FROM prediction_request_outbox WHERE prediction_job_id = #{predictionJobId} LIMIT 1")
    PredictionRequestOutbox selectByPredictionJobId(@Param("predictionJobId") String predictionJobId);

    /** 仅选候选行，真正的所有权由条件更新确定。 */
    @Select("SELECT id FROM prediction_request_outbox WHERE "
            + "(status = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(3)) "
            + "OR (status = 'SENDING' AND lease_until < UTC_TIMESTAMP(3)) "
            + "ORDER BY id LIMIT #{limit}")
    List<Long> selectDueIds(@Param("limit") int limit);

    /** 原子抢占到期记录，失效租约可恢复。 */
    @Update("UPDATE prediction_request_outbox SET status = 'SENDING', lease_token = #{token}, "
            + "lease_until = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL #{leaseSeconds} SECOND), "
            + "attempts = attempts + 1, updated_at = UTC_TIMESTAMP(3) WHERE id = #{id} "
            + "AND ((status = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(3)) "
            + "OR (status = 'SENDING' AND lease_until < UTC_TIMESTAMP(3)))")
    int claim(@Param("id") Long id, @Param("token") String token,
              @Param("leaseSeconds") int leaseSeconds);

    /** 仅当前租约的 owner 可以标记路由确认成功。 */
    @Update("UPDATE prediction_request_outbox SET status = 'SENT', lease_token = NULL, "
            + "lease_until = NULL, last_error = NULL, updated_at = UTC_TIMESTAMP(3) "
            + "WHERE id = #{id} AND status = 'SENDING' AND lease_token = #{token} "
            + "AND lease_until > UTC_TIMESTAMP(3)")
    int markSent(@Param("id") Long id, @Param("token") String token);

    /** 失败后保留记录，使用有上限的退避再次领取。 */
    @Update("UPDATE prediction_request_outbox SET status = 'PENDING', lease_token = NULL, "
            + "lease_until = NULL, next_attempt_at = DATE_ADD(UTC_TIMESTAMP(3), "
            + "INTERVAL #{delaySeconds} SECOND), last_error = #{error}, updated_at = UTC_TIMESTAMP(3) "
            + "WHERE id = #{id} AND status = 'SENDING' AND lease_token = #{token}")
    int retryLater(@Param("id") Long id, @Param("token") String token,
                   @Param("delaySeconds") int delaySeconds, @Param("error") String error);
}
