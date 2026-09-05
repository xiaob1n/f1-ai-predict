package com.lbz.f1aipredict.season.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 赛季分页结果。
 * <p>
 * 不返回实体对象；items 默认空列表避免序列化为 null。
 * JSON 键使用 camelCase。page 为 0-based。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeasonPageDto {

    /** 当前页赛季列表 */
    @JsonProperty("items")
    @Builder.Default
    private List<SeasonDto> items = new ArrayList<>();

    /** 当前页码（0-based，已裁剪） */
    @JsonProperty("page")
    private Integer page;

    /** 当前每页条数（已裁剪，最大 100） */
    @JsonProperty("size")
    private Integer size;

    /** 符合过滤条件的总记录数 */
    @JsonProperty("total")
    private Long total;
}
