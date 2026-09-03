package com.lbz.f1aipredict.sync.store;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lbz.f1aipredict.sync.dto.SyncRecordQuery;
import com.lbz.f1aipredict.sync.entity.FeedRawPayload;
import com.lbz.f1aipredict.sync.entity.SyncRecord;
import com.lbz.f1aipredict.sync.mapper.FeedRawPayloadMapper;
import com.lbz.f1aipredict.sync.mapper.SyncRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.Objects;

/**
 * sync_record / feed_raw_payload 的唯一持久化入口。
 * 后续 FeedSyncService 只调用本 Store，禁止直接使用 Mapper。
 */
@Slf4j
@Component
public class SyncPersistenceStore {

    private final SyncRecordMapper syncRecordMapper;
    private final FeedRawPayloadMapper feedRawPayloadMapper;

    /**
     * 构造器注入两个 Mapper，与 QuestionServiceImpl 风格一致。
     */
    public SyncPersistenceStore(SyncRecordMapper syncRecordMapper,
                                FeedRawPayloadMapper feedRawPayloadMapper) {
        this.syncRecordMapper = Objects.requireNonNull(syncRecordMapper, "syncRecordMapper must not be null");
        this.feedRawPayloadMapper = Objects.requireNonNull(feedRawPayloadMapper, "feedRawPayloadMapper must not be null");
    }

    /**
     * 按 contentHash 幂等插入原始响应留档。
     * SHA-256 由调用方计算并传入，本方法不负责哈希。
     * 空/空白 contentHash 拒绝写入（uk_payload_hash 要求 NOT NULL）。
     * 命中 uk_payload_hash 时回读已有行并返回同一主键。
     *
     * @param payload 待写入的留档（须含 sourceType、sourceUrl、contentHash、rawJson）
     * @return 新插入或已存在记录的主键
     */
    public Long saveRawPayload(FeedRawPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        String contentHash = payload.getContentHash();
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be blank");
        }

        Instant now = Instant.now();
        if (payload.getFetchedAt() == null) {
            payload.setFetchedAt(now);
        }
        if (payload.getCreatedAt() == null) {
            payload.setCreatedAt(now);
        }

        try {
            feedRawPayloadMapper.insert(payload);
            log.debug("写入 Feed 留档: sourceType={}, gamedayId={}, payloadId={}",
                    payload.getSourceType(), payload.getGamedayId(), payload.getId());
            return payload.getId();
        } catch (DuplicateKeyException ex) {
            // 唯一键冲突：返回已有记录 ID，不吞掉异常却不回读。
            return resolveExistingPayloadId(payload.getSourceType(), payload.getGamedayId(), contentHash, ex);
        } catch (DataIntegrityViolationException ex) {
            // Spring 可能只抛包装后的 DataIntegrityViolationException，沿 cause 链识别重复键。
            if (isDuplicateKey(ex)) {
                return resolveExistingPayloadId(payload.getSourceType(), payload.getGamedayId(), contentHash, ex);
            }
            throw ex;
        }
    }

    /**
     * 查找指定数据源下内容未变化（SUCCESS / SKIPPED_UNCHANGED）的最近一条同步记录。
     * 业务 Service 禁止直接访问 SyncRecordMapper，判重必须走本方法。
     *
     * @param sourceType  数据源类型，如 SCHEDULE
     * @param contentHash 响应 SHA-256
     * @return 最近一条未变化记录，无则返回 null
     */
    public SyncRecord findLatestUnchanged(String sourceType, String contentHash) {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        if (contentHash == null || contentHash.isBlank()) {
            return null;
        }
        SyncRecord latest = syncRecordMapper.selectLatestUnchanged(sourceType, contentHash);
        log.debug("查询未变化同步记录: sourceType={}, found={}", sourceType, latest != null);
        return latest;
    }

    /**
     * 始终新插入一条同步审计记录（不按 hash 去重）。
     * SUCCESS / SKIPPED_UNCHANGED / FAILED 均可写入；FAILED 允许 contentHash 为 null。
     *
     * @param record 待写入的同步记录
     * @return 生成的主键
     */
    public Long saveSyncRecord(SyncRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        Instant now = Instant.now();
        if (record.getSyncedAt() == null) {
            record.setSyncedAt(now);
        }
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(now);
        }
        syncRecordMapper.insert(record);
        log.debug("写入同步记录: sourceType={}, status={}, gamedayId={}, recordId={}, durationMs={}",
                record.getSourceType(), record.getStatus(), record.getGamedayId(),
                record.getId(), record.getDurationMs());
        return record.getId();
    }

    /**
     * 按条件分页查询同步记录。
     * <p>
     * 再次裁剪 page/size（与 Controller 双保险）：size 空或 &lt;1 → 20，size&gt;100 → 100，
     * page 空或 &lt;0 → 0。MyBatis-Plus {@link Page} 的 current 从 1 开始，
     * 因此把 0-based page 转成 current = page + 1，避免 page=0 算出负 offset。
     *
     * @param query 过滤与分页条件
     * @return MyBatis-Plus 分页结果（records 为实体，由 Service 映射为 DTO）
     */
    public Page<SyncRecord> pageRecords(SyncRecordQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        query.clampPaging();
        // MP Page.current 从 1 起；API 约定 0-based，这里 +1
        Page<SyncRecord> page = new Page<>((long) query.getPage() + 1, query.getSize());
        QueryWrapper<SyncRecord> wrapper = new QueryWrapper<>();
        if (query.getSourceType() != null && !query.getSourceType().isBlank()) {
            wrapper.eq("source_type", query.getSourceType());
        }
        if (query.getGamedayId() != null) {
            wrapper.eq("gameday_id", query.getGamedayId());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq("status", query.getStatus());
        }
        wrapper.orderByDesc("id");
        Page<SyncRecord> result = syncRecordMapper.selectPage(page, wrapper);
        log.debug("分页查询同步记录: sourceType={}, gamedayId={}, status={}, page={}, size={}, total={}, rows={}",
                query.getSourceType(), query.getGamedayId(), query.getStatus(),
                query.getPage(), query.getSize(), result.getTotal(),
                result.getRecords() == null ? 0 : result.getRecords().size());
        return result;
    }

    /**
     * 按主键读取原始 Feed 留档；不存在时返回 null，由 Service 转 404。
     *
     * @param payloadId 留档主键
     * @return 实体或 null
     */
    public FeedRawPayload findRawPayloadById(Long payloadId) {
        Objects.requireNonNull(payloadId, "payloadId must not be null");
        FeedRawPayload payload = feedRawPayloadMapper.selectById(payloadId);
        log.debug("按主键读取 Feed 留档: payloadId={}, found={}", payloadId, payload != null);
        return payload;
    }

    /**
     * 唯一键冲突后按 contentHash 回读已有主键。
     */
    private Long resolveExistingPayloadId(String sourceType, Integer gamedayId, String contentHash,
                                          DataIntegrityViolationException ex) {
        FeedRawPayload existing = feedRawPayloadMapper.selectByContentHash(contentHash);
        if (existing == null || existing.getId() == null) {
            log.error("Feed 留档唯一键冲突后回读失败: sourceType={}, gamedayId={}", sourceType, gamedayId, ex);
            throw new IllegalStateException(
                    "Duplicate content_hash but existing feed_raw_payload not found", ex);
        }
        log.info("Feed 留档已存在，复用已有主键: sourceType={}, gamedayId={}, payloadId={}",
                sourceType, gamedayId, existing.getId());
        return existing.getId();
    }

    /**
     * 沿 cause 链判断是否为唯一键冲突（DuplicateKeyException 或其 JDBC 根因）。
     */
    private static boolean isDuplicateKey(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DuplicateKeyException
                    || current instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
