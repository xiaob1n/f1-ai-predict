package com.lbz.f1aipredict.season.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 赛季分页查询参数。
 * <p>
 * 绑定 {@code GET /api/v1/seasons} 的 query string。
 * page 为 0-based；size 默认 20、最小 1、最大 100。
 * status 作为字符串等值过滤，未知值由后续 Service 返回空页而不是 400。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeasonQuery {

    /** 分页默认页码（0-based 第一页） */
    public static final int DEFAULT_PAGE = 0;

    /** 分页默认每页条数 */
    public static final int DEFAULT_SIZE = 20;

    /** 分页每页条数上限，防止一次拖垮数据库 */
    public static final int MAX_SIZE = 100;

    /** 赛季状态过滤，可空表示不过滤 */
    @JsonProperty("status")
    private String status;

    /** 页码，0-based，缺省或负数按 0 处理 */
    @JsonProperty("page")
    private Integer page;

    /** 每页条数，缺省或 &lt;1 按 20，超过 100 截断为 100 */
    @JsonProperty("size")
    private Integer size;

    /**
     * 就地裁剪分页参数：page/size 非法值回落到默认，size 上限 100。
     * Controller 与 Service 边界均会调用，避免 size=200 打到数据库。
     */
    public void clampPaging() {
        this.page = clampPage(this.page);
        this.size = clampSize(this.size);
    }

    /**
     * 将页码裁剪为合法的 0-based 值。
     *
     * @param page 原始页码，可空
     * @return 合法页码（null 或 &lt;0 时返回 0）
     */
    public static int clampPage(Integer page) {
        if (page == null || page < 0) {
            return DEFAULT_PAGE;
        }
        return page;
    }

    /**
     * 将每页条数裁剪到 [1, 100]，非法值回落默认 20。
     *
     * @param size 原始每页条数，可空
     * @return 合法 size
     */
    public static int clampSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        if (size > MAX_SIZE) {
            return MAX_SIZE;
        }
        return size;
    }
}
