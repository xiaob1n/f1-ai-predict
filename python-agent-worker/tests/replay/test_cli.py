from __future__ import annotations

import json

from f1_predict.replay import cli


def test_inventory_is_static_and_marks_real_stages_blocked(capsys):
    assert cli.main(["inventory"]) == 0
    report = json.loads(capsys.readouterr().out)
    assert report["real_resource_access"] is False
    assert report["model_calls"] is False
    assert report["simulation"]["real"] is False
    assert report["simulation"]["m1"] is False
    assert report["simulation"]["m2"] is False
    assert set(report["blocked_stages"]) >= {"export", "prepare-isolated", "load-isolated", "run-stub", "run-real-model"}


def test_dry_run_requires_explicit_schema_root(tmp_path, capsys):
    run_dir = cli.Path.home() / ".f1predict-test-not-created"
    assert cli.main(["dry-run", "--run-dir", str(run_dir)]) == 2
    assert "schema-root" in capsys.readouterr().err
    assert not run_dir.exists()


def test_cli_passes_explicit_schema_root_through_legacy_hook(tmp_path, capsys):
    run_dir = tmp_path / "runtime"
    schema_root = tmp_path / "sql"
    schema_root.mkdir()
    calls = []
    hooks = {
        "validate_run_dir": lambda path: path,
        "dry_run": lambda path, **kwargs: calls.append((path, kwargs)) or {"offline": True},
    }
    assert cli.main(["dry-run", "--run-dir", str(run_dir), "--schema-root", str(schema_root)],
                    hooks=hooks) == 0
    assert calls == [(run_dir, {"keep_evidence": False, "schema_root": schema_root})]
    assert '"offline": true' in capsys.readouterr().out


def test_run_directory_rejects_parent_symlink_that_resolves_inside_home(tmp_path, monkeypatch):
    import pytest

    home = tmp_path / "home"
    home.mkdir()
    real_parent = home / "real"
    real_parent.mkdir()
    alias_parent = tmp_path / "alias"
    alias_parent.symlink_to(real_parent, target_is_directory=True)
    monkeypatch.setattr(cli.Path, "home", lambda: home)
    with pytest.raises(cli.PreflightBlocked, match="符号链接"):
        cli.validate_run_dir(alias_parent / "child")


def test_run_directory_rejects_external_symlink_alias_into_home(tmp_path, monkeypatch):
    import pytest

    home = tmp_path / "home"
    home.mkdir()
    real = home / "real"
    real.mkdir()
    alias = tmp_path / "outside-alias"
    alias.symlink_to(home, target_is_directory=True)
    monkeypatch.setattr(cli.Path, "home", lambda: home)
    with pytest.raises(cli.PreflightBlocked, match="符号链接"):
        cli.validate_run_dir(alias / "real")


def test_every_resource_stage_exits_two_without_creating_run_directory(tmp_path, capsys):
    run_dir = tmp_path / "not-created"
    for action in ("export", "prepare-isolated", "load-isolated", "run-stub", "run-real-model"):
        assert cli.main([action, "--run-dir", str(run_dir)]) == 2
        assert "BLOCKED" in capsys.readouterr().err
        assert not run_dir.exists()
