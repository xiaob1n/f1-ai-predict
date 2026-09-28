package com.lbz.f1aipredict.prediction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 预测结果中选中的选项及其排序位置。 */
@Getter
@Setter
@TableName("prediction_result_item")
public class PredictionResultItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("result_id")
    private Long resultId;

    @TableField("option_id")
    private Integer optionId;

    @TableField("position")
    private Integer position;

    @TableField("created_at")
    private Instant createdAt;
}
