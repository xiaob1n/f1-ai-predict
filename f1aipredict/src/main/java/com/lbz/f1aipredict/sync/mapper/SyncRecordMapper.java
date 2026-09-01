package com.lbz.f1aipredict.sync.mapper;

import com.lbz.f1aipredict.sync.entity.SyncRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 同步记录 Mapper。
 * 继承 BaseMapper 提供 insert / selectById 等标准 CRUD；
 * 按数据源 + 内容哈希查找最近一次成功/跳过记录，供赛程同步判重。
 */
@Mapper
public interface SyncRecordMapper extends BaseMapper<SyncRecord> {

    /**
     * 查找指定数据源下、内容哈希相同且状态为 SUCCESS 或 SKIPPED_UNCHANGED 的最近一条记录。
     *
     * @param sourceType  数据源类型，如 SCHEDULE
     * @param contentHash 响应 SHA-256
     * @return 最近一条未变化记录，无则返回 null
     */
    @Select("""
            SELECT * FROM sync_record
            WHERE source_type = #{sourceType}
              AND content_hash = #{contentHash}
              AND status IN ('SUCCESS', 'SKIPPED_UNCHANGED')
            ORDER BY id DESC
            LIMIT 1
            """)
    SyncRecord selectLatestUnchanged(@Param("sourceType") String sourceType,
                                     @Param("contentHash") String contentHash);
}
