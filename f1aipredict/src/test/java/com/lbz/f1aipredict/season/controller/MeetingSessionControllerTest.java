package com.lbz.f1aipredict.season.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lbz.f1aipredict.common.GlobalExceptionHandler;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.season.dto.MeetingSessionDto;
import com.lbz.f1aipredict.season.service.MeetingSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MeetingSessionController 的 Web 层单元测试（计划 Todo 5）。
 * <p>
 * sessionKey 是单资源对象；meetingKey 是列表。父 Round 缺失 404，
 * 父存在子空 200 []。非数字 ID 与字面量 {@code /by-*} 不得走主键转换。
 */
class MeetingSessionControllerTest {

    private MeetingSessionService meetingSessionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        meetingSessionService = mock(MeetingSessionService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MeetingSessionController(meetingSessionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(isoJacksonConverter())
                .build();
    }

    /**
     * standalone MockMvc 不走 Boot 的 Jackson 自动配置，Jackson 2 默认把 Instant
     * 写成 epoch 数字。显式注册 JavaTimeModule 并关闭时间戳，对齐生产 ISO-8601。
     */
    private static MappingJackson2HttpMessageConverter isoJacksonConverter() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new MappingJackson2HttpMessageConverter(mapper);
    }

    @Test
    @DisplayName("分站 Session：父 Round 缺失时返回 404 Round not found: 999")
    void listByRound_whenParentMissing_returnsStable404() throws Exception {
        when(meetingSessionService.listByRoundId(999L))
                .thenThrow(new ResourceNotFoundException("Round not found: 999"));

        mockMvc.perform(get("/api/v1/rounds/{roundId}/sessions", 999L))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Round not found: 999"));

        verify(meetingSessionService).listByRoundId(999L);
    }

    @Test
    @DisplayName("分站 Session：父 Round 存在但无 Session 时返回 200 []")
    void listByRound_whenParentExistsWithoutChildren_returnsEmptyArray() throws Exception {
        when(meetingSessionService.listByRoundId(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rounds/{roundId}/sessions", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(meetingSessionService).listByRoundId(1L);
    }

    @Test
    @DisplayName("分站 Session：命中时返回未包装的 Session 数组")
    void listByRound_whenFound_returnsSessionArray() throws Exception {
        when(meetingSessionService.listByRoundId(1L)).thenReturn(List.of(sessionDto(21L, 9961)));

        mockMvc.perform(get("/api/v1/rounds/{roundId}/sessions", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(21))
                .andExpect(jsonPath("$[0].session_name").value("Race"))
                .andExpect(jsonPath("$[0].start_date_utc").value("2026-03-08T04:00:00Z"));

        verify(meetingSessionService).listByRoundId(1L);
    }

    @Test
    @DisplayName("Session 详情：命中时返回单个 JSON 对象")
    void getById_whenFound_returnsSingleObject() throws Exception {
        when(meetingSessionService.getById(21L)).thenReturn(sessionDto(21L, 9961));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}", 21L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.session_key").value(9961));

        verify(meetingSessionService).getById(21L);
    }

    @Test
    @DisplayName("Session 详情：缺失时返回统一 404 体 Session not found: 999")
    void getById_whenMissing_returnsStable404() throws Exception {
        when(meetingSessionService.getById(999L))
                .thenThrow(new ResourceNotFoundException("Session not found: 999"));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Session not found: 999"));

        verify(meetingSessionService).getById(999L);
    }

    @Test
    @DisplayName("sessionKey：命中时返回单个 JSON 对象而不是数组")
    void getBySessionKey_whenFound_returnsSingleObject() throws Exception {
        when(meetingSessionService.getBySessionKey(9961)).thenReturn(sessionDto(21L, 9961));

        mockMvc.perform(get("/api/v1/sessions/by-session-key/{sessionKey}", 9961))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.session_key").value(9961));

        verify(meetingSessionService).getBySessionKey(9961);
        verify(meetingSessionService, never()).getById(org.mockito.ArgumentMatchers.any());
        verify(meetingSessionService, never()).listByMeetingKey(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("sessionKey：缺失时返回统一 404 体 Session not found for sessionKey: 999")
    void getBySessionKey_whenMissing_returnsStable404() throws Exception {
        when(meetingSessionService.getBySessionKey(999))
                .thenThrow(new ResourceNotFoundException("Session not found for sessionKey: 999"));

        mockMvc.perform(get("/api/v1/sessions/by-session-key/{sessionKey}", 999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Session not found for sessionKey: 999"));

        verify(meetingSessionService).getBySessionKey(999);
    }

    @Test
    @DisplayName("meetingKey：多条命中时返回 JSON 数组")
    void listByMeetingKey_whenMultiple_returnsArray() throws Exception {
        when(meetingSessionService.listByMeetingKey(1254))
                .thenReturn(List.of(sessionDto(21L, 9961), sessionDto(22L, 9962)));

        mockMvc.perform(get("/api/v1/sessions/by-meeting-key/{meetingKey}", 1254))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(21))
                .andExpect(jsonPath("$[1].id").value(22));

        verify(meetingSessionService).listByMeetingKey(1254);
        verify(meetingSessionService, never()).getById(org.mockito.ArgumentMatchers.any());
        verify(meetingSessionService, never()).getBySessionKey(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("meetingKey：未命中时返回 200 [] 而不是单资源 404")
    void listByMeetingKey_whenEmpty_returnsEmptyArray() throws Exception {
        when(meetingSessionService.listByMeetingKey(1254)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sessions/by-meeting-key/{meetingKey}", 1254))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(meetingSessionService).listByMeetingKey(1254);
    }

    @Test
    @DisplayName("非数字 sessionId：/sessions/foo 为 404 且不调用服务、不触发类型转换")
    void getById_whenNonNumeric_returns404WithoutServiceInvocation() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/foo"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(meetingSessionService);
    }

    private static MeetingSessionDto sessionDto(Long id, int sessionKey) {
        return MeetingSessionDto.builder()
                .id(id)
                .roundId(1L)
                .meetingKey(1254)
                .sessionKey(sessionKey)
                .sessionName("Race")
                .sessionType("Race")
                .gamedayId(202608)
                .startDateUtc(Instant.parse("2026-03-08T04:00:00Z"))
                .endDateUtc(Instant.parse("2026-03-08T06:00:00Z"))
                .status("SCHEDULED")
                .build();
    }
}
