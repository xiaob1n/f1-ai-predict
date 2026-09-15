package com.lbz.f1aipredict.prediction.controller;

import com.lbz.f1aipredict.prediction.dto.CreatePredictionBatchRequest;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDetailDto;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobPageDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobQuery;
import com.lbz.f1aipredict.prediction.service.PredictionBatchService;
import com.lbz.f1aipredict.prediction.service.PredictionJobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 预测批次与任务的 REST 入口，只编排请求绑定并委托给服务接口。 */
@RestController
@RequestMapping("/api/v1")
public class PredictionController {

    private final PredictionBatchService batchService;
    private final PredictionJobService jobService;

    /** 构造预测接口控制器，依赖保持在服务抽象层。 */
    public PredictionController(PredictionBatchService batchService, PredictionJobService jobService) {
        this.batchService = batchService;
        this.jobService = jobService;
    }

    /** 创建批次；201 只表示 PENDING 批次已落库，不代表任务已投递。 */
    @PostMapping("/rounds/{roundId}/prediction-batches")
    @ResponseStatus(HttpStatus.CREATED)
    public PredictionBatchDto createBatch(@PathVariable Long roundId,
                                           @Valid @RequestBody CreatePredictionBatchRequest request) {
        return batchService.create(roundId, request);
    }

    /** 查询批次详情。 */
    @GetMapping("/prediction-batches/{batchId}")
    public PredictionBatchDetailDto getBatch(@PathVariable Long batchId) {
        return batchService.getById(batchId);
    }

    /** 查询批次任务，先在控制器边界裁剪分页参数再进入服务。 */
    @GetMapping("/prediction-batches/{batchId}/jobs")
    public PredictionJobPageDto listJobs(@PathVariable Long batchId,
                                         @ModelAttribute PredictionJobQuery query) {
        query.clampPaging();
        return jobService.listByBatchId(batchId, query);
    }

    /** 按业务键查询单个任务。 */
    @GetMapping("/prediction-jobs/{predictionJobId}")
    public PredictionJobDto getJob(@PathVariable String predictionJobId) {
        return jobService.getByBusinessId(predictionJobId);
    }
}
