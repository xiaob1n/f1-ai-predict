package com.lbz.f1aipredict.season.controller;

import com.lbz.f1aipredict.common.GlobalExceptionHandler;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.season.dto.CurrentRoundDto;
import com.lbz.f1aipredict.season.dto.MeetingSessionDto;
import com.lbz.f1aipredict.season.dto.RoundDto;
import com.lbz.f1aipredict.season.dto.SeasonDto;
import com.lbz.f1aipredict.season.service.RoundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RoundController 的 Web 层单元测试（计划 Todo 5）。
 * <p>
 * 覆盖当前分站聚合 DTO、普通详情、赛季分站列表的父资源 404 / 空子集合 200，
 * 以及 {@code /current} 字面量路由与非数字 ID 的 404（不触发类型转换）。
 */
class RoundControllerTest {

    private RoundService roundService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        roundService = mock(RoundService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RoundController(roundService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("当前分站：命中时返回 round/season/sessions/gamedayId 聚合 JSON")
    void getCurrent_whenFound_returnsAggregationJson() throws Exception {
        when(roundService.getCurrentRound()).thenReturn(currentRoundDto());

        mockMvc.perform(get("/api/v1/rounds/current"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.round.id").value(1))
                .andExpect(jsonPath("$.round.grand_prix_name").value("Australian Grand Prix"))
                .andExpect(jsonPath("$.season.id").value(10))
                .andExpect(jsonPath("$.season.year").value(2026))
                .andExpect(jsonPath("$.sessions").isArray())
                .andExpect(jsonPath("$.sessions[0].session_name").value("Race"))
                .andExpect(jsonPath("$.gameday_id").value(202608));

        verify(roundService).getCurrentRound();
        verify(roundService, never()).getById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("当前分站：无候选时返回统一 404 体 Current round not found")
    void getCurrent_whenMissing_returnsStable404() throws Exception {
        when(roundService.getCurrentRound())
                .thenThrow(new ResourceNotFoundException("Current round not found"));

        mockMvc.perform(get("/api/v1/rounds/current"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Current round not found"));

        verify(roundService).getCurrentRound();
        verify(roundService, never()).getById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("分站详情：命中时返回普通 RoundDto，而不是 CurrentRound 聚合")
    void getById_whenFound_returnsOrdinaryRoundJson() throws Exception {
        when(roundService.getById(1L)).thenReturn(roundDto());

        mockMvc.perform(get("/api/v1/rounds/{roundId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.season_id").value(10))
                .andExpect(jsonPath("$.round_number").value(1))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.round").doesNotExist())
                .andExpect(jsonPath("$.sessions").doesNotExist())
                .andExpect(jsonPath("$.gameday_id").doesNotExist());

        verify(roundService).getById(1L);
    }

    @Test
    @DisplayName("分站详情：缺失时返回统一 404 体 Round not found: 999")
    void getById_whenMissing_returnsStable404() throws Exception {
        when(roundService.getById(999L))
                .thenThrow(new ResourceNotFoundException("Round not found: 999"));

        mockMvc.perform(get("/api/v1/rounds/{roundId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Round not found: 999"));

        verify(roundService).getById(999L);
    }

    @Test
    @DisplayName("赛季分站：父赛季缺失时返回 404 Season not found: 999")
    void listBySeason_whenParentMissing_returnsStable404() throws Exception {
        when(roundService.listBySeasonId(999L))
                .thenThrow(new ResourceNotFoundException("Season not found: 999"));

        mockMvc.perform(get("/api/v1/seasons/{seasonId}/rounds", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Season not found: 999"));

        verify(roundService).listBySeasonId(999L);
    }

    @Test
    @DisplayName("赛季分站：父赛季存在但无子分站时返回 200 []")
    void listBySeason_whenParentExistsWithoutChildren_returnsEmptyArray() throws Exception {
        when(roundService.listBySeasonId(10L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/seasons/{seasonId}/rounds", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty())
                .andExpect(jsonPath("$.success").doesNotExist());

        verify(roundService).listBySeasonId(10L);
    }

    @Test
    @DisplayName("赛季分站：命中时返回未包装的 RoundDto 数组")
    void listBySeason_whenFound_returnsRoundArray() throws Exception {
        when(roundService.listBySeasonId(10L)).thenReturn(List.of(roundDto()));

        mockMvc.perform(get("/api/v1/seasons/{seasonId}/rounds", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].grand_prix_name").value("Australian Grand Prix"));

        verify(roundService).listBySeasonId(10L);
    }

    @Test
    @DisplayName("非数字 roundId：/rounds/foo 为 404 且不调用服务、不触发类型转换")
    void getById_whenNonNumeric_returns404WithoutServiceInvocation() throws Exception {
        mockMvc.perform(get("/api/v1/rounds/foo"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(roundService);
    }

    @Test
    @DisplayName("写保护：POST /rounds/current 返回 405 且不调用服务")
    void postCurrent_isMethodNotAllowedWithoutServiceInvocation() throws Exception {
        mockMvc.perform(post("/api/v1/rounds/current"))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(roundService);
    }

    private static CurrentRoundDto currentRoundDto() {
        return CurrentRoundDto.builder()
                .round(roundDto())
                .season(seasonDto())
                .sessions(List.of(sessionDto()))
                .gamedayId(202608)
                .build();
    }

    private static RoundDto roundDto() {
        return RoundDto.builder()
                .id(1L)
                .seasonId(10L)
                .roundNumber(1)
                .grandPrixName("Australian Grand Prix")
                .officialName("Formula 1 Rolex Australian Grand Prix 2026")
                .circuitName("Albert Park")
                .country("Australia")
                .locality("Melbourne")
                .startDate(LocalDate.of(2026, 3, 6))
                .endDate(LocalDate.of(2026, 3, 8))
                .status("IN_PROGRESS")
                .build();
    }

    private static SeasonDto seasonDto() {
        return SeasonDto.builder()
                .id(10L)
                .year(2026)
                .name("2026 FIA Formula One World Championship")
                .status("IN_PROGRESS")
                .startDate(LocalDate.of(2026, 3, 6))
                .endDate(LocalDate.of(2026, 12, 6))
                .build();
    }

    private static MeetingSessionDto sessionDto() {
        return MeetingSessionDto.builder()
                .id(21L)
                .roundId(1L)
                .meetingKey(1254)
                .sessionKey(9961)
                .sessionName("Race")
                .sessionType("Race")
                .gamedayId(202608)
                .startDateUtc(Instant.parse("2026-03-08T04:00:00Z"))
                .endDateUtc(Instant.parse("2026-03-08T06:00:00Z"))
                .status("SCHEDULED")
                .build();
    }
}
