package com.lbz.f1aipredict.scoring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbz.f1aipredict.scoring.entity.OfficialAnswer;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/** 官方答案当前态与修订历史的持久化入口。 */
@Mapper
public interface OfficialAnswerMapper extends BaseMapper<OfficialAnswer> {

    @Select("SELECT * FROM official_answer WHERE question_id = #{questionId} LIMIT 1")
    OfficialAnswer selectByQuestionId(@Param("questionId") Long questionId);

    @Select("SELECT * FROM official_answer WHERE question_id = #{questionId} FOR UPDATE")
    OfficialAnswer selectByQuestionIdForUpdate(@Param("questionId") Long questionId);

    /**
     * 按题目主键集合一次读取当前态答案，供批次评分避免逐题查询。
     * 入参为空集合或 null 时以 WHERE 1 = 0 短路，不会生成 IN () 这类非法 SQL。
     */
    @Select({
        "<script>",
        "SELECT * FROM official_answer",
        "<where>",
        "  <if test='questionIds != null and questionIds.size() > 0'>",
        "    question_id IN",
        "    <foreach collection='questionIds' item='qid' open='(' separator=',' close=')'>#{qid}</foreach>",
        "  </if>",
        "  <if test='questionIds == null or questionIds.size() == 0'>",
        "    1 = 0",
        "  </if>",
        "</where>",
        "ORDER BY question_id ASC",
        "</script>"
    })
    List<OfficialAnswer> selectByQuestionIds(@Param("questionIds") Collection<Long> questionIds);

    /** 更新当前态前留存旧版本；历史表见 sql/010_official_answer_history.sql，列需与其保持一致。 */
    @Insert("""
            INSERT INTO official_answer_history
              (question_id, gameday_id, raw_json, answer_content, official_points,
               published_at, content_hash, synced_at, created_at, updated_at, archived_at, revision_reason)
            SELECT question_id, gameday_id, raw_json, answer_content, official_points,
                   published_at, content_hash, synced_at, created_at, updated_at, #{archivedAt}, #{reason}
            FROM official_answer WHERE id = #{id}
            """)
    int archiveCurrent(@Param("id") Long id, @Param("archivedAt") java.time.Instant archivedAt,
                       @Param("reason") String reason);

    @Delete("DELETE FROM official_answer WHERE id = #{id}")
    int deleteCurrent(@Param("id") Long id);

    @Update("""
            UPDATE official_answer
            SET raw_json = #{answer.rawJson}, answer_content = #{answer.answerContent},
                official_points = #{answer.officialPoints}, published_at = #{answer.publishedAt},
                content_hash = #{answer.contentHash}, synced_at = #{answer.syncedAt}, updated_at = #{answer.updatedAt}
            WHERE id = #{answer.id}
            """)
    int updateCurrent(@Param("answer") OfficialAnswer answer);
}
