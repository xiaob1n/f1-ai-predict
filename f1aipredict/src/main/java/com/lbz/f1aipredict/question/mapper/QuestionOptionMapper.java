package com.lbz.f1aipredict.question.mapper;

import com.lbz.f1aipredict.question.entity.QuestionOption;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 题目选项 Mapper - 持久化契约
 * 提供按快照主键集合批量查询选项的只读能力（选项数据随快照生成，本版本无写操作）
 */
@Mapper
public interface QuestionOptionMapper extends BaseMapper<QuestionOption> {

    /**
     * 按快照主键集合批量查询选项
     * <p>入参为空集合或 null 时使用 WHERE 1 = 0 短路，安全返回空列表，
     * 不会生成 IN () 这类非法 SQL。</p>
     * <p>结果按 snapshot_id 升序、option_no 升序稳定排列，保证同一快照内的选项顺序可预期。</p>
     *
     * @param snapshotIds 快照主键集合
     * @return 命中的选项列表，按 snapshot_id 升序、option_no 升序排列
     */
    @Select({
        "<script>",
        "SELECT * FROM question_option",
        "<where>",
        "  <if test='snapshotIds != null and snapshotIds.size() > 0'>",
        "    snapshot_id IN",
        "    <foreach collection='snapshotIds' item='sid' open='(' separator=',' close=')'>#{sid}</foreach>",
        "  </if>",
        "  <if test='snapshotIds == null or snapshotIds.size() == 0'>",
        "    1 = 0",
        "  </if>",
        "</where>",
        "ORDER BY snapshot_id ASC, option_no ASC",
        "</script>"
    })
    List<QuestionOption> selectBySnapshotIds(@Param("snapshotIds") Collection<Long> snapshotIds);
}