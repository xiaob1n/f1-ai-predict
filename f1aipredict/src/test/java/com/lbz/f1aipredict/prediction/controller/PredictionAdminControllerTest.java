package com.lbz.f1aipredict.prediction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lbz.f1aipredict.common.GlobalExceptionHandler;
import com.lbz.f1aipredict.prediction.service.PredictionLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 预测批次管理端锁定接口的 standalone MockMvc 契约测试。 */
class PredictionAdminControllerTest {

    private PredictionLockService lockService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        lockService = mock(PredictionLockService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(new PredictionAdminController(lockService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void lockBatchReturnsExplicitCamelCaseDtoAndMillisecondTime() throws Exception {
        Instant lockedAt = Instant.parse("2026-10-08T12:00:00.123Z");
        Instant deadline = Instant.parse("2026-10-08T11:59:00Z");
        when(lockService.lockBatch(org.mockito.ArgumentMatchers.eq(42L), any(Instant.class)))
                .thenReturn(new PredictionLockService.LockResult(true, lockedAt, deadline));

        mockMvc.perform(post("/api/v1/admin/prediction/batches/42/lock"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.newly_locked").value(true))
                .andExpect(jsonPath("$.locked_at").value("2026-10-08T12:00:00.123Z"))
                .andExpect(jsonPath("$.prediction_deadline").value("2026-10-08T11:59:00Z"))
                .andExpect(jsonPath("$.newlyLocked").doesNotExist())
                .andExpect(jsonPath("$.predictionDeadline").doesNotExist());

        ArgumentCaptor<Instant> lockTime = ArgumentCaptor.forClass(Instant.class);
        verify(lockService).lockBatch(org.mockito.ArgumentMatchers.eq(42L), lockTime.capture());
        assertThat(lockTime.getValue().getNano() % 1_000_000).isZero();
    }
}
