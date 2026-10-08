package com.lbz.f1aipredict.prediction.controller;

import com.lbz.f1aipredict.prediction.dto.PredictionBatchLockDto;
import com.lbz.f1aipredict.prediction.service.PredictionLockService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 预测管理端操作入口。 */
@RestController
@RequestMapping("/api/v1/admin/prediction/batches")
public class PredictionAdminController {

    private final PredictionLockService predictionLockService;

    /** 构造预测管理端控制器。 */
    public PredictionAdminController(PredictionLockService predictionLockService) {
        this.predictionLockService = predictionLockService;
    }

    /** 手动锁定指定预测批次，重复调用返回首次锁定结果。 */
    @PostMapping("/{id}/lock")
    public PredictionBatchLockDto lockBatch(@PathVariable Long id) {
        // 锁定时间按数据库毫秒精度截断，保证可持久化且保持可信服务器时间。
        return PredictionBatchLockDto.from(
                predictionLockService.lockBatch(id, Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    }
}
