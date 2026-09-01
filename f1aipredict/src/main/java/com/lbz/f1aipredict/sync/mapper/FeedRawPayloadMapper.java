package com.lbz.f1aipredict.sync.mapper;

import com.lbz.f1aipredict.sync.entity.FeedRawPayload;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Feed 原始响应留档 Mapper。
 * 继承 BaseMapper 提供 insert / selectById 等标准 CRUD；
 * 额外按 content_hash 查询，供唯一键冲突后回读已有主键。
 */
@Mapper
public interface FeedRawPayloadMapper extends BaseMapper<FeedRawPayload> {

    /**
     * 按内容哈希查询已留档的原始响应。
     * 对应 uk_payload_hash，重复写入时用于返回已有记录 ID。
     *
     * @param contentHash 响应内容 SHA-256
     * @return 命中的留档，无则返回 null
     */
    @Select("SELECT * FROM feed_raw_payload WHERE content_hash = #{contentHash} LIMIT 1")
    FeedRawPayload selectByContentHash(@Param("contentHash") String contentHash);
}
