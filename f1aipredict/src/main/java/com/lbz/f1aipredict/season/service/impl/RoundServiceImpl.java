package com.lbz.f1aipredict.season.service.impl;

import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.season.dto.CurrentRoundDto;
import com.lbz.f1aipredict.season.dto.MeetingSessionDto;
import com.lbz.f1aipredict.season.dto.RoundDto;
import com.lbz.f1aipredict.season.dto.SeasonDto;
import com.lbz.f1aipredict.season.entity.MeetingSession;
import com.lbz.f1aipredict.season.entity.Round;
import com.lbz.f1aipredict.season.entity.Season;
import com.lbz.f1aipredict.season.mapper.MeetingSessionMapper;
import com.lbz.f1aipredict.season.mapper.RoundMapper;
import com.lbz.f1aipredict.season.mapper.SeasonMapper;
import com.lbz.f1aipredict.season.service.RoundService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分站只读查询服务实现。
 * <p>
 * 当前分站选择一次性批量读取全部 Round 与 Session 候选，随后只在内存中关联和筛选，
 * 避免按候选逐条访问 Mapper，并通过注入的 UTC {@link Clock} 保证结果可重复测试。
 */
@Service
public class RoundServiceImpl implements RoundService {

    private static final String CANCELLED = "CANCELLED";
    private static final String IN_PROGRESS = "IN_PROGRESS";

    /**
     * Round 类优先级统一按开始日期空值最后，再按分站号和 ID 升序，保证输入顺序不影响结果。
     */
    private static final Comparator<Round> ROUND_ORDER = Comparator
            .comparing(Round::getStartDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Round::getRoundNumber, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Round::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    /** Session 聚合顺序与 Mapper 列表契约一致：开始时间空值最后，随后按 ID 升序。 */
    private static final Comparator<MeetingSession> SESSION_ORDER = Comparator
            .comparing(MeetingSession::getStartDateUtc, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(MeetingSession::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    /** 当前 Session 窗口命中时优先最近开始者，相同开始时间取较大 ID。 */
    private static final Comparator<MeetingSession> ACTIVE_SESSION_ORDER = Comparator
            .comparing(MeetingSession::getStartDateUtc, Comparator.reverseOrder())
            .thenComparing(MeetingSession::getId, Comparator.nullsLast(Comparator.reverseOrder()));

    private final RoundMapper roundMapper;
    private final MeetingSessionMapper meetingSessionMapper;
    private final SeasonMapper seasonMapper;
    private final Clock clock;

    public RoundServiceImpl(RoundMapper roundMapper,
                            MeetingSessionMapper meetingSessionMapper,
                            SeasonMapper seasonMapper,
                            Clock clock) {
        this.roundMapper = roundMapper;
        this.meetingSessionMapper = meetingSessionMapper;
        this.seasonMapper = seasonMapper;
        this.clock = clock;
    }

    /**
     * 严格执行五级确定性选择：进行中状态、Session 窗口、Round 日期窗、未来 Session、未来 Round。
     */
    @Override
    public CurrentRoundDto getCurrentRound() {
        List<Round> rounds = roundMapper.selectList(null);
        List<MeetingSession> sessions = meetingSessionMapper.selectList(null);
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);

        Map<Long, Round> eligibleRoundsById = new HashMap<>();
        for (Round round : rounds) {
            if (!CANCELLED.equals(round.getStatus())) {
                eligibleRoundsById.put(round.getId(), round);
            }
        }

        Round selected = selectInProgressRound(rounds);
        if (selected == null) {
            selected = selectByActiveSession(sessions, eligibleRoundsById, now);
        }
        if (selected == null) {
            selected = selectByRoundDateWindow(rounds, today);
        }
        if (selected == null) {
            selected = selectByFutureSession(sessions, eligibleRoundsById, now);
        }
        if (selected == null) {
            selected = selectFutureRound(rounds, today);
        }
        if (selected == null) {
            throw currentRoundNotFound();
        }

        Season season = seasonMapper.selectById(selected.getSeasonId());
        if (season == null) {
            throw currentRoundNotFound();
        }

        List<MeetingSession> selectedSessions = new ArrayList<>();
        for (MeetingSession session : sessions) {
            if (Objects.equals(session.getRoundId(), selected.getId())) {
                selectedSessions.add(session);
            }
        }
        selectedSessions.sort(SESSION_ORDER);

        List<MeetingSessionDto> sessionDtos = selectedSessions.stream()
                .map(this::toMeetingSessionDto)
                .toList();
        Integer gamedayId = selectedSessions.stream()
                .map(MeetingSession::getGamedayId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return CurrentRoundDto.builder()
                .round(toRoundDto(selected))
                .season(toSeasonDto(season))
                .sessions(sessionDtos)
                .gamedayId(gamedayId)
                .build();
    }

    /** 按主键查询分站，不存在时返回稳定的资源级错误。 */
    @Override
    public RoundDto getById(Long roundId) {
        if (roundId == null) {
            throw new ResourceNotFoundException("Round not found: null");
        }
        Round round = roundMapper.selectById(roundId);
        if (round == null) {
            throw new ResourceNotFoundException("Round not found: " + roundId);
        }
        return toRoundDto(round);
    }

    /** 先确认父赛季存在，再复用 Mapper 的稳定分站列表契约。 */
    @Override
    public List<RoundDto> listBySeasonId(Long seasonId) {
        if (seasonId == null) {
            throw new ResourceNotFoundException("Season not found: null");
        }
        if (seasonMapper.selectById(seasonId) == null) {
            throw new ResourceNotFoundException("Season not found: " + seasonId);
        }
        return roundMapper.selectBySeasonId(seasonId).stream()
                .map(this::toRoundDto)
                .toList();
    }

    /** 优先级一：显式进行中分站。 */
    private Round selectInProgressRound(List<Round> rounds) {
        return rounds.stream()
                .filter(this::isEligibleRound)
                .filter(round -> IN_PROGRESS.equals(round.getStatus()))
                .min(ROUND_ORDER)
                .orElse(null);
    }

    /** 优先级二：当前时刻位于完整 Session 闭区间内。 */
    private Round selectByActiveSession(List<MeetingSession> sessions,
                                        Map<Long, Round> eligibleRoundsById,
                                        Instant now) {
        return sessions.stream()
                .filter(session -> eligibleRoundsById.containsKey(session.getRoundId()))
                .filter(session -> session.getStartDateUtc() != null && session.getEndDateUtc() != null)
                .filter(session -> !session.getStartDateUtc().isAfter(now))
                .filter(session -> !session.getEndDateUtc().isBefore(now))
                .min(ACTIVE_SESSION_ORDER)
                .map(session -> eligibleRoundsById.get(session.getRoundId()))
                .orElse(null);
    }

    /** 优先级三：UTC today 位于分站开始和结束日期闭区间内。 */
    private Round selectByRoundDateWindow(List<Round> rounds, LocalDate today) {
        return rounds.stream()
                .filter(this::isEligibleRound)
                .filter(round -> round.getStartDate() != null && round.getEndDate() != null)
                .filter(round -> !round.getStartDate().isAfter(today))
                .filter(round -> !round.getEndDate().isBefore(today))
                .min(ROUND_ORDER)
                .orElse(null);
    }

    /** 优先级四：最近的未来 Session，结束时间允许为空。 */
    private Round selectByFutureSession(List<MeetingSession> sessions,
                                        Map<Long, Round> eligibleRoundsById,
                                        Instant now) {
        return sessions.stream()
                .filter(session -> eligibleRoundsById.containsKey(session.getRoundId()))
                .filter(session -> session.getStartDateUtc() != null && session.getStartDateUtc().isAfter(now))
                .min(SESSION_ORDER)
                .map(session -> eligibleRoundsById.get(session.getRoundId()))
                .orElse(null);
    }

    /** 优先级五：最近的未来分站。 */
    private Round selectFutureRound(List<Round> rounds, LocalDate today) {
        return rounds.stream()
                .filter(this::isEligibleRound)
                .filter(round -> round.getStartDate() != null && round.getStartDate().isAfter(today))
                .min(ROUND_ORDER)
                .orElse(null);
    }

    private boolean isEligibleRound(Round round) {
        return !CANCELLED.equals(round.getStatus());
    }

    private ResourceNotFoundException currentRoundNotFound() {
        return new ResourceNotFoundException("Current round not found");
    }

    /** 将 Round 实体逐字段映射为公开 DTO，不暴露审计字段。 */
    private RoundDto toRoundDto(Round round) {
        RoundDto dto = new RoundDto();
        dto.setId(round.getId());
        dto.setSeasonId(round.getSeasonId());
        dto.setRoundNumber(round.getRoundNumber());
        dto.setGrandPrixName(round.getGrandPrixName());
        dto.setOfficialName(round.getOfficialName());
        dto.setCircuitName(round.getCircuitName());
        dto.setCountry(round.getCountry());
        dto.setLocality(round.getLocality());
        dto.setStartDate(round.getStartDate());
        dto.setEndDate(round.getEndDate());
        dto.setStatus(round.getStatus());
        return dto;
    }

    /** 将 Season 实体逐字段映射为当前聚合中的公开 DTO。 */
    private SeasonDto toSeasonDto(Season season) {
        SeasonDto dto = new SeasonDto();
        dto.setId(season.getId());
        dto.setYear(season.getYear());
        dto.setName(season.getName());
        dto.setStatus(season.getStatus());
        dto.setStartDate(season.getStartDate());
        dto.setEndDate(season.getEndDate());
        return dto;
    }

    /** 将 MeetingSession 实体逐字段映射为公开 DTO，不暴露审计字段。 */
    private MeetingSessionDto toMeetingSessionDto(MeetingSession session) {
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
