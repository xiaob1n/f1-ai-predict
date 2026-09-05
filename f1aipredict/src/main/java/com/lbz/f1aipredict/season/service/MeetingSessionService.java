package com.lbz.f1aipredict.season.service;

import com.lbz.f1aipredict.season.dto.MeetingSessionDto;

import java.util.List;

/**
 * Session 只读查询服务契约。
 * <p>
 * 分站列表先复用 {@link RoundService#getById(Long)} 确认父资源；
 * sessionKey 是唯一单资源查询，meetingKey 是非唯一列表查询。
 * 返回 DTO 而非实体，不暴露审计列或 rawJson。
 */
public interface MeetingSessionService {

    /**
     * 先确认分站存在，再列出该分站下的全部 Session。
     * <p>
     * 父 Round 缺失时抛出 {@code Round not found: <id>}，且不访问 Session 列表 Mapper。
     * 父存在但无 Session 时返回非 null 空列表。列表顺序遵循 Mapper SQL 契约。
     *
     * @param roundId 分站主键
     * @return 按 Mapper 契约稳定排序的 Session 列表
     */
    List<MeetingSessionDto> listByRoundId(Long roundId);

    /**
     * 按主键查询 Session 详情。
     *
     * @param sessionId Session 主键
     * @return Session DTO
     */
    MeetingSessionDto getById(Long sessionId);

    /**
     * 按唯一 OpenF1 session_key 查询单条 Session。
     *
     * @param sessionKey OpenF1 session_key
     * @return Session DTO
     */
    MeetingSessionDto getBySessionKey(Integer sessionKey);

    /**
     * 按非唯一 OpenF1 meeting_key 列出 Session。
     * <p>
     * 未命中或 key 为空时返回非 null 空列表，永不升级为单资源 404。
     *
     * @param meetingKey OpenF1 meeting_key
     * @return 按 Mapper 契约稳定排序的 Session 列表
     */
    List<MeetingSessionDto> listByMeetingKey(Integer meetingKey);
}
