package com.lbz.f1aipredict.question.controller;

import com.lbz.f1aipredict.common.GlobalExceptionHandler;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.question.dto.QuestionDetailDto;
import com.lbz.f1aipredict.question.dto.QuestionDto;
import com.lbz.f1aipredict.question.dto.QuestionOptionDto;
import com.lbz.f1aipredict.question.dto.QuestionQuery;
import com.lbz.f1aipredict.question.dto.QuestionSnapshotDto;
import com.lbz.f1aipredict.question.service.QuestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
 * QuestionController 的 Web 层单元测试（计划 Todo 6）。
 * <p>
 * 使用 standalone MockMvc：手动 new QuestionController(questionService)，服务以 Mockito mock 注入，
 * 并挂载真实的 {@link GlobalExceptionHandler} 验证 404 契约。不加载 Spring 上下文，
 * 不触碰数据库/datasource/MyBatis 的任何实现，全部断言基于真实 JSON 响应体。
 *
 * @see QuestionController
 * @see QuestionService
 */
class QuestionControllerTest {

    /** 被测试控制器依赖的题目只读服务 mock */
    private QuestionService questionService;

    /** standalone MockMvc 实例（每用例重建，保证隔离） */
    private MockMvc mockMvc;

    /**
     * 每个用例前重建 mock 与 MockMvc：
     * 控制器仅依赖 QuestionService，全局异常处理器使用真实实现以验证 404/405 行为。
     */
    @BeforeEach
    void setUp() {
        questionService = mock(QuestionService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new QuestionController(questionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 构造一个包含单个选项的题目列表返回体，供各用例复用。
     *
     * @return 含一个题目、一个选项的列表
     */
    private static List<QuestionDto> singleQuestionList() {
        QuestionOptionDto option = QuestionOptionDto.builder()
                .optionId(1)
                .optionNo(1)
                .optionText("Lewis Hamilton 获胜")
                .points(10)
                .chance(new BigDecimal("0.35"))
                .build();
        QuestionDto question = QuestionDto.builder()
                .questionId(7L)
                .gamedayId(202608)
                .sourceQuestionId(101)
                .questionNo(1)
                .questionText("谁将赢得冲刺赛？")
                .status("OPEN")
                .latestSnapshotId(9L)
                .options(List.of(option))
                .build();
        return List.of(question);
    }

    /** 构造一个包含单个选项的详情 DTO，供详情/快照用例复用 */
    private static QuestionDetailDto detailDto() {
        QuestionOptionDto option = QuestionOptionDto.builder()
                .optionId(1)
                .optionNo(1)
                .optionText("Lewis Hamilton 获胜")
                .points(10)
                .chance(new BigDecimal("0.35"))
                .build();
        return QuestionDetailDto.builder()
                .questionId(7L)
                .gamedayId(202608)
                .sourceQuestionId(101)
                .questionNo(1)
                .questionText("谁将赢得冲刺赛？")
                .status("OPEN")
                .latestSnapshotId(9L)
                .options(List.of(option))
                .build();
    }

    /** 构造一个包含 hasRawJson 标记的快照 DTO */
    private static QuestionSnapshotDto snapshotDto() {
        QuestionOptionDto option = QuestionOptionDto.builder()
                .optionId(2)
                .optionNo(1)
                .optionText("Charles Leclerc 获胜")
                .points(10)
                .chance(new BigDecimal("0.28"))
                .build();
        return QuestionSnapshotDto.builder()
                .snapshotId(9L)
                .questionId(7L)
                .snapshotNo(2)
                .contentHash("abc123def")
                .snapshotReason("Manual")
                .createdAt(Instant.parse("2026-08-31T12:00:00Z"))
                .hasRawJson(true)
                .options(List.of(option))
                .build();
    }

    /**
     * 计划 Todo 6 契约：list 语义签名必须是 list(Long, QuestionQuery)，
     * 不得把 status/gamedayId/includeOptions/snapshotId 拆成独立方法参数。
     * GET 查询条件通过 {@link ModelAttribute} 绑定到单个 Query DTO。
     */
    @Test
    @DisplayName("契约：list 签名为 list(Long, QuestionQuery)，查询条件走 ModelAttribute")
    void list_methodSignature_isRoundIdAndQuestionQuery() throws NoSuchMethodException {
        Method list = QuestionController.class.getMethod("list", Long.class, QuestionQuery.class);
        Parameter[] parameters = list.getParameters();
        assertEquals(2, parameters.length, "list 只能有 roundId + QuestionQuery 两个参数");
        assertEquals(Long.class, parameters[0].getType(), "第一个参数必须是 Long roundId");
        assertEquals(QuestionQuery.class, parameters[1].getType(), "第二个参数必须是 QuestionQuery");
        assertTrue(
                parameters[1].isAnnotationPresent(ModelAttribute.class),
                "QuestionQuery 必须使用 @ModelAttribute 绑定 GET 查询参数，不能用 @RequestBody");

        Method[] declared = QuestionController.class.getDeclaredMethods();
        int listOverloads = 0;
        for (Method method : declared) {
            if ("list".equals(method.getName()) && method.getParameterCount() > 0) {
                listOverloads++;
                assertEquals(2, method.getParameterCount(), "禁止拆散的 list 重载");
            }
        }
        assertEquals(1, listOverloads, "只允许一个 list(Long, QuestionQuery) 方法");
    }

    // -------------------- 路由 1：GET /api/v1/rounds/{roundId}/questions --------------------

    /**
     * 未显式传 includeOptions 时应绑定默认值 true（QuestionQuery.includeOptions == Boolean.TRUE），
     * 路径变量 roundId 正确透传，返回未包装的题目列表 JSON。
     */
    @Test
    @DisplayName("列表：未传 includeOptions 时默认绑定 true，roundId 正确透传")
    void list_whenIncludeOptionsAbsent_bindsDefaultTrue() throws Exception {
        Long roundId = 42L;
        when(questionService.listByRoundId(eq(roundId), any(QuestionQuery.class)))
                .thenReturn(singleQuestionList());

        mockMvc.perform(get("/api/v1/rounds/{roundId}/questions", roundId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // 未包装的直接列表 JSON 断言
                .andExpect(jsonPath("$[0].question_id").value(7))
                .andExpect(jsonPath("$[0].question_type").value("UNKNOWN"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].latest_snapshot_id").value(9))
                .andExpect(jsonPath("$[0].options[0].option_text").value("Lewis Hamilton 获胜"))
                .andExpect(jsonPath("$[0].options[0].chance").value(0.35));

        // 捕获实际传给服务的查询对象，验证默认值绑定
        ArgumentCaptor<QuestionQuery> captor = ArgumentCaptor.forClass(QuestionQuery.class);
        verify(questionService).listByRoundId(eq(roundId), captor.capture());
        QuestionQuery query = captor.getValue();
        assertNotNull(query, "查询对象不应为 null");
        assertEquals(Boolean.TRUE, query.getIncludeOptions(), "includeOptions 未传时应默认 true");
        assertEquals(null, query.getStatus(), "未传 status 应为 null");
        assertEquals(null, query.getGamedayId(), "未传 gamedayId 应为 null");
        assertEquals(null, query.getSnapshotId(), "未传 snapshotId 应为 null");
    }

    /**
     * 显式传 includeOptions=false 时保持 false（不得被默认值覆盖），
     * 且 status / gamedayId / snapshotId 全部正确绑定进 QuestionQuery。
     */
    @Test
    @DisplayName("列表：显式 includeOptions=false 保持 false，过滤条件全部绑定")
    void list_whenExplicitFalseAndFilters_bindsAllQueryParams() throws Exception {
        Long roundId = 1L;
        when(questionService.listByRoundId(eq(roundId), any(QuestionQuery.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rounds/{roundId}/questions", roundId)
                        .param("status", "OPEN")
                        .param("gamedayId", "3")
                        .param("includeOptions", "false")
                        .param("snapshotId", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        ArgumentCaptor<QuestionQuery> captor = ArgumentCaptor.forClass(QuestionQuery.class);
        verify(questionService).listByRoundId(eq(roundId), captor.capture());
        QuestionQuery query = captor.getValue();
        assertEquals("OPEN", query.getStatus(), "status 应绑定 OPEN");
        assertEquals(Integer.valueOf(3), query.getGamedayId(), "gamedayId 应绑定 3");
        assertEquals(Boolean.FALSE, query.getIncludeOptions(), "显式传 false 应保持 false，不被默认值覆盖");
        assertEquals(Long.valueOf(9), query.getSnapshotId(), "snapshotId 应绑定 9");
    }

    // -------------------- 路由 2：GET /api/v1/questions/{questionId} --------------------

    /**
     * 详情路由返回未包装的题目详情 JSON：questionType 固定 UNKNOWN、含选项列表。
     */
    @Test
    @DisplayName("详情：返回题目详情 JSON 且 questionType 为 UNKNOWN")
    void getDetail_whenFound_returnsDetailJson() throws Exception {
        when(questionService.getDetail(7L)).thenReturn(detailDto());

        mockMvc.perform(get("/api/v1/questions/{questionId}", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question_id").value(7))
                .andExpect(jsonPath("$.question_type").value("UNKNOWN"))
                .andExpect(jsonPath("$.question_text").value("谁将赢得冲刺赛？"))
                .andExpect(jsonPath("$.options[0].option_id").value(1))
                .andExpect(jsonPath("$.options[0].chance").value(0.35));

        verify(questionService).getDetail(7L);
    }

    // -------------------- 路由 3：GET /api/v1/questions/{questionId}/snapshots/{snapshotId} --------------------

    /**
     * 指定快照路由返回快照 JSON：hasRawJson=true，且绝不出现 rawJson 本体。
     */
    @Test
    @DisplayName("快照：返回快照 JSON，hasRawJson=true 且不泄露 rawJson")
    void getSnapshot_whenFound_returnsSnapshotJsonWithoutRawJson() throws Exception {
        when(questionService.getSnapshot(7L, 9L)).thenReturn(snapshotDto());

        mockMvc.perform(get("/api/v1/questions/{questionId}/snapshots/{snapshotId}", 7L, 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot_id").value(9))
                .andExpect(jsonPath("$.question_id").value(7))
                .andExpect(jsonPath("$.snapshot_no").value(2))
                .andExpect(jsonPath("$.has_raw_json").value(true))
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.raw_json").doesNotExist())
                .andExpect(jsonPath("$.options[0].option_text").value("Charles Leclerc 获胜"));

        verify(questionService).getSnapshot(7L, 9L);
    }

    // -------------------- 路由 4：GET /api/v1/questions/{questionId}/snapshots --------------------

    /**
     * 快照历史路由返回未包装的快照列表 JSON，同样不泄露 rawJson 本体。
     */
    @Test
    @DisplayName("快照历史：返回快照列表 JSON 且不泄露 rawJson")
    void listSnapshots_whenFound_returnsSnapshotListJson() throws Exception {
        when(questionService.listSnapshots(7L)).thenReturn(List.of(snapshotDto()));

        mockMvc.perform(get("/api/v1/questions/{questionId}/snapshots", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].snapshot_id").value(9))
                .andExpect(jsonPath("$[0].has_raw_json").value(true))
                .andExpect(jsonPath("$[0].raw_json").doesNotExist());

        verify(questionService).listSnapshots(7L);
    }

    // -------------------- 404 契约：真实 GlobalExceptionHandler --------------------

    /**
     * 详情目标不存在时，服务抛出的 ResourceNotFoundException 被真实 GlobalExceptionHandler
     * 映射为 HTTP 404 + 稳定错误体 {"code":"RESOURCE_NOT_FOUND","message":"..."}。
     */
    @Test
    @DisplayName("404：详情不存在时返回统一错误体")
    void getDetail_whenMissing_returnsStable404() throws Exception {
        when(questionService.getDetail(999L))
                .thenThrow(new ResourceNotFoundException("Question not found: 999"));

        mockMvc.perform(get("/api/v1/questions/{questionId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Question not found: 999"));

        verify(questionService).getDetail(999L);
    }

    /**
     * 快照与题目归属不匹配时同样映射为 404，错误信息保持安全可读。
     */
    @Test
    @DisplayName("404：快照归属不匹配时返回统一错误体")
    void getSnapshot_whenOwnershipMismatch_returnsStable404() throws Exception {
        when(questionService.getSnapshot(7L, 9L))
                .thenThrow(new ResourceNotFoundException("Snapshot does not belong to question: 9/7"));

        mockMvc.perform(get("/api/v1/questions/{questionId}/snapshots/{snapshotId}", 7L, 9L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Snapshot does not belong to question: 9/7"));

        verify(questionService).getSnapshot(7L, 9L);
    }

    // -------------------- 写保护：POST 一律被拒绝且不触碰服务 --------------------

    /**
     * 对全部四个 GET 路径发起 POST 均被 Spring 拒绝（405 Method Not Allowed），
     * 且 QuestionService 零交互——证明控制器未引入任何写/答题路由（计划 Must NOT have）。
     */
    @Test
    @DisplayName("写保护：四个 GET 路径的 POST 全部被拒绝且不调用服务")
    void postToAnyGetPath_isRejectedWithoutServiceInvocation() throws Exception {
        List<String> readPaths = List.of(
                "/api/v1/rounds/42/questions",
                "/api/v1/questions/7",
                "/api/v1/questions/7/snapshots",
                "/api/v1/questions/7/snapshots/9");

        for (String path : readPaths) {
            mockMvc.perform(post(path))
                    .andExpect(status().isMethodNotAllowed());
        }

        verifyNoInteractions(questionService);
    }

    /**
     * 列表路由的 200 返回体未包装（$ 直接是数组），与之前断言共同锁定响应形态。
     */
    @Test
    @DisplayName("列表：返回体为直接数组（无 success 包装）")
    void list_returnsUnwrappedArray() throws Exception {
        when(questionService.listByRoundId(eq(1L), any(QuestionQuery.class)))
                .thenReturn(singleQuestionList());

        mockMvc.perform(get("/api/v1/rounds/{roundId}/questions", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(jsonPath("$[0].question_id").value(7));

        // 追加断言：includeOptions 默认 true 且 roundId 透传（一并验证服务委托）
        ArgumentCaptor<QuestionQuery> qCaptor = ArgumentCaptor.forClass(QuestionQuery.class);
        verify(questionService).listByRoundId(eq(1L), qCaptor.capture());
        assertEquals(Boolean.TRUE, qCaptor.getValue().getIncludeOptions());
        assertTrue(qCaptor.getValue().getIncludeOptions(), "默认应附带选项");
    }
}