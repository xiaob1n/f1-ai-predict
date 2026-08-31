package com.lbz.f1aipredict.question.service;

import com.lbz.f1aipredict.question.dto.QuestionDetailDto;
import com.lbz.f1aipredict.question.dto.QuestionDto;
import com.lbz.f1aipredict.question.dto.QuestionQuery;
import com.lbz.f1aipredict.question.dto.QuestionSnapshotDto;

import java.util.List;

/**
 * 题目只读查询服务（接口契约）。
 * <p>
 * 对外暴露四个只读接口：按分站列出题目、读取单个题目详情、
 * 读取指定快照、读取快照历史。所有接口均只读、不涉及写入，
 * 返回 DTO 而非实体，绝不回传原始 Feed JSON。
 * <p>
 * 本接口契约与 {@code QuestionServiceImpl} 一一对应，调用方只依赖
 * 本接口而不感知实现细节（批量快照/选项加载、快照归属校验等均封装在实现内）。
 */
public interface QuestionService {

    /**
     * 按分站列出题目。
     * <p>
     * 默认附加各题目最新快照快照下的选项；当查询参数指定 {@link QuestionQuery#getSnapshotId()} 时，
     * 仅返回该快照所属且属于目标分站（roundId）与过滤条件的题目，跨分站/缺失/过滤不匹配返回空列表。
     *
     * @param roundId 分站 ID（必填，未知分站返回空列表）
     * @param query   查询条件（status / gamedayId / includeOptions / snapshotId，可空）
     * @return 题目列表，按 question_no 升序、id 升序稳定排列
     */
    List<QuestionDto> listByRoundId(Long roundId, QuestionQuery query);

    /**
     * 读取单个题目详情。
     * <p>
     * 返回题目字段及其最新快照的选项列表；题目不存在时抛出
     * {@code ResourceNotFoundException}（HTTP 404）。
     *
     * @param questionId 题目 ID（必填）
     * @return 题目详情 DTO
     */
    QuestionDetailDto getDetail(Long questionId);

    /**
     * 读取题目在指定快照下的内容。
     * <p>
     * 校验快照确实属于该题目（snapshot.questionId == questionId），
     * 不匹配或快照不存在时抛出 {@code ResourceNotFoundException}（HTTP 404）。
     *
     * @param questionId 题目 ID（必填）
     * @param snapshotId 快照 ID（必填）
     * @return 快照 DTO，含该快照下选项及 hasRawJson 标记，不回传原始 JSON
     */
    QuestionSnapshotDto getSnapshot(Long questionId, Long snapshotId);

    /**
     * 读取题目的快照历史。
     * <p>
     * 返回该题全部历史快照（含各快照下的选项与 hasRawJson 标记），
     * 按 snapshot_no 升序、id 升序稳定排列；题目不存在时抛出
     * {@code ResourceNotFoundException}（HTTP 404）。
     *
     * @param questionId 题目 ID（必填）
     * @return 快照历史列表 DTO
     */
    List<QuestionSnapshotDto> listSnapshots(Long questionId);
}