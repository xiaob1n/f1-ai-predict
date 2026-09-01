package com.lbz.f1aipredict.season.mapper;

import com.lbz.f1aipredict.season.entity.MeetingSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 会议/Session Mapper。
 * 继承 BaseMapper 提供 insert / updateById / selectById 等标准 CRUD；
 * 按 session_key 查询供赛程同步按 uk_session_key 幂等 upsert。
 */
@Mapper
public interface MeetingSessionMapper extends BaseMapper<MeetingSession> {

    /**
     * 按 OpenF1 session_key 查询（对应唯一键 uk_session_key）。
     *
     * @param sessionKey 会话标识，不得为 null
     * @return 命中的 Session，无则返回 null
     */
    @Select("SELECT * FROM meeting_session WHERE session_key = #{sessionKey} LIMIT 1")
    MeetingSession selectBySessionKey(@Param("sessionKey") Integer sessionKey);

    /**
     * 按 gameday_id 查询任意一条 meeting_session，用于题目同步时解析 round_id。
     * 同一 gameday 下所有 session 的 round_id 一致，取首条即可。
     *
     * @param gamedayId 比赛日 ID，不得为 null
     * @return 命中的 Session，无则返回 null
     */
    @Select("SELECT * FROM meeting_session WHERE gameday_id = #{gamedayId} LIMIT 1")
    MeetingSession selectByGamedayId(@Param("gamedayId") Integer gamedayId);
}
