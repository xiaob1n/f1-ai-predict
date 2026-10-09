package com.lbz.f1aipredict.season.mapper;

import com.lbz.f1aipredict.season.entity.MeetingSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
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
     * <p>
     * 赛程同步在 round 加锁前后各读一次同一 session_key 做归属复查，因此声明 {@code flushCache=TRUE}，
     * 避免第二次读命中一级缓存而得到同一个对象实例。
     *
     * @param sessionKey 会话标识，不得为 null
     * @return 命中的 Session，无则返回 null
     */
    @Select("SELECT * FROM meeting_session WHERE session_key = #{sessionKey} LIMIT 1")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
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
     * 列出比赛日的所有 Session，用于校验不能只依赖任意一行推断唯一 round 归属。
     * <p>
     * 该查询同时承担“拿到 round 锁前后各读一次”的归属复查，两次调用的语句与参数完全相同，
     * 必须声明 {@code flushCache=TRUE}，否则第二次会命中事务内一级缓存而读不到锁等待期间提交的变更。
     * {@code useCache=false} 只控制二级缓存，不会清除一级缓存。
     */
    @Select("SELECT * FROM meeting_session WHERE gameday_id = #{gamedayId} ORDER BY id ASC")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<MeetingSession> selectAllByGamedayId(@Param("gamedayId") Integer gamedayId);

    /**
     * 按比赛日索引执行当前读并锁定 Session 范围，空结果时也保护插入间隙。
     * 加锁读必须每次真实访问数据库，声明 {@code flushCache=TRUE} 避免命中一级缓存而跳过加锁。
     */
    @Select("SELECT * FROM meeting_session FORCE INDEX (idx_session_gameday) WHERE gameday_id = #{gamedayId} ORDER BY id ASC FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<MeetingSession> selectAllByGamedayIdForUpdate(@Param("gamedayId") Integer gamedayId);

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
