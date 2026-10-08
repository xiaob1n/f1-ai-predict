package com.lbz.f1aipredict.season.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lbz.f1aipredict.common.GlobalExceptionHandler;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.season.dto.SeasonDto;
import com.lbz.f1aipredict.season.dto.SeasonPageDto;
import com.lbz.f1aipredict.season.dto.SeasonQuery;
import com.lbz.f1aipredict.season.service.SeasonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SeasonController 的 Web 层单元测试（计划 Todo 5）。
 * <p>
 * standalone MockMvc：手动 new 控制器并注入 Mockito 的 {@link SeasonService}，
 * 挂载真实 {@link GlobalExceptionHandler} 验证统一 404 体。不加载 Spring 上下文、不连库。
 */
class SeasonControllerTest {

    /** 被测控制器依赖的赛季查询服务 mock */
    private SeasonService seasonService;

    /** standalone MockMvc（每用例重建） */
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        seasonService = mock(SeasonService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SeasonController(seasonService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(isoJacksonConverter())
                .build();
    }

    /**
     * standalone MockMvc 不走 Boot 的 Jackson 自动配置，Jackson 2 默认把 LocalDate
     * 写成数组。显式注册 JavaTimeModule 并关闭时间戳，对齐生产 ISO-8601。
     */
    private static MappingJackson2HttpMessageConverter isoJacksonConverter() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new MappingJackson2HttpMessageConverter(mapper);
    }

    @Test
    @DisplayName("契约：list 签名为 list(SeasonQuery)，查询条件走 ModelAttribute")
    void list_methodSignature_isSeasonQueryModelAttribute() throws NoSuchMethodException {
        Method list = SeasonController.class.getMethod("list", SeasonQuery.class);
        Parameter[] parameters = list.getParameters();
        assertEquals(1, parameters.length, "list 只能有 SeasonQuery 一个参数");
        assertEquals(SeasonQuery.class, parameters[0].getType());
        assertTrue(
                parameters[0].isAnnotationPresent(ModelAttribute.class),
                "SeasonQuery 必须使用 @ModelAttribute 绑定 GET 查询参数");
    }

    @Test
    @DisplayName("当前赛季：命中时返回未包装的 SeasonDto JSON")
    void getCurrent_whenFound_returnsSeasonJson() throws Exception {
        when(seasonService.getCurrentSeason()).thenReturn(seasonDto());

        mockMvc.perform(get("/api/v1/seasons/current"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.name").value("2026 FIA Formula One World Championship"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.start_date").value("2026-03-06"))
                .andExpect(jsonPath("$.end_date").value("2026-12-06"))
                .andExpect(jsonPath("$.success").doesNotExist());

        verify(seasonService).getCurrentSeason();
    }

    @Test
    @DisplayName("当前赛季：无候选时返回统一 404 体 Current season not found")
    void getCurrent_whenMissing_returnsStable404() throws Exception {
        when(seasonService.getCurrentSeason())
                .thenThrow(new ResourceNotFoundException("Current season not found"));

        mockMvc.perform(get("/api/v1/seasons/current"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Current season not found"));

        verify(seasonService).getCurrentSeason();
    }

    @Test
    @DisplayName("赛季列表：未传分页时默认 page=0 size=20")
    void list_whenPagingAbsent_bindsDefaultPageZeroSizeTwenty() throws Exception {
        when(seasonService.listSeasons(any(SeasonQuery.class)))
                .thenReturn(pageDto(List.of(seasonDto()), 0, 20, 1L));

        mockMvc.perform(get("/api/v1/seasons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].id").value(10))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(1));

        ArgumentCaptor<SeasonQuery> captor = ArgumentCaptor.forClass(SeasonQuery.class);
        verify(seasonService).listSeasons(captor.capture());
        assertEquals(0, captor.getValue().getPage());
        assertEquals(20, captor.getValue().getSize());
    }

    @Test
    @DisplayName("赛季列表：负页码与超大 size 在进入服务前裁剪为 page=0 size=100")
    void list_whenNegativeAndOversizedPaging_clampsAtControllerBoundary() throws Exception {
        when(seasonService.listSeasons(any(SeasonQuery.class)))
                .thenReturn(pageDto(List.of(), 0, 100, 0L));

        mockMvc.perform(get("/api/v1/seasons")
                        .param("page", "-1")
                        .param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.items").isArray());

        ArgumentCaptor<SeasonQuery> captor = ArgumentCaptor.forClass(SeasonQuery.class);
        verify(seasonService).listSeasons(captor.capture());
        assertEquals(0, captor.getValue().getPage(), "page=-1 必须在 Controller 裁剪为 0");
        assertEquals(100, captor.getValue().getSize(), "size=1000 必须在 Controller 裁剪为 100");
    }

    @Test
    @DisplayName("赛季列表：未知 status 原样转发给服务并返回空页，而不是 400")
    void list_whenUnknownStatus_forwardsAndReturnsEmptyPage() throws Exception {
        when(seasonService.listSeasons(any(SeasonQuery.class)))
                .thenReturn(pageDto(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/v1/seasons").param("status", "NOT_A_REAL_STATUS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(0));

        ArgumentCaptor<SeasonQuery> captor = ArgumentCaptor.forClass(SeasonQuery.class);
        verify(seasonService).listSeasons(captor.capture());
        assertEquals("NOT_A_REAL_STATUS", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("写保护：POST /seasons/current 返回 405 且不调用服务")
    void postCurrent_isMethodNotAllowedWithoutServiceInvocation() throws Exception {
        mockMvc.perform(post("/api/v1/seasons/current"))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(seasonService);
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

    private static SeasonPageDto pageDto(List<SeasonDto> items, int page, int size, long total) {
        return SeasonPageDto.builder()
                .items(items)
                .page(page)
                .size(size)
                .total(total)
                .build();
    }
}
