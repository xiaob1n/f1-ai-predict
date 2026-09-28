"""模型候选仅提供受限选项和说明；来源与版本均由代码回填。"""

from __future__ import annotations

from typing import Protocol

from pydantic import BaseModel, ConfigDict, Field


class ModelCandidate(BaseModel):
    """不接受模型自称的数据源或时间戳。"""

    model_config = ConfigDict(extra="forbid", strict=True)

    option_ids: list[int] = Field(min_length=1, max_length=1)
    confidence: float = Field(ge=0, le=1)
    reasoning_summary: str = Field(min_length=1, max_length=500)


class ModelGateway(Protocol):
    """只有显式部署并验证结构化输出能力的适配器才可注入。"""

    async def predict(self, context: str) -> ModelCandidate:
        """返回经过 Pydantic 解析的候选，不执行特征文本中的指令。"""
        ...
