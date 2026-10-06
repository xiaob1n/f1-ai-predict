"""真实单题 MVP runner 的无副作用安全门禁测试。"""

from __future__ import annotations

import contextlib
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import run


class RunnerSafetyTest(unittest.TestCase):
    def test_inventory_is_static_and_marks_missing_prerequisites(self) -> None:
        result = run.inventory()
        self.assertFalse(result["real_resource_access"])
        self.assertFalse(result["model_calls"])
        self.assertIn("可信证据", result["trusted_metadata"])
        self.assertEqual(result["schema_allowlist"],
                         ["001", "002", "003", "004", "006", "007", "008"])
        self.assertNotIn("005", result["schema_allowlist"])

    def test_every_side_effect_stage_fails_closed_without_external_calls(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary) / "dedicated"
            for action in ("export", "prepare-isolated", "load-isolated", "run-stub",
                           "run-real-model"):
                with (self.subTest(action=action),
                      patch.object(run, "validate_run_dir", return_value=run_dir),
                      self.assertRaises(run.PreflightBlocked)):
                    run.blocked_stage(action, run_dir)

    def test_legacy_e2e_runtime_is_rejected(self) -> None:
        with self.assertRaisesRegex(run.PreflightBlocked, "禁止使用"):
            run.validate_run_dir(run.LEGACY_RUNTIME)

    def test_run_directory_must_be_private_and_under_home(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            outside_home = Path(temporary) / "runtime"
            with (patch.object(run.Path, "home", return_value=Path(temporary) / "home"),
                  self.assertRaisesRegex(run.PreflightBlocked, "home")):
                run.validate_run_dir(outside_home)

    def test_existing_directory_permissions_are_checked(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            runtime = home / "dedicated"
            runtime.mkdir(mode=0o700)
            runtime.chmod(0o755)
            with (patch.object(run.Path, "home", return_value=home),
                  self.assertRaisesRegex(run.PreflightBlocked, "权限为 0700")):
                run.validate_run_dir(runtime)

    def test_verify_and_stop_report_no_activity_without_side_effects(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            runtime = home / "dedicated"
            runtime.mkdir(mode=0o700)
            with patch.object(run.Path, "home", return_value=home):
                self.assertFalse(run.verify(runtime)["external_checks"])
                self.assertEqual(run.stop(runtime)["owned_processes"], 0)

    def test_cli_sensitive_actions_exit_blocked(self) -> None:
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            runtime = home / "dedicated"
            with patch.object(run.Path, "home", return_value=home), \
                    contextlib.redirect_stderr(output):
                code = run.main(["run-real-model", "--run-dir", str(runtime)])
        self.assertEqual(code, 2)
        self.assertIn("BLOCKED", output.getvalue())
        self.assertFalse(runtime.exists())

    def test_verify_reads_only_private_simulation_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            runtime = home / "dedicated"
            runtime.mkdir(mode=0o700)
            state = runtime / "workflow.json"
            state.write_text(json.dumps({"mode": "OFFLINE_SIMULATION", "stage": "STOPPED"}), encoding="utf-8")
            state.chmod(0o600)
            with patch.object(run.Path, "home", return_value=home):
                report = run.verify(runtime)
                self.assertEqual(report["status"], "SIMULATION_EVIDENCE_ONLY")
                self.assertFalse(report["external_checks"])
                self.assertFalse(report["m1Verified"])
                self.assertEqual(run.stop(runtime)["owned_processes"], 0)
            self.assertTrue(state.exists())

    def test_real_state_never_grants_stop_authority(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            runtime = home / "dedicated"
            runtime.mkdir(mode=0o700)
            state = runtime / "workflow.json"
            state.write_text(json.dumps({"mode": "REAL", "ownedProcesses": [1]}), encoding="utf-8")
            state.chmod(0o600)
            with (patch.object(run.Path, "home", return_value=home),
                  self.assertRaises(run.PreflightBlocked)):
                run.stop(runtime)

    def test_intermediate_home_symlink_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            target = home / "target"
            target.mkdir(mode=0o700)
            link = home / "alias"
            link.symlink_to(target, target_is_directory=True)
            with (patch.object(run.Path, "home", return_value=home),
                  self.assertRaisesRegex(run.PreflightBlocked, "符号链接")):
                run.validate_run_dir(link / "child")

    def test_keep_evidence_cannot_enable_a_real_stage(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stderr(output):
            code = run.main(["export", "--keep-evidence"])
        self.assertEqual(code, 2)
        self.assertIn("仅用于 dry-run", output.getvalue())

    def test_malformed_state_is_blocked_without_echoing_contents(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            runtime = home / "dedicated"
            runtime.mkdir(mode=0o700)
            state = runtime / "workflow.json"
            state.write_text("not-json-private-content", encoding="utf-8")
            state.chmod(0o600)
            output = io.StringIO()
            with patch.object(run.Path, "home", return_value=home), contextlib.redirect_stderr(output):
                code = run.main(["verify", "--run-dir", str(runtime)])
            self.assertEqual(code, 2)
            self.assertIn("BLOCKED:", output.getvalue())
            self.assertNotIn("not-json-private-content", output.getvalue())
            self.assertTrue(state.exists())

    def test_keep_evidence_is_an_explicit_dry_run_option(self) -> None:
        result = {"mode": "OFFLINE_SIMULATION", "m1Verified": False, "m2Verified": False}
        output = io.StringIO()
        with (patch.object(run, "dry_run", return_value=result) as simulated,
              patch.object(run, "validate_run_dir"), contextlib.redirect_stdout(output)):
            code = run.main(["dry-run", "--keep-evidence", "--run-dir", str(Path.home() / "new-simulation")])
        self.assertEqual(code, 0)
        simulated.assert_called_once_with(Path.home() / "new-simulation", keep_evidence=True)
        self.assertFalse(json.loads(output.getvalue())["m1Verified"])

    def test_inventory_cli_emits_machine_readable_blockers(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = run.main(["inventory"])
        self.assertEqual(code, 0)
        self.assertFalse(json.loads(output.getvalue())["real_resource_access"])

    def test_legacy_script_inventory_bootstraps_worker_source_without_install(self) -> None:
        script = Path(__file__).with_name("run.py")
        completed = subprocess.run(["python3", str(script), "inventory"], check=False,
                                   capture_output=True, text=True, timeout=10)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertFalse(report["real_resource_access"])
        self.assertFalse(report["model_calls"])


if __name__ == "__main__":
    unittest.main()
