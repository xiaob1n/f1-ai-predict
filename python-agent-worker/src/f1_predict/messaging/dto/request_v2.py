"""预测请求 v2 契约：保留源数据缺失值，不修改既有 v1 契约。"""

from __future__ import annotations

from typing import Literal

from pydantic import Field

from f1_predict.messaging.dto.base import F1PredictBaseModel, UtcDateTime


class QuestionOptionV2(F1PredictBaseModel):
    """快照选项；null 表示源数据未提供，不能代替为零。"""

    option_id: int | None = Field(alias="optionId")
    option_no: int = Field(alias="optionNo", ge=0)
    option_text: str | None = Field(alias="optionText")
    points: int | None = Field(alias="points")
    chance: float | None = Field(alias="chance")


class QuestionV2(F1PredictBaseModel):
    """从指定题目快照冻结的字段。"""

    question_text: str = Field(alias="questionText", min_length=1)
    sub_text: str | None = Field(alias="subText")
    question_type: Literal["UNKNOWN", "SINGLE"] = Field(alias="questionType")
    option_template_id: int | None = Field(alias="optionTemplateId")
    choice_limit: int | None = Field(alias="choiceLimit")
    options: list[QuestionOptionV2] = Field(alias="options", min_length=1)


class RaceContextV2(F1PredictBaseModel):
    """Round 和题目所属赛事信息，无唯一 Session 时键为 null。"""

    season_id: int = Field(alias="seasonId", gt=0)
    year: int = Field(alias="year", gt=0)
    round_id: int = Field(alias="roundId", gt=0)
    round_number: int = Field(alias="roundNumber", gt=0)
    meeting_key: int | None = Field(alias="meetingKey")
    session_key: int | None = Field(alias="sessionKey")
    gameday_id: int = Field(alias="gamedayId", gt=0)
    track_name: str = Field(alias="trackName", min_length=1)


class PredictionRequestV2(F1PredictBaseModel):
    """Java 每个任务发送一条的不可变请求。"""

    schema_version: Literal["2"] = Field(alias="schemaVersion")
    message_id: str = Field(alias="messageId", min_length=1)
    prediction_job_id: str = Field(alias="predictionJobId", min_length=1)
    batch_id: int = Field(alias="batchId", gt=0)
    question_id: int = Field(alias="questionId", gt=0)
    trace_id: str = Field(alias="traceId", min_length=1)
    question_snapshot_id: int = Field(alias="questionSnapshotId", gt=0)
    question: QuestionV2 = Field(alias="question")
    race_context: RaceContextV2 = Field(alias="raceContext")
    data_cutoff: UtcDateTime = Field(alias="dataCutoff")
    model_version: str = Field(alias="modelVersion", min_length=1)
    prompt_version: str = Field(alias="promptVersion", min_length=1)
    feature_version: str = Field(alias="featureVersion", min_length=1)
    embedding_version: str | None = Field(alias="embeddingVersion")
    retriever_version: str | None = Field(alias="retrieverVersion")
