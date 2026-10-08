package com.lbz.f1aipredict.prediction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lbz.f1aipredict.common.GlobalExceptionHandler;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.prediction.dto.CreatePredictionBatchRequest;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDetailDto;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobPageDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobQuery;
import com.lbz.f1aipredict.prediction.dto.PredictionJobStatusCountsDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobOutcomeDto;
import com.lbz.f1aipredict.prediction.service.PredictionBatchService;
import com.lbz.f1aipredict.prediction.service.PredictionJobOutcomeService;
import com.lbz.f1aipredict.prediction.service.PredictionJobService;
import com.lbz.f1aipredict.sync.FeedSyncException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 预测批次四组 REST 路由的 standalone MockMvc 契约测试。 */
class PredictionControllerTest {

    private PredictionBatchService batchService;
    private PredictionJobService jobService;
    private PredictionJobOutcomeService outcomeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        batchService = mock(PredictionBatchService.class);
        jobService = mock(PredictionJobService.class);
        outcomeService = mock(PredictionJobOutcomeService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PredictionController(batchService, jobService, outcomeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST 创建批次返回 201 且不包装 success")
    void createBatch_returns201AndRawDto() throws Exception {
        when(batchService.create(any(), any())).thenReturn(PredictionBatchDto.builder()
                .batchId(11L).roundId(7L).status("PENDING").questionCount(1).jobIds(List.of("job-1")).build());

        mockMvc.perform(post("/api/v1/rounds/7/prediction-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.batch_id").value(11))
                .andExpect(jsonPath("$.round_id").value(7))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.question_count").value(1))
                .andExpect(jsonPath("$.job_ids[0]").value("job-1"))
                .andExpect(jsonPath("$.jobIds").doesNotExist())
                .andExpect(jsonPath("$.success").doesNotExist());
        verify(batchService).create(any(), any());
    }

    @Test
    @DisplayName("GET 批次详情返回 200 camelCase JSON")
    void getBatch_returns200() throws Exception {
        when(batchService.getById(11L)).thenReturn(PredictionBatchDetailDto.builder()
                .batchId(11L).roundId(7L).batchNo(1).status("PENDING").questionCount(1)
                .dataCutoff(Instant.parse("2026-08-28T02:00:00Z"))
                .createdAt(Instant.parse("2026-08-28T02:01:00Z"))
                .updatedAt(Instant.parse("2026-08-28T02:02:00Z"))
                .statusCounts(PredictionJobStatusCountsDto.builder().pendingCount(1).runningCount(0)
                        .retryingCount(0).succeededCount(0).failedCount(0).deadLetterCount(0).unknownCount(0).build())
                .build());

        mockMvc.perform(get("/api/v1/prediction-batches/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch_id").value(11))
                .andExpect(jsonPath("$.round_id").value(7))
                .andExpect(jsonPath("$.batch_no").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.question_count").value(1))
                .andExpect(jsonPath("$.data_cutoff").value("2026-08-28T02:00:00Z"))
                .andExpect(jsonPath("$.created_at").value("2026-08-28T02:01:00Z"))
                .andExpect(jsonPath("$.updated_at").value("2026-08-28T02:02:00Z"))
                .andExpect(jsonPath("$.status_counts.pending_count").value(1))
                .andExpect(jsonPath("$.status_counts.running_count").value(0))
                .andExpect(jsonPath("$.status_counts.retrying_count").value(0))
                .andExpect(jsonPath("$.status_counts.succeeded_count").value(0))
                .andExpect(jsonPath("$.status_counts.failed_count").value(0))
                .andExpect(jsonPath("$.status_counts.dead_letter_count").value(0))
                .andExpect(jsonPath("$.status_counts.unknown_count").value(0))
                .andExpect(jsonPath("$.batchId").doesNotExist());
        verify(batchService).getById(11L);
    }

    @Test
    @DisplayName("GET 批次任务列表使用 query DTO 并裁剪 size")
    void listJobs_clampsSizeBeforeService() throws Exception {
        when(jobService.listByBatchId(any(), any())).thenReturn(PredictionJobPageDto.builder()
                .items(List.of(jobDto())).page(2).size(100).total(1L).build());

        mockMvc.perform(get("/api/v1/prediction-batches/11/jobs").param("page", "2").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].prediction_job_id").value("job-1"))
                .andExpect(jsonPath("$.items[0].batch_id").value(11))
                .andExpect(jsonPath("$.items[0].question_id").value(1))
                .andExpect(jsonPath("$.items[0].question_snapshot_id").value(101))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].retry_count").value(0))
                .andExpect(jsonPath("$.items[0].max_retries").value(3))
                .andExpect(jsonPath("$.items[0].worker_node").value("worker-1"))
                .andExpect(jsonPath("$.items[0].data_cutoff").value("2026-08-28T02:00:00Z"))
                .andExpect(jsonPath("$.items[0].feature_version").value("feature-v1"))
                .andExpect(jsonPath("$.items[0].model_version").value("model-v1"))
                .andExpect(jsonPath("$.items[0].prompt_version").value("prompt-v1"))
                .andExpect(jsonPath("$.items[0].locked_at").value("2026-08-28T02:03:00Z"))
                .andExpect(jsonPath("$.items[0].completed_at").value("2026-08-28T02:04:00Z"))
                .andExpect(jsonPath("$.items[0].created_at").value("2026-08-28T02:01:00Z"))
                .andExpect(jsonPath("$.items[0].updated_at").value("2026-08-28T02:05:00Z"))
                .andExpect(jsonPath("$.items[0].predictionJobId").doesNotExist());
        ArgumentCaptor<PredictionJobQuery> captor = ArgumentCaptor.forClass(PredictionJobQuery.class);
        verify(jobService).listByBatchId(org.mockito.ArgumentMatchers.eq(11L), captor.capture());
        assertEquals(100, captor.getValue().getSize());
        assertEquals(2, captor.getValue().getPage());
    }

    @Test
    @DisplayName("GET 单任务业务键返回 200")
    void getJob_returns200() throws Exception {
        when(jobService.getByBusinessId("job-1")).thenReturn(jobDto());

        mockMvc.perform(get("/api/v1/prediction-jobs/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prediction_job_id").value("job-1"))
                .andExpect(jsonPath("$.batch_id").value(11))
                .andExpect(jsonPath("$.question_id").value(1))
                .andExpect(jsonPath("$.question_snapshot_id").value(101))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retry_count").value(0))
                .andExpect(jsonPath("$.max_retries").value(3))
                .andExpect(jsonPath("$.worker_node").value("worker-1"))
                .andExpect(jsonPath("$.data_cutoff").value("2026-08-28T02:00:00Z"))
                .andExpect(jsonPath("$.feature_version").value("feature-v1"))
                .andExpect(jsonPath("$.model_version").value("model-v1"))
                .andExpect(jsonPath("$.prompt_version").value("prompt-v1"))
                .andExpect(jsonPath("$.locked_at").value("2026-08-28T02:03:00Z"))
                .andExpect(jsonPath("$.completed_at").value("2026-08-28T02:04:00Z"))
                .andExpect(jsonPath("$.created_at").value("2026-08-28T02:01:00Z"))
                .andExpect(jsonPath("$.updated_at").value("2026-08-28T02:05:00Z"))
                .andExpect(jsonPath("$.predictionJobId").doesNotExist());
        verify(jobService).getByBusinessId("job-1");
    }

    @Test
    @DisplayName("GET 任务 outcome 返回当前状态与 camelCase 详情")
    void getOutcome_returnsCurrentStateAndDetails() throws Exception {
        when(outcomeService.getByBusinessId("job-1")).thenReturn(PredictionJobOutcomeDto.builder()
                .predictionJobId("job-1").status("PENDING").build());

        mockMvc.perform(get("/api/v1/prediction-jobs/job-1/outcome"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prediction_job_id").value("job-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.failure").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.predictionJobId").doesNotExist());
        verify(outcomeService).getByBusinessId("job-1");
    }

    @Test
    @DisplayName("GET 不存在任务 outcome 使用统一 404")
    void getOutcome_missingJob_returns404() throws Exception {
        when(outcomeService.getByBusinessId("missing"))
                .thenThrow(new ResourceNotFoundException("Prediction job not found: missing"));

        mockMvc.perform(get("/api/v1/prediction-jobs/missing/outcome"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Bean Validation、JSON 时间格式和绑定错误统一返回 400 INVALID_REQUEST")
    void malformedRequests_returnSafe400() throws Exception {
        mockMvc.perform(post("/api/v1/rounds/7/prediction-batches")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid request"));
        mockMvc.perform(post("/api/v1/rounds/7/prediction-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson().replace("2026-08-28T10:00:00+08:00", "2026-08-28T10:00:00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/prediction-batches/not-a-number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/prediction-batches/11/jobs").param("page", "not-a-number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid request"));
        mockMvc.perform(get("/api/v1/prediction-batches/11/jobs").param("size", "not-a-number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid request"));
        verifyNoInteractions(batchService, jobService);
    }

    @Test
    @DisplayName("GET jobs 默认 query 为 page=0、size=20")
    void listJobs_defaultsPagingBeforeService() throws Exception {
        when(jobService.listByBatchId(any(), any())).thenReturn(PredictionJobPageDto.builder()
                .items(List.of()).page(0).size(20).total(0L).build());
        mockMvc.perform(get("/api/v1/prediction-batches/11/jobs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
        ArgumentCaptor<PredictionJobQuery> captor = ArgumentCaptor.forClass(PredictionJobQuery.class);
        verify(jobService).listByBatchId(org.mockito.ArgumentMatchers.eq(11L), captor.capture());
        assertEquals(0, captor.getValue().getPage());
        assertEquals(20, captor.getValue().getSize());
    }

    @Test
    @DisplayName("GET jobs 的非法 size 裁剪为默认 20")
    void listJobs_invalidSize_clampsToDefault() throws Exception {
        when(jobService.listByBatchId(any(), any())).thenReturn(PredictionJobPageDto.builder()
                .items(List.of()).page(0).size(20).total(0L).build());
        mockMvc.perform(get("/api/v1/prediction-batches/11/jobs").param("page", "-1").param("size", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
        ArgumentCaptor<PredictionJobQuery> captor = ArgumentCaptor.forClass(PredictionJobQuery.class);
        verify(jobService).listByBatchId(org.mockito.ArgumentMatchers.eq(11L), captor.capture());
        assertEquals(20, captor.getValue().getSize());
        assertEquals(0, captor.getValue().getPage());
    }

    @Test
    @DisplayName("业务 404 仍使用统一错误体")
    void missingResource_returns404() throws Exception {
        when(batchService.getById(99L)).thenThrow(new ResourceNotFoundException("Prediction batch not found: 99"));
        mockMvc.perform(get("/api/v1/prediction-batches/99"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Prediction batch not found: 99"));
    }

    @Test
    @DisplayName("既有 FeedSyncException 仍映射为 502 且不泄漏 URL")
    void upstreamFailure_returnsSafe502() throws Exception {
        when(batchService.getById(11L))
                .thenThrow(new FeedSyncException("failed https://secret.example/feed", 503));
        mockMvc.perform(get("/api/v1/prediction-batches/11"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("FEED_SYNC_ERROR"))
                .andExpect(jsonPath("$.message").value("Feed sync failed with HTTP 503"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("https://"))));
    }

    @Test
    @DisplayName("PUT/PATCH/DELETE 不允许进入 Service")
    void unsupportedMethods_return405WithoutServiceInteraction() throws Exception {
        for (var requestBuilder : List.of(
                put("/api/v1/rounds/7/prediction-batches"),
                patch("/api/v1/rounds/7/prediction-batches"),
                delete("/api/v1/rounds/7/prediction-batches"),
                put("/api/v1/prediction-batches/11"),
                patch("/api/v1/prediction-batches/11"),
                delete("/api/v1/prediction-batches/11"),
                put("/api/v1/prediction-batches/11/jobs"),
                patch("/api/v1/prediction-batches/11/jobs"),
                delete("/api/v1/prediction-batches/11/jobs"),
                put("/api/v1/prediction-jobs/job-1"),
                patch("/api/v1/prediction-jobs/job-1"),
                delete("/api/v1/prediction-jobs/job-1"),
                put("/api/v1/prediction-jobs/job-1/outcome"),
                patch("/api/v1/prediction-jobs/job-1/outcome"),
                delete("/api/v1/prediction-jobs/job-1/outcome"))) {
            mockMvc.perform(requestBuilder).andExpect(status().isMethodNotAllowed());
        }
        verifyNoInteractions(batchService, jobService);
    }

    private static String validCreateJson() {
        return "{\"question_ids\":[1],\"data_cutoff\":\"2026-08-28T10:00:00+08:00\","
                + "\"feature_version\":\"feature-v1\",\"model_version\":\"model-v1\","
                + "\"prompt_version\":\"prompt-v1\"}";
    }

    private static PredictionJobDto jobDto() {
        return PredictionJobDto.builder()
                .predictionJobId("job-1").batchId(11L).questionId(1L).questionSnapshotId(101L)
                .status("PENDING").retryCount(0).maxRetries(3).workerNode("worker-1")
                .dataCutoff(Instant.parse("2026-08-28T02:00:00Z")).featureVersion("feature-v1")
                .modelVersion("model-v1").promptVersion("prompt-v1")
                .lockedAt(Instant.parse("2026-08-28T02:03:00Z"))
                .completedAt(Instant.parse("2026-08-28T02:04:00Z"))
                .createdAt(Instant.parse("2026-08-28T02:01:00Z"))
                .updatedAt(Instant.parse("2026-08-28T02:05:00Z")).build();
    }
}
