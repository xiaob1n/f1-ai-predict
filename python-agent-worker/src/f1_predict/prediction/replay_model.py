"""历史工程回放的确定性替身；仅验证消息链路，不代表真实模型能力。"""

from __future__ import annotations

import json
import math

from f1_predict.prediction.model import ModelCandidate
from f1_predict.prediction.model_http import InvalidModelOutput


class ReplayBaselineModel:
    """使用冻结中位圈时挑选候选，分数只用于工程占位且未经校准。"""

    async def predict(self, context: str) -> ModelCandidate:
        """不访问网络；完全从已校验的特征与选项映射计算候选。"""
        try:
            data = json.loads(context)
            task = data["predictionTask"]
            if task["kind"] != "HISTORICAL_ENGINEERING_REPLAY":
                raise InvalidModelOutput("historical replay context is missing")
            mapping = task["optionDrivers"]
            drivers = {
                item["driverNumber"]: item["medianLapSeconds"]
                for item in data["features"]["drivers"]
            }
            if (len(mapping) != 2 or len(drivers) != 2
                    or len(set(mapping.values())) != 2 or set(mapping.values()) != set(drivers)):
                raise InvalidModelOutput("historical replay requires two complete drivers")
            times = {int(option_id): drivers[number] for option_id, number in mapping.items()}
            if (len(times) != 2 or any(
                isinstance(value, bool) or not isinstance(value, (int, float))
                or not math.isfinite(value) or value <= 0
                for value in times.values()
            )):
                raise InvalidModelOutput("historical replay lap medians are invalid")
            selected = min(times, key=lambda option_id: (times[option_id], option_id))
            gap = abs(times[selected] - max(times.values())) / max(times.values())
            confidence = min(0.99, 0.5 + gap)
            return ModelCandidate(
                option_ids=[selected],
                confidence=confidence,
                reasoning_summary="确定性替身：按冻结练习圈中位数排序；分数未经校准",
            )
        except (KeyError, TypeError, ValueError) as error:
            raise InvalidModelOutput("historical replay baseline input is invalid") from error
