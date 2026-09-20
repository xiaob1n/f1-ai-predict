"""公共符号中文 docstring 契约：只扫描 ``src/f1_predict``，不把测试路由当生产 API。"""

from __future__ import annotations

import ast
from collections.abc import Iterator
from pathlib import Path

# 汉字范围：CJK 统一表意文字、扩展 A、兼容表意文字。禁止使用残缺区间。
_CHINESE_CHAR = r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]"

_SRC_ROOT = Path(__file__).resolve().parents[2] / "src" / "f1_predict"


def _is_public_name(name: str) -> bool:
    """公共符号：名称不以单下划线或双下划线开头。"""
    return not name.startswith("_")


def _iter_python_files(root: Path) -> Iterator[Path]:
    """遍历生产包内的 ``.py`` 文件，跳过缓存目录。"""
    for path in sorted(root.rglob("*.py")):
        if "__pycache__" in path.parts:
            continue
        yield path


def _docstring_of(node: ast.AST) -> str | None:
    """读取模块/类/函数的 docstring；缺失则为 None。"""
    match node:
        case ast.Module() | ast.ClassDef() | ast.FunctionDef() | ast.AsyncFunctionDef():
            return ast.get_docstring(node, clean=False)
        case _:
            return None


def _iter_public_symbols(tree: ast.Module, *, relative: str) -> Iterator[tuple[str, int, str | None]]:
    """产出模块、公共类、公共函数与公共方法的 (限定名, 行号, docstring)。

    不下降到函数内部的嵌套定义，避免把一次性测试路由或闭包误判为生产 API。
    """
    yield (relative, 1, ast.get_docstring(tree, clean=False))
    for node in tree.body:
        match node:
            case ast.ClassDef() if _is_public_name(node.name):
                yield (f"{relative}:{node.name}", node.lineno, _docstring_of(node))
                for item in node.body:
                    match item:
                        case ast.FunctionDef() | ast.AsyncFunctionDef() if _is_public_name(item.name):
                            yield (
                                f"{relative}:{node.name}.{item.name}",
                                item.lineno,
                                _docstring_of(item),
                            )
                        case _:
                            continue
            case ast.FunctionDef() | ast.AsyncFunctionDef() if _is_public_name(node.name):
                yield (f"{relative}:{node.name}", node.lineno, _docstring_of(node))
            case _:
                continue


def _has_chinese(text: str) -> bool:
    """文档字符串是否含至少一个汉字。"""
    import re

    return re.search(_CHINESE_CHAR, text) is not None


def test_public_symbols_have_nonempty_chinese_docstrings() -> None:
    """Given 生产包源码, When AST 扫描公共符号, Then 均有非空且含汉字的 docstring."""
    assert _SRC_ROOT.is_dir()
    files = list(_iter_python_files(_SRC_ROOT))
    assert files, f"未找到生产源码: {_SRC_ROOT}"

    missing: list[str] = []
    english_only: list[str] = []
    scanned = 0
    for path in files:
        relative = path.relative_to(_SRC_ROOT).as_posix()
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        assert isinstance(tree, ast.Module)
        for qualified, lineno, docstring in _iter_public_symbols(tree, relative=relative):
            scanned += 1
            location = f"{relative}:{lineno} {qualified}"
            if docstring is None or not docstring.strip():
                missing.append(location)
                continue
            if not _has_chinese(docstring):
                english_only.append(location)

    assert scanned > 0
    assert not missing, "缺少 docstring:\n" + "\n".join(missing)
    assert not english_only, "docstring 不含中文:\n" + "\n".join(english_only)


def test_docstring_scan_stays_inside_src() -> None:
    """Given 扫描根, When 解析路径, Then 只覆盖 src/f1_predict，不含 tests。"""
    src_text = Path(__file__).read_text(encoding="utf-8")
    assert "src" in str(_SRC_ROOT)
    assert _SRC_ROOT.name == "f1_predict"
    assert "tests" not in _SRC_ROOT.parts
    assert "python-agent-worker" in _SRC_ROOT.parts
    # 契约本身不得去扫描 tests，避免把装饰器测试路由当生产公共 API。
    assert "rglob" in src_text
    assert all(path.is_relative_to(_SRC_ROOT) for path in _iter_python_files(_SRC_ROOT))
