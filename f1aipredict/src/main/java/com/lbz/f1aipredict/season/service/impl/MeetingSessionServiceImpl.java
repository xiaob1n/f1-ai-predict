package com.lbz.f1aipredict.season.service.impl;

import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.season.dto.MeetingSessionDto;
import com.lbz.f1aipredict.season.entity.MeetingSession;
import com.lbz.f1aipredict.season.mapper.MeetingSessionMapper;
import com.lbz.f1aipredict.season.service.MeetingSessionService;
import com.lbz.f1aipredict.season.service.RoundService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Session 只读查询服务实现。
 * <p>
 * 分站列表的父资源存在性复用已确认的 {@link RoundService#getById(Long)}，
 * 不直接访问 RoundMapper，也不依赖 Feed。列表顺序完全交给 Mapper SQL，
 * 本类只做一次查询后的显式 DTO 映射，禁止循环内访问 Mapper。
 */
@Service
public class MeetingSessionServiceImpl implements MeetingSessionService {

    private final MeetingSessionMapper meetingSessionMapper;
    private final RoundService roundService;

    /**
     * 构造器注入 Session Mapper 与 Round 查询服务。
     * 禁止注入 RoundMapper、FeedSyncService 或 WebClient。
     */
    public MeetingSessionServiceImpl(MeetingSessionMapper meetingSessionMapper,
                                     RoundService roundService) {
        this.meetingSessionMapper = meetingSessionMapper;
        this.roundService = roundService;
    }

    /**
     * 先走 RoundService 确认父分站，缺失时原样抛出 {@code Round not found: <id>}，
     * 成功后才调用一次 {@code selectByRoundId}。
     */
    @Override
    public List<MeetingSessionDto> listByRoundId(Long roundId) {
        // 父资源检查必须先于列表查询，以便精确保留 Round 404 且避免无意义 SQL。
        roundService.getById(roundId);
        return toDtoList(meetingSessionMapper.selectByRoundId(roundId));
    }

    /**
     * 主键查询：null ID 直接 404，避免对 MyBatis selectById(null) 发出无意义调用。
     */
    @Override
    public MeetingSessionDto getById(Long sessionId) {
        if (sessionId == null) {
            throw new ResourceNotFoundException("Session not found: null");
        }
        MeetingSession session = meetingSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ResourceNotFoundException("Session not found: " + sessionId);
        }
        return toDto(session);
    }

    /**
     * sessionKey 是唯一键查询：null 直接 404，未命中抛带 key 的精确错误。
     */
    @Override
    public MeetingSessionDto getBySessionKey(Integer sessionKey) {
        if (sessionKey == null) {
            throw new ResourceNotFoundException("Session not found for sessionKey: null");
        }
        MeetingSession session = meetingSessionMapper.selectBySessionKey(sessionKey);
        if (session == null) {
            throw new ResourceNotFoundException("Session not found for sessionKey: " + sessionKey);
        }
        return toDto(session);
    }

    /**
     * meetingKey 非唯一：null 视为无过滤命中，直接返回空列表且不发 SQL；
     * 未命中同样返回空列表，永不升级为单资源 404。
     */
    @Override
    public List<MeetingSessionDto> listByMeetingKey(Integer meetingKey) {
        if (meetingKey == null) {
            return Collections.emptyList();
        }
        return toDtoList(meetingSessionMapper.selectByMeetingKey(meetingKey));
    }

    /**
     * Mapper 列表可能被对抗性 mock 成 null，对外统一归一化为非 null 空列表。
     * 顺序原样保留，不在内存中重排。
     */
    private List<MeetingSessionDto> toDtoList(List<MeetingSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return Collections.emptyList();
        }
        return sessions.stream().map(this::toDto).toList();
    }

    /**
     * 将 MeetingSession 公开字段逐一映射到 DTO，不暴露 createdAt/updatedAt。
     */
    private MeetingSessionDto toDto(MeetingSession session) {
        MeetingSessionDto dto = new MeetingSessionDto();
        dto.setId(session.getId());
        dto.setRoundId(session.getRoundId());
        dto.setMeetingKey(session.getMeetingKey());
        dto.setSessionKey(session.getSessionKey());
        dto.setSessionName(session.getSessionName());
        dto.setSessionType(session.getSessionType());
        dto.setGamedayId(session.getGamedayId());
        dto.setStartDateUtc(session.getStartDateUtc());
        dto.setEndDateUtc(session.getEndDateUtc());
        dto.setStatus(session.getStatus());
        return dto;
    }
}
