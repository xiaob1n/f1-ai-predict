package com.lbz.f1aipredict.season.controller;

import com.lbz.f1aipredict.common.GlobalExceptionHandler;
import com.lbz.f1aipredict.question.controller.QuestionController;
import com.lbz.f1aipredict.question.dto.QuestionDto;
import com.lbz.f1aipredict.question.dto.QuestionQuery;
import com.lbz.f1aipredict.question.service.QuestionService;
import com.lbz.f1aipredict.season.dto.MeetingSessionDto;
import com.lbz.f1aipredict.season.dto.RoundDto;
import com.lbz.f1aipredict.season.service.MeetingSessionService;
import com.lbz.f1aipredict.season.service.RoundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨 Controller 路由回归（计划 Todo 5）。
 * <p>
 * 同时注册 Round / Session / Question 控制器，证明
 * {@code GET /api/v1/rounds/{roundId}/questions} 仍由 {@link QuestionController} 独占，
 * 不会被新的 Round 数字路由或 Session 列表路由抢占。
 */
class SeasonRoundRoutingTest {

    private RoundService roundService;
    private MeetingSessionService meetingSessionService;
    private QuestionService questionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        roundService = mock(RoundService.class);
        meetingSessionService = mock(MeetingSessionService.class);
        questionService = mock(QuestionService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new RoundController(roundService),
                        new MeetingSessionController(meetingSessionService),
                        new QuestionController(questionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("路由所有权：/rounds/1/questions 只调用 QuestionService")
    void getRoundQuestions_invokesQuestionServiceOnly() throws Exception {
        QuestionDto question = QuestionDto.builder()
                .questionId(7L)
                .questionText("谁将赢得冲刺赛？")
                .build();
        when(questionService.listByRoundId(eq(1L), any(QuestionQuery.class)))
                .thenReturn(List.of(question));

        mockMvc.perform(get("/api/v1/rounds/{roundId}/questions", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].question_id").value(7));

        verify(questionService).listByRoundId(eq(1L), any(QuestionQuery.class));
        verifyNoInteractions(roundService);
        verifyNoInteractions(meetingSessionService);
    }

    @Test
    @DisplayName("路由所有权：/rounds/1 只调用 RoundService.getById")
    void getRoundById_invokesRoundServiceOnly() throws Exception {
        when(roundService.getById(1L)).thenReturn(RoundDto.builder().id(1L).seasonId(10L).build());

        mockMvc.perform(get("/api/v1/rounds/{roundId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        verify(roundService).getById(1L);
        verifyNoInteractions(questionService);
        verifyNoInteractions(meetingSessionService);
    }

    @Test
    @DisplayName("路由所有权：/rounds/1/sessions 只调用 MeetingSessionService")
    void getRoundSessions_invokesMeetingSessionServiceOnly() throws Exception {
        when(meetingSessionService.listByRoundId(1L))
                .thenReturn(List.of(MeetingSessionDto.builder().id(21L).roundId(1L).build()));

        mockMvc.perform(get("/api/v1/rounds/{roundId}/sessions", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(21));

        verify(meetingSessionService).listByRoundId(1L);
        verifyNoInteractions(questionService);
        verifyNoInteractions(roundService);
    }
}
