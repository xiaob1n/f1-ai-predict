package com.lbz.f1aipredict.season.mapper;

import com.lbz.f1aipredict.season.entity.Season;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 赛季 Mapper。
 * 继承 BaseMapper 提供 insert / updateById / selectById 等标准 CRUD；
 * 按年份查询供赛程同步按 uk_season_year 幂等 upsert。
 */
@Mapper
public interface SeasonMapper extends BaseMapper<Season> {

    /**
     * 按赛季年份查询（对应唯一键 uk_season_year）。
     *
     * @param year 赛季年份，如 2026
     * @return 命中的赛季，无则返回 null
     */
    @Select("SELECT * FROM season WHERE year = #{year} LIMIT 1")
    Season selectByYear(@Param("year") Integer year);
}
