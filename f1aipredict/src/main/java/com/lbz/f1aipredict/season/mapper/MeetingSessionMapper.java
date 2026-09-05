package com.lbz.f1aipredict.season.mapper;

import com.lbz.f1aipredict.season.entity.MeetingSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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
     * 本方法继续保留给题目同步反查，不得改成列表查询。
     *
     * @param gamedayId 比赛日 ID，不得为 null
     * @return 命中的 Session，无则返回 null
     */
    @Select("SELECT * FROM meeting_session WHERE gameday_id = #{gamedayId} LIMIT 1")
    MeetingSession selectByGamedayId(@Param("gamedayId") Integer gamedayId);

    /**
     * 按分站列出全部 Session。
     * 使用 MySQL 兼容空值排序：{@code (start_date_utc IS NULL) ASC} 把空开始时间排到最后，
     * 禁止 PostgreSQL 风格 {@code NULLS LAST}。
     *
     * @param roundId 分站主键
     * @return 该分站下的 Session 列表，无数据返回空列表
     */
    @Select("SELECT * FROM meeting_session WHERE round_id = #{roundId} ORDER BY (start_date_utc IS NULL) ASC, start_date_utc ASC, id ASC")
    List<MeetingSession> selectByRoundId(@Param("roundId") Long roundId);

    /**
     * 按 OpenF1 meeting_key 列出 Session（meeting_key 非唯一）。
     * 排序与 {@link #selectByRoundId(Long)} 相同，保证空开始时间稳定排在末尾。
     *
     * @param meetingKey OpenF1 meeting_key
     * @return 命中的 Session 列表，无数据返回空列表
     */
    @Select("SELECT * FROM meeting_session WHERE meeting_key = #{meetingKey} ORDER BY (start_date_utc IS NULL) ASC, start_date_utc ASC, id ASC")
    List<MeetingSession> selectByMeetingKey(@Param("meetingKey") Integer meetingKey);
}
