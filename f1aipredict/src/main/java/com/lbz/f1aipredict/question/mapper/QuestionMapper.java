package com.lbz.f1aipredict.question.mapper;

import com.lbz.f1aipredict.question.entity.Question;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.jdbc.SQL;

import java.util.List;

/**
 * 题目 Mapper - 持久化契约
 * 实现题目按分站、状态、游戏日过滤查询
 */
@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    /**
     * 按分站查询题目，支持可选的状态、游戏日过滤
     *
     * @param roundId   分站 ID（必填）
     * @param status    题目状态（可选，为 null 时不参与过滤）
     * @param gamedayId 游戏日 ID（可选，为 null 时不参与过滤）
     * @return 题目列表，按 question_no 升序、id 升序排列
     */
    @SelectProvider(type = SQLProvider.class, method = "selectByRound")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "roundId", column = "round_id"),
        @Result(property = "gamedayId", column = "gameday_id"),
        @Result(property = "sourceQuestionId", column = "source_question_id"),
        @Result(property = "questionNo", column = "question_no"),
        @Result(property = "questionText", column = "question_text"),
        @Result(property = "subText", column = "sub_text"),
        @Result(property = "optionTemplateId", column = "option_template_id"),
        @Result(property = "choiceLimit", column = "choice_limit"),
        @Result(property = "status", column = "status"),
        @Result(property = "contentHash", column = "content_hash"),
        @Result(property = "latestSnapshotId", column = "latest_snapshot_id"),
        @Result(property = "firstSeenAt", column = "first_seen_at"),
        @Result(property = "lastSyncedAt", column = "last_synced_at"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<Question> selectByRound(@Param("roundId") Long roundId,
                                 @Param("status") String status,
                                 @Param("gamedayId") Integer gamedayId);

    /**
     * 按主键查询单个题目
     *
     * @param id 题目主键
     * @return 匹配的题目，无则返回 null
     */
    @Select("SELECT * FROM question WHERE id = #{id}")
    Question selectById(@Param("id") Long id);

    /**
     * 按 gameday_id 与来源题目 ID 查询唯一题目（对应唯一键 uk_question_source）。
     *
     * @param gamedayId        比赛日 ID，不得为 null
     * @param sourceQuestionId 来源题目 ID，不得为 null
     * @return 匹配的题目，无则返回 null
     */
    @Select("SELECT * FROM question WHERE gameday_id = #{gamedayId} AND source_question_id = #{sourceQuestionId} LIMIT 1")
    Question selectByGamedayIdAndSourceQuestionId(@Param("gamedayId") Integer gamedayId,
                                                  @Param("sourceQuestionId") Integer sourceQuestionId);

    /**
     * 内部 SQL 提供器：将 selectByRound 的动态 SQL 生成逻辑内聚于此，
     * 避免依赖外部 Provider 类，保证 Mapper 文件自包含。
     */
    class SQLProvider {

        /**
         * 生成按分站查询题目的动态 SQL
         * roundId 为必填条件；status、gamedayId 为可选过滤条件（为 null 时忽略）；
         * 结果按 question_no 升序、id 升序稳定排序。
         */
        public String selectByRound(Long roundId, String status, Integer gamedayId) {
            // 使用 MyBatis 的 SQL 构建器生成参数安全绑定的动态 SQL（全部走 #{} 预编译绑定，避免注入）
            return new SQL() {{
                SELECT("*");
                FROM("question");
                WHERE("round_id = #{roundId}");
                // status、gamedayId 为可选过滤条件，为 null 时不追加 WHERE 子句
                if (status != null) {
                    WHERE("status = #{status}");
                }
                if (gamedayId != null) {
                    WHERE("gameday_id = #{gamedayId}");
                }
                // 满足计划的排序要求：question_no 升序，其次 id 升序
                ORDER_BY("question_no ASC, id ASC");
            }}.toString();
        }
    }
}