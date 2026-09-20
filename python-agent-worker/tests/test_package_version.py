"""包版本契约：公开符号 ``f1_predict.__version__`` 必须为 ``0.1.0``。"""


def test_package_version_is_0_1_0() -> None:
    """Given 已安装的 f1_predict 包, When 读取公开版本, Then 精确等于 0.1.0."""
    import f1_predict

    version = f1_predict.__version__

    assert version == "0.1.0"
