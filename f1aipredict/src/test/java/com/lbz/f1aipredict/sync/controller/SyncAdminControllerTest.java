package com.lbz.f1aipredict.sync.controller;

import com.lbz.f1aipredict.common.GlobalExceptionHandler;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.sync.dto.RawPayloadDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordPageDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordQuery;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import com.lbz.f1aipredict.sync.service.FeedSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SyncAdminController 的 Web 层单元测试（计划 Todo 9）。
 * <p>
 * standalone MockMvc：手动 new 控制器并注入 Mockito 的 {@link FeedSyncService}，
 * 挂载真实 {@link GlobalExceptionHandler} 验证 404 契约。不加载 Spring 上下文、不连库。
 */
class SyncAdminControllerTest {

    /** 被测控制器依赖的同步服务 mock */
    private FeedSyncService feedSyncService;

    /** standalone MockMvc（每用例重建） */
    private MockMvc mockMvc;

    /**
     * 每个用例前重建 mock 与 MockMvc。
     */
    @BeforeEach
    void setUp() {
        feedSyncService = mock(FeedSyncService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SyncAdminController(feedSyncService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /schedule → 200，JSON sourceType=SCHEDULE")
    void postSchedule_returns200WithScheduleSourceType() throws Exception {
        when(feedSyncService.syncSchedule()).thenReturn(result("SCHEDULE"));

        mockMvc.perform(post("/api/v1/admin/sync/schedule"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.source_type").value("SCHEDULE"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(feedSyncService).syncSchedule();
    }

    @Test
    @DisplayName("POST /current → 200，JSON sourceType=CURRENT")
    void postCurrent_returns200WithCurrentSourceType() throws Exception {
        when(feedSyncService.syncCurrent()).thenReturn(result("CURRENT"));

        mockMvc.perform(post("/api/v1/admin/sync/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_type").value("CURRENT"));

        verify(feedSyncService).syncCurrent();
    }

    @Test
    @DisplayName("POST /questions/1234 → 服务以 gamedayId=1234 被调用")
    void postQuestions_passesGamedayId1234() throws Exception {
        when(feedSyncService.syncQuestions(1234)).thenReturn(result("QUESTIONS"));

        mockMvc.perform(post("/api/v1/admin/sync/questions/{gamedayId}", 1234))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_type").value("QUESTIONS"));

        verify(feedSyncService).syncQuestions(1234);
    }

    @Test
    @DisplayName("POST /limits → 200，JSON sourceType=LIMITS")
    void postLimits_returns200WithLimitsSourceType() throws Exception {
        when(feedSyncService.syncLimits()).thenReturn(result("LIMITS"));

        mockMvc.perform(post("/api/v1/admin/sync/limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_type").value("LIMITS"));

        verify(feedSyncService).syncLimits();
    }

    @Test
    @DisplayName("GET /records?size=200 → 服务收到 size=100")
    void getRecords_size200_clampedTo100BeforeService() throws Exception {
        when(feedSyncService.pageRecords(any(SyncRecordQuery.class))).thenReturn(emptyPage(0, 100));

        mockMvc.perform(get("/api/v1/admin/sync/records").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.page").value(0));

        ArgumentCaptor<SyncRecordQuery> captor = ArgumentCaptor.forClass(SyncRecordQuery.class);
        verify(feedSyncService).pageRecords(captor.capture());
        assertEquals(100, captor.getValue().getSize(), "size=200 必须在进入服务前裁剪为 100");
    }

    @Test
    @DisplayName("GET /records 默认 page=0")
    void getRecords_defaultPageIsZero() throws Exception {
        when(feedSyncService.pageRecords(any(SyncRecordQuery.class))).thenReturn(emptyPage(0, 20));

        mockMvc.perform(get("/api/v1/admin/sync/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        ArgumentCaptor<SyncRecordQuery> captor = ArgumentCaptor.forClass(SyncRecordQuery.class);
        verify(feedSyncService).pageRecords(captor.capture());
        assertEquals(0, captor.getValue().getPage(), "未传 page 时服务必须收到 0-based 第一页");
        assertEquals(20, captor.getValue().getSize(), "未传 size 时默认 20");
    }

    @Test
    @DisplayName("GET /records?size=0 → 服务收到默认 size=20")
    void getRecords_sizeZero_clampedToDefault20() throws Exception {
        when(feedSyncService.pageRecords(any(SyncRecordQuery.class))).thenReturn(emptyPage(0, 20));

        mockMvc.perform(get("/api/v1/admin/sync/records").param("size", "0"))
                .andExpect(status().isOk());

        ArgumentCaptor<SyncRecordQuery> captor = ArgumentCaptor.forClass(SyncRecordQuery.class);
        verify(feedSyncService).pageRecords(captor.capture());
        assertEquals(20, captor.getValue().getSize(), "size=0 视为非法，回落默认 20");
    }

    @Test
    @DisplayName("GET /raw-payloads/99 服务抛 ResourceNotFoundException → 404 RESOURCE_NOT_FOUND")
    void getRawPayload_missing_returnsStable404() throws Exception {
        when(feedSyncService.getRawPayload(99L))
                .thenThrow(new ResourceNotFoundException("Raw payload not found: 99"));

        mockMvc.perform(get("/api/v1/admin/sync/raw-payloads/{payloadId}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Raw payload not found: 99"));

        verify(feedSyncService).getRawPayload(99L);
    }

    @Test
    @DisplayName("GET /raw-payloads/1 → 200 且含 rawJson 字段")
    void getRawPayload_found_returnsRawJson() throws Exception {
        RawPayloadDto dto = RawPayloadDto.builder()
                .payloadId(1L)
                .sourceType("SCHEDULE")
                .sourceUrl("https://example.test/schedule.json")
                .contentHash("abc")
                .rawJson("{\"ok\":true}")
                .fetchedAt(Instant.parse("2026-08-31T12:00:00Z"))
                .build();
        when(feedSyncService.getRawPayload(1L)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/admin/sync/raw-payloads/{payloadId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload_id").value(1))
                .andExpect(jsonPath("$.raw_json").value("{\"ok\":true}"))
                .andExpect(jsonPath("$.source_type").value("SCHEDULE"));

        verify(feedSyncService).getRawPayload(1L);
    }

    @Test
    @DisplayName("GET /raw-payloads/abc 路径非整型 → Spring 400 而非 200")
    void getRawPayload_malformedId_isBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/sync/raw-payloads/{payloadId}", "abc"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 构造最小同步结果，仅锁定 sourceType / status。
     */
    private static SyncResultDto result(String sourceType) {
        return SyncResultDto.builder()
                .sourceType(sourceType)
                .status("SUCCESS")
                .build();
    }

    /**
     * 构造空分页结果，page/size 与控制器裁剪后的值对齐。
     */
    private static SyncRecordPageDto emptyPage(int page, int size) {
        return SyncRecordPageDto.builder()
                .items(List.<SyncRecordDto>of())
                .page(page)
                .size(size)
                .total(0L)
                .build();
    }
}
