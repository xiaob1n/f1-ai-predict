package com.lbz.f1aipredict.scoring.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 从题目 Feed 的 Answer 字段独立提取官方答案，不更改题目快照模型。 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnswerFeedResponse {

    @JsonProperty("Data")
    private Data data;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        @JsonProperty("Value")
        private Value value;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Value {
        @JsonProperty("Questions")
        private List<Question> questions;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Question {
        @JsonProperty("Id")
        private Integer id;

        @JsonProperty("Answer")
        private JsonNode answer;
    }
}
