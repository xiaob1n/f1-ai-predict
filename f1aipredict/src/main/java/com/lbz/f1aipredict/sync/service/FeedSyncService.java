package com.lbz.f1aipredict.sync.service;

import com.lbz.f1aipredict.sync.dto.RawPayloadDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordPageDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordQuery;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;

/**
 * Feed 同步服务。当前实现赛程、limits、题目同步以及管理端查询。
 */
public interface FeedSyncService {

    /**
     * 拉取 raceday 赛程 Feed，按内容哈希幂等写入 season / round / meeting_session。
     *
     * @return 本次同步结果（SUCCESS / SKIPPED_UNCHANGED / FAILED）
     */
    SyncResultDto syncSchedule();

    /**
     * 拉取指定比赛日的题目 Feed，按题目内容哈希幂等写入 question / question_snapshot / question_option。
     *
     * @param gamedayId 比赛日 ID，不得为 null
     * @return 本次同步结果（SUCCESS / SKIPPED_UNCHANGED / FAILED）
     */
    SyncResultDto syncQuestions(Integer gamedayId);

    /**
     * 拉取 limits 解析当前 gamedayId，再串行同步赛程与当前比赛日题目。
     * 顺序固定为 limits → schedule → questions，禁止并行。
     *
     * @return 汇总结果（sourceType=CURRENT；任一子结果 FAILED 则整体 FAILED）
     */
    SyncResultDto syncCurrent();

    /**
     * 仅拉取 limits：留档 LIMITS payload、解析 gamedayId、写入 SUCCESS/FAILED 记录。
     * 不执行赛程或题目同步。
     *
     * @return sourceType=LIMITS 的同步结果（成功时带 gamedayId）
     */
    SyncResultDto syncLimits();

    /**
     * 按条件分页查询同步记录。page 0-based；size 非法值回落 20、超过 100 截断。
     *
     * @param query 过滤与分页条件
     * @return 分页 DTO，不含实体
     */
    SyncRecordPageDto pageRecords(SyncRecordQuery query);

    /**
     * 按主键读取原始 Feed JSON。缺失时抛 {@link com.lbz.f1aipredict.common.ResourceNotFoundException}。
     *
     * @param payloadId 留档主键
     * @return 原始响应 DTO
     */
    RawPayloadDto getRawPayload(Long payloadId);
}
