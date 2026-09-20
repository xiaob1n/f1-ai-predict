"""F1 AI Predict Python Worker 根包。

阶段一仅建立可导入的包边界，不在此实现配置、日志或业务逻辑。
"""

from typing import Final

# 与 pyproject.toml 的 project.version 对齐，供运行时与契约测试读取。
__version__: Final[str] = "0.1.0"
