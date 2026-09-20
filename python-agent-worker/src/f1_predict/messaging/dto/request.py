"""预测任务请求消息：题目、选项与赛事上下文随消息完整下发。"""

from __future__ import annotations

from typing import Literal

from pydantic import Field

from f1_predict.messaging.dto.base import (
    F1PredictBaseModel,
    PredictionMessageEnvelope,
    UtcDateTime,
)


class QuestionOptionPayload(F1PredictBaseModel):
    """题目选项快照。``optionId`` 只在当前快照范围内有意义，原样回填。"""

    option_id: int = Field(alias="optionId")
    option_no: int = Field(alias="optionNo")
    option_text: str = Field(alias="optionText")
    points: int = Field(alias="points")
    chance: float = Field(alias="chance")


class QuestionPayload(F1PredictBaseModel):
    """请求内嵌的题目载荷。阶段一 ``questionType`` 固定为 ``UNKNOWN``。"""

    question_text: str = Field(alias="questionText")
    sub_text: str | None = Field(alias="subText")
    question_type: Literal["UNKNOWN"] = Field(alias="questionType")
    option_template_id: int = Field(alias="optionTemplateId")
    choice_limit: int = Field(alias="choiceLimit")
    options: list[QuestionOptionPayload] = Field(alias="options", default_factory=list)


class RaceContext(F1PredictBaseModel):
    """赛事上下文，供特征与检索限定当前 round / session。"""

    season_id: int = Field(alias="seasonId")
    year: int = Field(alias="year")
    round_id: int = Field(alias="roundId")
    round_number: int = Field(alias="roundNumber")
    meeting_key: int = Field(alias="meetingKey")
    session_key: int = Field(alias="sessionKey")
    gameday_id: int = Field(alias="gamedayId")
    track_name: str = Field(alias="trackName")


class PredictionRequestMessage(PredictionMessageEnvelope):
    """Java 发布、Python 消费的预测任务请求。"""

    question_snapshot_id: int = Field(alias="questionSnapshotId")
    question: QuestionPayload = Field(alias="question")
    race_context: RaceContext = Field(alias="raceContext")
    data_cutoff: UtcDateTime = Field(alias="dataCutoff")
    model_version: str = Field(alias="modelVersion")
    prompt_version: str = Field(alias="promptVersion")
    feature_version: str = Field(alias="featureVersion")
    embedding_version: str = Field(alias="embeddingVersion")
    retriever_version: str = Field(alias="retrieverVersion")
