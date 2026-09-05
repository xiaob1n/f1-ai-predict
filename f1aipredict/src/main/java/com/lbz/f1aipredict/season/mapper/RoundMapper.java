package com.lbz.f1aipredict.season.mapper;

import com.lbz.f1aipredict.season.entity.Round;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分站 Mapper。
 * 继承 BaseMapper 提供 insert / updateById / selectById 等标准 CRUD；
 * 按赛季 + 分站序号查询供赛程同步按 uk_round_season_no 幂等 upsert。
 */
@Mapper
public interface RoundMapper extends BaseMapper<Round> {

    /**
     * 按赛季与分站序号查询（对应唯一键 uk_round_season_no）。
     *
     * @param seasonId    赛季主键
     * @param roundNumber 分站序号（MeetingNumber，不是 RaceId）
     * @return 命中的分站，无则返回 null
     */
    @Select("SELECT * FROM round WHERE season_id = #{seasonId} AND round_number = #{roundNumber} LIMIT 1")
    Round selectBySeasonIdAndRoundNumber(@Param("seasonId") Long seasonId,
                                         @Param("roundNumber") Integer roundNumber);

    /**
     * 按赛季列出全部分站，供只读查询接口使用。
     * 排序稳定：round_number ASC, id ASC。不复用同步幂等查找方法。
     *
     * @param seasonId 赛季主键
     * @return 该赛季下的分站列表，无数据返回空列表
     */
    @Select("SELECT * FROM round WHERE season_id = #{seasonId} ORDER BY round_number ASC, id ASC")
    List<Round> selectBySeasonId(@Param("seasonId") Long seasonId);
}
