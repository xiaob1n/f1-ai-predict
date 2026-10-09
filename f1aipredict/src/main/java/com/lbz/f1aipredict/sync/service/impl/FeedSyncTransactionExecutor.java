package com.lbz.f1aipredict.sync.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/** Feed HTTP 完成后，仅将业务表写入与审计放入独立短事务。 */
@Component
public class FeedSyncTransactionExecutor {

    /** 在调用方传入的同步写入逻辑外开启事务。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public <T> T execute(Supplier<T> work) {
        return work.get();
    }

    /** 赛程同步使用可保护空比赛日索引范围的隔离级别。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public <T> T executeSchedule(Supplier<T> work) {
        return work.get();
    }
}
