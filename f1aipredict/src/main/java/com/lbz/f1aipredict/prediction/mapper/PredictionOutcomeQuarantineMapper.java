package com.lbz.f1aipredict.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.prediction.entity.PredictionOutcomeQuarantine;
import org.apache.ibatis.annotations.Mapper;

/** 隔离终态消息 Mapper；调用方必须先限制原始消息体大小。 */
@Mapper
public interface PredictionOutcomeQuarantineMapper extends BaseMapper<PredictionOutcomeQuarantine> {
}
