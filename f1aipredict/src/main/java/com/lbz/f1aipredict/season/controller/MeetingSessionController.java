package com.lbz.f1aipredict.season.controller;

import com.lbz.f1aipredict.season.dto.MeetingSessionDto;
import com.lbz.f1aipredict.season.service.MeetingSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Session 只读查询接口控制器。
 * <p>
 * 分站列表先由服务确认父 Round；sessionKey 是唯一单资源，meetingKey 是非唯一列表。
 * {@code /by-session-key} 与 {@code /by-meeting-key} 必须是字面量前缀，
 * 数字段加 {@code \\d+}，避免把 {@code foo} 当成 Integer 做类型转换。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MeetingSessionController {

    /** Session 只读查询服务，构造器注入 */
    private final MeetingSessionService meetingSessionService;

    /**
     * 查询分站下的全部 Session（GET /api/v1/rounds/{roundId}/sessions）。
     * <p>
     * 父 Round 缺失由服务抛出 {@code Round not found: <id>}；父存在但无 Session 返回空列表。
     * 本路由不得声明 {@code /questions}，以免抢占 QuestionController。
     *
     * @param roundId 分站主键（仅数字）
     * @return Session DTO 列表，无包装
     */
    @GetMapping("/rounds/{roundId:\\d+}/sessions")
    public List<MeetingSessionDto> listByRoundId(@PathVariable Long roundId) {
        return meetingSessionService.listByRoundId(roundId);
    }

    /**
     * 按主键查询 Session 详情（GET /api/v1/sessions/{sessionId}）。
     * <p>
     * 数字正则保证 {@code /sessions/foo} 不进入本方法、不触发类型转换。
     *
     * @param sessionId Session 主键（仅数字）
     * @return 单个 Session DTO
     */
    @GetMapping("/sessions/{sessionId:\\d+}")
    public MeetingSessionDto getById(@PathVariable Long sessionId) {
        return meetingSessionService.getById(sessionId);
    }

    /**
     * 按唯一 OpenF1 session_key 查询单条 Session
     * （GET /api/v1/sessions/by-session-key/{sessionKey}）。
     * <p>
     * 字面量 {@code /by-session-key} 优先于 {@code /{sessionId}}，返回单个 JSON 对象。
     *
     * @param sessionKey OpenF1 session_key（仅数字）
     * @return 单个 Session DTO
     */
    @GetMapping("/sessions/by-session-key/{sessionKey:\\d+}")
    public MeetingSessionDto getBySessionKey(@PathVariable Integer sessionKey) {
        return meetingSessionService.getBySessionKey(sessionKey);
    }

    /**
     * 按非唯一 OpenF1 meeting_key 列出 Session
     * （GET /api/v1/sessions/by-meeting-key/{meetingKey}）。
     * <p>
     * 字面量 {@code /by-meeting-key} 优先于主键路由；未命中返回空数组，不升级为 404。
     *
     * @param meetingKey OpenF1 meeting_key（仅数字）
     * @return Session DTO 列表
     */
    @GetMapping("/sessions/by-meeting-key/{meetingKey:\\d+}")
    public List<MeetingSessionDto> listByMeetingKey(@PathVariable Integer meetingKey) {
        return meetingSessionService.listByMeetingKey(meetingKey);
    }
}
