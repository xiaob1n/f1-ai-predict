package com.lbz.f1aipredict.question.controller;

import com.lbz.f1aipredict.question.dto.QuestionDetailDto;
import com.lbz.f1aipredict.question.dto.QuestionDto;
import com.lbz.f1aipredict.question.dto.QuestionQuery;
import com.lbz.f1aipredict.question.dto.QuestionSnapshotDto;
import com.lbz.f1aipredict.question.service.QuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题目只读查询接口控制器。
 * <p>
 * 仅暴露四个只读 GET 路由（见计划 Todo 6）：按分站列出题目、读取单个题目详情、
 * 读取指定快照、读取快照历史。不做任何写入、答题、官方答案或管理端点，
 * 全部委托给 {@link QuestionService}，返回 DTO 而非实体，无额外成功包装。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class QuestionController {

    /** 题目只读查询服务，构造器注入 */
    private final QuestionService questionService;

    /**
     * 按分站列出题目（GET /api/v1/rounds/{roundId}/questions）。
     * <p>
     * status / gamedayId / includeOptions / snapshotId 通过 {@link ModelAttribute}
     * 绑定到单个 {@link QuestionQuery}；空查询构造时 includeOptions 默认为 true，
     * 显式传 false 时保持 false。
     *
     * @param roundId 分站 ID（必填路径变量）
     * @param query   GET 查询条件 DTO（四个字段一并绑定）
     * @return 题目列表 DTO，无包装
     */
    @GetMapping("/rounds/{roundId}/questions")
    public List<QuestionDto> list(
            @PathVariable Long roundId,
            @ModelAttribute QuestionQuery query) {
        return questionService.listByRoundId(roundId, query);
    }

    /**
     * 读取单个题目详情（GET /api/v1/questions/{questionId}）。
     *
     * @param questionId 题目 ID（必填）
     * @return 题目详情 DTO（含最新快照下的选项列表），无包装
     */
    @GetMapping("/questions/{questionId}")
    public QuestionDetailDto getDetail(@PathVariable Long questionId) {
        return questionService.getDetail(questionId);
    }

    /**
     * 读取题目在指定快照下的内容（GET /api/v1/questions/{questionId}/snapshots/{snapshotId}）。
     *
     * @param questionId 题目 ID（必填）
     * @param snapshotId 快照 ID（必填）
     * @return 快照 DTO（含该快照下选项及 hasRawJson 标记，不回传原始 JSON），无包装
     */
    @GetMapping("/questions/{questionId}/snapshots/{snapshotId}")
    public QuestionSnapshotDto getSnapshot(@PathVariable Long questionId, @PathVariable Long snapshotId) {
        return questionService.getSnapshot(questionId, snapshotId);
    }

    /**
     * 读取题目的快照历史（GET /api/v1/questions/{questionId}/snapshots）。
     *
     * @param questionId 题目 ID（必填）
     * @return 快照历史列表 DTO，无包装
     */
    @GetMapping("/questions/{questionId}/snapshots")
    public List<QuestionSnapshotDto> listSnapshots(@PathVariable Long questionId) {
        return questionService.listSnapshots(questionId);
    }
}