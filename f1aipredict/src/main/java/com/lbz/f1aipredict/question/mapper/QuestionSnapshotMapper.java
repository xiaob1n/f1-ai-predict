package com.lbz.f1aipredict.question.mapper;

import com.lbz.f1aipredict.question.entity.QuestionSnapshot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 题目快照 Mapper - 持久化契约
 * 提供按主键、按题目、按主键集合的只读查询能力（快照本版本无写操作）
 */
@Mapper
public interface QuestionSnapshotMapper extends BaseMapper<QuestionSnapshot> {

    /**
     * 按主键查询单个题目快照
     *
     * @param id 快照主键
     * @return 匹配的快照，无则返回 null
     */
    @Select("SELECT * FROM question_snapshot WHERE id = #{id}")
    QuestionSnapshot selectById(@Param("id") Long id);

    /**
     * 按题目 ID 查询该题的全部历史快照
     * 按 snapshot_no 升序、id 升序稳定排列，保证历史快照顺序可预期
     *
     * @param questionId 题目主键
     * @return 快照列表，按 snapshot_no 升序、id 升序排列
     */
    @Select("SELECT * FROM question_snapshot WHERE question_id = #{questionId} ORDER BY snapshot_no ASC, id ASC")
    List<QuestionSnapshot> selectByQuestionId(@Param("questionId") Long questionId);

    /**
     * 查询某题当前最大 snapshot_no，用于生成新快照时递增。
     * 无快照时返回 null。
     *
     * @param questionId 题目主键
     * @return 最大 snapshot_no，无则返回 null
     */
    @Select("SELECT MAX(snapshot_no) FROM question_snapshot WHERE question_id = #{questionId}")
    Integer selectMaxSnapshotNo(@Param("questionId") Long questionId);

    /**
     * 按主键集合批量查询快照
     * <p>方法名刻意命名为 {@code selectSnapshotByIds}：父接口 BaseMapper 已声明
     * {@code selectByIds(Collection<? extends Serializable>)}，若本接口再用
     * {@code selectByIds(Collection<Long>)} 会因泛型擦除后同为 Collection 而编译冲突，
     * 故改用语义等价且不冲突的签名。</p>
     * <p>入参为空集合或 null 时使用 WHERE 1 = 0 短路，安全返回空列表，
     * 不会生成 IN () 这类非法 SQL。</p>
     *
     * @param ids 快照主键集合
     * @return 命中的快照列表，按 snapshot_no 升序、id 升序排列
     */
    @Select({
        "<script>",
        "SELECT * FROM question_snapshot",
        "<where>",
        "  <if test='ids != null and ids.size() > 0'>",
        "    id IN",
        "    <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "  </if>",
        "  <if test='ids == null or ids.size() == 0'>",
        "    1 = 0",
        "  </if>",
        "</where>",
        "ORDER BY snapshot_no ASC, id ASC",
        "</script>"
    })
    List<QuestionSnapshot> selectSnapshotByIds(@Param("ids") Collection<Long> ids);
}