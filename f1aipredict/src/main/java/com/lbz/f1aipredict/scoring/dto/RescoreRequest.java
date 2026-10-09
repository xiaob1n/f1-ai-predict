package com.lbz.f1aipredict.scoring.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** 显式重算请求；reason 非空且去除首尾空白后不超过 64 个字符（与历史表 revision_reason 列宽一致），由服务端校验。 */
@Getter
@Setter
public class RescoreRequest {

    @JsonProperty("reason")
    private String reason;
}
