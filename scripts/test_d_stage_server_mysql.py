import importlib.util
import io
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

SCRIPT_PATH = Path(__file__).with_name("d-stage-server-mysql.py")
SPEC = importlib.util.spec_from_file_location("d_stage_server_mysql", SCRIPT_PATH)
module = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(module)


class FakeCursor:
    def __init__(self, connection):
        self.connection = connection
        self.result = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def execute(self, sql, params=None):
        self.connection.statements.append((sql, params))
        if sql == "SELECT SUBSTRING_INDEX(USER(), '@', -1)":
            self.result = ("203.0.113.7",)
        elif sql.startswith("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA"):
            self.result = None
        elif sql.startswith("SELECT COUNT(*) FROM mysql.user"):
            self.result = (0,)
        elif sql == "SELECT DATABASE()":
            self.result = (self.connection.database,)
        elif sql == "SELECT DATABASE(), USER(), CURRENT_USER()":
            identity = f"{self.connection.user}@{self.connection.account_host}"
            self.result = (self.connection.database, identity, identity)
        elif sql.startswith("SELECT run_id FROM"):
            self.result = ((FakeConnection.last_run_id,),)
        else:
            match = re.search(r"VALUES \('([0-9a-f]{32})', UTC_TIMESTAMP", sql)
            if match:
                self.connection.run_id = match.group(1)
                FakeConnection.last_run_id = match.group(1)
            self.result = None

    def fetchone(self):
        return self.result

    def fetchall(self):
        return self.result


class FakeConnection:
    last_run_id = None

    def __init__(self, database=None):
        self.database = database
        self.user = "root-user"
        self.account_host = "203.0.113.7"
        self.statements = []
        self.run_id = None
        self.closed = False

    def cursor(self):
        return FakeCursor(self)

    def close(self):
        self.closed = True


class ServerMysqlScriptTest(unittest.TestCase):
    def test_ddl_is_retargeted_to_only_the_random_schema(self):
        schema = "f1_it_0123456789abcdef"
        statements = module.get_safe_schema_sql(schema)
        self.assertIn((module.SQL_FILES[0], f"CREATE DATABASE `{schema}` DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci"), statements)
        self.assertTrue(any(statement == f"USE `{schema}`" for _, statement in statements))
        self.assertTrue(all("f1_ai_predict" not in statement for _, statement in statements))
        self.assertTrue(all(re.match(r"^(?:CREATE DATABASE|USE|CREATE TABLE|ALTER TABLE)\b", statement, re.IGNORECASE) for _, statement in statements))

    def test_rejects_schema_names_outside_random_test_pattern(self):
        with self.assertRaises(module.SafeSetupError):
            module.get_safe_schema_sql("f1_ai_predict")

    def test_client_grant_host_accepts_only_ip_or_localhost(self):
        self.assertEqual("127.0.0.1", module.validate_client_host("127.0.0.1"))
        self.assertEqual("localhost", module.validate_client_host("localhost"))
        with self.assertRaises(module.SafeSetupError):
            module.validate_client_host("%")

    def test_initialization_uses_only_target_schema_and_exact_client_ip_grants(self):
        schema = "f1_it_0123456789abcdef"
        prepared = module.get_safe_schema_sql(schema)
        root_connection = FakeConnection("mvp")
        app_connection = FakeConnection(schema)
        connections = iter((root_connection, app_connection))
        config = {
            "MVP_SOURCE_MYSQL_HOST": "mysql.example.com",
            "MVP_SOURCE_MYSQL_PORT": "3306",
            "MVP_SOURCE_MYSQL_USER": "root-user",
            "MVP_SOURCE_MYSQL_PASSWORD": "never-print-this",
            "MVP_SOURCE_MYSQL_DATABASE": "mvp",
            "SERVER_IP": "203.0.113.1",
            "SERVER_USER": "runner",
            "SERVER_PASSWORD": "ssh-secret",
            "MYSQL_TUNNEL_PORT": "33445",
        }

        def fake_mysql_connection(connection_config, database=None, tunnel_process=None):
            connection = next(connections)
            connection.database = database
            connection.user = connection_config["MVP_SOURCE_MYSQL_USER"]
            return connection

        with tempfile.TemporaryDirectory() as temp_dir, patch.object(
            module, "mysql_connection", side_effect=fake_mysql_connection
        ):
            actual_schema, run_id, env_path = module.create_isolated_database(
                config, prepared, Path(temp_dir), schema
            )
            text = env_path.read_text(encoding="utf-8")
            mode = env_path.stat().st_mode & 0o777
            self.assertEqual(schema, actual_schema)
            self.assertRegex(run_id, r"^[0-9a-f]{32}$")
            self.assertEqual(0o600, mode)
            self.assertIn("F1_IT_REMOTE_MODE=true", text)
            self.assertIn(f"F1_IT_DATABASE={schema}", text)
            self.assertNotIn("never-print-this", text)
            executed = [sql for sql, _ in root_connection.statements]
            self.assertTrue(all("f1_ai_predict" not in sql for sql in executed))
            grants = [sql for sql in executed if sql.startswith("GRANT ")]
            self.assertEqual(4, len(grants))
            self.assertTrue(all(f"ON `{schema}`.*" in sql and "@'203.0.113.7'" in sql for sql in grants))
            state = next(Path(temp_dir).glob("*.state")).read_text(encoding="utf-8")
            self.assertIn("status=initialized", state)
            self.assertIn("database:" + schema, state)
            self.assertTrue(root_connection.closed)
            self.assertTrue(app_connection.closed)
            run_config, it_values, redactions = module.load_run_configuration(
                Path(temp_dir), run_id, config
            )
            self.assertEqual(schema, it_values["F1_IT_DATABASE"])
            self.assertEqual("203.0.113.7", run_config["MYSQL_ACCOUNT_HOST"])
            self.assertIn("never-print-this", redactions)

    def test_comment_text_with_dots_is_not_treated_as_schema_qualification(self):
        statements = module.get_safe_schema_sql("f1_it_0123456789abcdef")
        question_comment = [statement for _, statement in statements if "question.id" in statement]
        self.assertTrue(question_comment)
        self.assertTrue(all("COMMENT" in statement for statement in question_comment))

    def test_parses_fixed_ddl_when_head_007_has_no_use(self):
        head_007 = subprocess.run(
            ["git", "show", "HEAD:sql/007_prediction_request_outbox.sql"],
            cwd=module.ROOT,
            check=True,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
        ).stdout
        self.assertNotRegex(head_007, r"(?im)^\s*USE\b")
        head_007 = "-- USE `f1_ai_predict`; " + "\n" + head_007
        with tempfile.TemporaryDirectory() as temp_dir:
            sql_dir = Path(temp_dir)
            for filename in module.SQL_FILES:
                target = sql_dir / filename
                if filename == "007_prediction_request_outbox.sql":
                    target.write_text(head_007, encoding="utf-8")
                else:
                    shutil.copyfile(module.SQL_DIR / filename, target)
            with patch.object(module, "SQL_DIR", sql_dir):
                statements = module.get_safe_schema_sql("f1_it_0123456789abcdef")
            seventh = [
                statement
                for filename, statement in statements
                if filename == "007_prediction_request_outbox.sql"
            ]
            self.assertEqual("USE `f1_it_0123456789abcdef`", seventh[0])
            self.assertTrue(seventh[1].startswith("CREATE TABLE"))

    def test_rejects_multiple_or_cross_schema_use_statements_in_007(self):
        invalid_uses = (
            "USE `f1_ai_predict`;\nUSE `f1_ai_predict`;",
            "USE `other_schema`;",
        )
        for invalid_use in invalid_uses:
            with self.subTest(invalid_use=invalid_use), tempfile.TemporaryDirectory() as temp_dir:
                sql_dir = Path(temp_dir)
                for filename in module.SQL_FILES:
                    shutil.copyfile(module.SQL_DIR / filename, sql_dir / filename)
                target = sql_dir / "007_prediction_request_outbox.sql"
                source = target.read_text(encoding="utf-8")
                source = re.sub(r"(?im)^\s*USE\s+[^;]+;\s*", "", source)
                source = source.replace("CREATE TABLE", invalid_use + "\nCREATE TABLE", 1)
                target.write_text(source, encoding="utf-8")
                with patch.object(module, "SQL_DIR", sql_dir), self.assertRaises(module.SafeSetupError):
                    module.get_safe_schema_sql("f1_it_0123456789abcdef")

    def test_rejects_schema_qualified_create_table_target(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            sql_dir = Path(temp_dir)
            for filename in module.SQL_FILES:
                shutil.copyfile(module.SQL_DIR / filename, sql_dir / filename)
            target = sql_dir / "002_season_round.sql"
            source = target.read_text(encoding="utf-8")
            source = source.replace(
                "CREATE TABLE IF NOT EXISTS `season`",
                "CREATE TABLE IF NOT EXISTS `other_schema`.`season`",
                1,
            )
            target.write_text(source, encoding="utf-8")
            with patch.object(module, "SQL_DIR", sql_dir), self.assertRaises(module.SafeSetupError):
                module.get_safe_schema_sql("f1_it_0123456789abcdef")

    def test_rejects_schema_qualified_references_target(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            sql_dir = Path(temp_dir)
            for filename in module.SQL_FILES:
                shutil.copyfile(module.SQL_DIR / filename, sql_dir / filename)
            target = sql_dir / "007_prediction_request_outbox.sql"
            source = target.read_text(encoding="utf-8")
            source = source.replace(
                "REFERENCES prediction_job",
                "REFERENCES `other_schema`.`prediction_job`",
                1,
            )
            target.write_text(source, encoding="utf-8")
            with patch.object(module, "SQL_DIR", sql_dir), self.assertRaises(module.SafeSetupError):
                module.get_safe_schema_sql("f1_it_0123456789abcdef")

    def test_parse_env_file_keeps_server_keys_and_values_for_redaction(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            env_path = Path(temp_dir) / "example.env"
            env_path.write_text(
                "MVP_SOURCE_MYSQL_HOST=127.0.0.1\n"
                "MVP_SOURCE_MYSQL_PORT=3306\n"
                "MVP_SOURCE_MYSQL_USER=db-root\n"
                "MVP_SOURCE_MYSQL_PASSWORD=db-secret\n"
                "MVP_SOURCE_MYSQL_DATABASE=f1_ai_predict\n"
                "SERVER_IP=203.0.113.1\n"
                "SERVER_USER=runner\n"
                "SERVER_PASSWORD='ssh secret' # comment\n"
                "OTHER_TOKEN='another secret'\n"
                "lowerCaseToken=lowercase-secret\n",
                encoding="utf-8",
            )
            values, redactions = module.parse_env_file(env_path)
            self.assertEqual("203.0.113.1", values["SERVER_IP"])
            self.assertEqual("runner", values["SERVER_USER"])
            self.assertEqual("ssh secret", values["SERVER_PASSWORD"])
            self.assertTrue({"db-secret", "ssh secret", "another secret", "lowercase-secret"}.issubset(redactions))

    def test_maven_output_is_redacted_before_console_and_scratch_log(self):
        class FakeProcess:
            def __init__(self):
                self.stdout = io.StringIO(
                    "password=app-secret ssh=ssh-secret jdbc="
                    "jdbc:mysql://127.0.0.1:43210/f1_it_0123456789abcdef?connectionTimeZone=UTC\n"
                )
                self.stderr = io.StringIO("root=db-root-secret host=203.0.113.9\n")

            def wait(self):
                return 0

            def terminate(self):
                return None

        run_values = {
            "F1_IT_DATABASE": "f1_it_0123456789abcdef",
            "F1_IT_DB_USERNAME": "f1_it_0123456789abcdef",
            "F1_IT_DB_PASSWORD": "app-secret",
            "F1_IT_RUN_ID": "a" * 32,
        }
        process = FakeProcess()
        call = {}

        def fake_popen(command, **kwargs):
            call.update(command=command, **kwargs)
            return process

        with tempfile.TemporaryDirectory() as temp_dir, patch.dict(
            os.environ,
            {
                "SERVER_PASSWORD": "ssh-secret",
                "MVP_SOURCE_MYSQL_PASSWORD": "db-root-secret",
                "MYSQL_PASSWORD": "other-secret",
                "OTHER_TOKEN": "dotenv-secret",
                "F1_IT_DB_PASSWORD": "stale-secret",
            },
        ), patch.object(module.subprocess, "Popen", side_effect=fake_popen), patch.object(
            module, "_assert_tunnel_active"
        ):
            stdout = io.StringIO()
            stderr = io.StringIO()
            with redirect_stdout(stdout), redirect_stderr(stderr):
                result, log_path = module.run_maven_it(
                    Path(temp_dir),
                    run_values,
                    ["app-secret", "ssh-secret", "db-root-secret", "203.0.113.9", "dotenv-secret"],
                    43210,
                    object(),
                )
            self.assertEqual(0, result)
            self.assertEqual(
                ["./mvnw", "-Pit", "test-compile", "failsafe:integration-test", "failsafe:verify"],
                call["command"],
            )
            self.assertEqual("app-secret", call["env"]["F1_IT_DB_PASSWORD"])
            self.assertNotIn("SERVER_PASSWORD", call["env"])
            self.assertNotIn("MVP_SOURCE_MYSQL_PASSWORD", call["env"])
            self.assertNotIn("MYSQL_PASSWORD", call["env"])
            self.assertNotIn("OTHER_TOKEN", call["env"])
            self.assertNotIn("API_TOKEN", call["env"])
            output = stdout.getvalue() + stderr.getvalue()
            saved = log_path.read_text(encoding="utf-8")
            for secret in ("app-secret", "ssh-secret", "db-root-secret", "203.0.113.9"):
                self.assertNotIn(secret, output)
                self.assertNotIn(secret, saved)
            self.assertIn("[REDACTED_JDBC_URL]", saved)
            self.assertEqual(0o600, log_path.stat().st_mode & 0o777)

    @staticmethod
    def _write_it_sources(root: Path) -> None:
        (root / "pkg").mkdir(parents=True)
        (root / "pkg" / "FooIT.java").write_text(
            "class FooIT extends AbstractBaseIT {\n"
            "    @Test\n    void alpha() {}\n"
            "    @Test\n    @DisplayName(\"beta\")\n    void beta() throws Exception {}\n"
            "    @RepeatedTest(3)\n    void repeated() {}\n"
            "    @TestConfiguration\n    static class NotATestMethod { void gamma() {} }\n"
            "}\n",
            encoding="utf-8",
        )
        (root / "pkg" / "AbstractBaseIT.java").write_text(
            "abstract class AbstractBaseIT {\n    @Test\n    void inherited() {}\n}\n", encoding="utf-8"
        )
        (root / "pkg" / "HelperIT.java").write_text("interface HelperIT {}\n", encoding="utf-8")
        (root / "pkg" / "Plain.java").write_text("class PlainIT { @Test void x() {} }\n", encoding="utf-8")

    def test_it_selector_accepts_only_concrete_classes_and_methods_from_current_sources(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            sources = Path(temp_dir)
            self._write_it_sources(sources)
            discovered = module.discover_it_tests(sources)
            self.assertEqual({"FooIT"}, set(discovered))
            self.assertEqual({"alpha", "beta", "repeated"}, discovered["FooIT"])
            self.assertEqual("FooIT", module.parse_it_tests("FooIT", sources))
            self.assertEqual("FooIT#alpha,FooIT#beta", module.parse_it_tests("FooIT#alpha,FooIT#beta", sources))
        # 当前仓库源码中的两个本轮目标类必须可选，抽象基类不可选。
        self.assertEqual(
            "ScheduleGamedayOwnershipIT,RoundOwnershipLockWaitIT",
            module.parse_it_tests("ScheduleGamedayOwnershipIT,RoundOwnershipLockWaitIT"),
        )
        with self.assertRaises(module.SafeSetupError):
            module.parse_it_tests("AbstractDStageIT")

    def test_it_selector_rejects_unknown_wildcard_and_option_injection(self):
        invalid_values = (
            "",
            "FooIT,",
            ",FooIT",
            "BarIT",
            "AbstractBaseIT",
            "HelperIT",
            "PlainIT",
            "Foo*",
            "*IT",
            "Foo?IT",
            "FooIT#",
            "FooIT#missing",
            "FooIT#alpha+beta",
            "FooIT#al*",
            "FooIT;touch",
            "FooIT -DskipTests",
            "FooIT,-DskipITs=true",
            "-Dmaven.test.skip=true",
            "FooIT,FooIT",
            "FooIT,FooIT#alpha",
            "FooIT#alpha,FooIT",
            "../FooIT",
            "pkg.FooIT",
            "FooIT\n",
            ",".join(["FooIT#alpha"] * (module.MAX_IT_SELECTIONS + 1)),
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            sources = Path(temp_dir)
            self._write_it_sources(sources)
            for value in invalid_values:
                with self.subTest(value=value), self.assertRaises(module.SafeSetupError):
                    module.parse_it_tests(value, sources)

    def test_it_selector_refuses_ambiguous_duplicate_simple_class_names(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            sources = Path(temp_dir)
            self._write_it_sources(sources)
            (sources / "other").mkdir()
            (sources / "other" / "FooIT.java").write_text("class FooIT { @Test void alpha() {} }\n", encoding="utf-8")
            self.assertNotIn("FooIT", module.discover_it_tests(sources))
            with self.assertRaises(module.SafeSetupError):
                module.parse_it_tests("FooIT", sources)

    def test_maven_command_with_selector_keeps_verify_and_never_skips_tests(self):
        default = module.build_maven_it_command()
        self.assertEqual(["./mvnw", "-Pit", "test-compile", "failsafe:integration-test", "failsafe:verify"], default)
        selected = module.build_maven_it_command("ScheduleGamedayOwnershipIT,RoundOwnershipLockWaitIT")
        self.assertEqual(
            [
                "./mvnw",
                "-Pit",
                "-Dit.test=ScheduleGamedayOwnershipIT,RoundOwnershipLockWaitIT",
                "-Dit.failIfNoSpecifiedTests=true",
                "test-compile",
                "failsafe:integration-test",
                "failsafe:verify",
            ],
            selected,
        )
        self.assertEqual("failsafe:verify", selected[-1])
        self.assertFalse([argument for argument in selected if "skip" in argument.lower()])
        # 除选择器本身和“无匹配即失败”外不允许出现其他 -D 属性。
        self.assertEqual(
            ["-Dit.test=ScheduleGamedayOwnershipIT,RoundOwnershipLockWaitIT", "-Dit.failIfNoSpecifiedTests=true"],
            [argument for argument in selected if argument.startswith("-D")],
        )
        with self.assertRaises(module.SafeSetupError):
            module.build_maven_it_command("ScheduleGamedayOwnershipIT,-DskipTests")

    def test_run_maven_it_passes_validated_selector_and_rejects_invalid_one_before_launch(self):
        class FakeProcess:
            def __init__(self):
                self.stdout = io.StringIO("")
                self.stderr = io.StringIO("")

            def wait(self, timeout=None):
                return 0

            def terminate(self):
                return None

        run_values = {
            "F1_IT_DATABASE": "f1_it_0123456789abcdef",
            "F1_IT_DB_USERNAME": "f1_it_0123456789abcdef",
            "F1_IT_DB_PASSWORD": "app-secret",
            "F1_IT_RUN_ID": "a" * 32,
        }
        calls = []

        def fake_popen(command, **kwargs):
            calls.append(command)
            return FakeProcess()

        with tempfile.TemporaryDirectory() as temp_dir, patch.object(
            module.subprocess, "Popen", side_effect=fake_popen
        ), patch.object(module, "_assert_tunnel_active"):
            result, log_path = module.run_maven_it(
                Path(temp_dir), run_values, [], 43210, object(), it_tests="ScheduleGamedayOwnershipIT"
            )
            self.assertEqual(0, result)
            self.assertEqual(
                [
                    "./mvnw",
                    "-Pit",
                    "-Dit.test=ScheduleGamedayOwnershipIT",
                    "-Dit.failIfNoSpecifiedTests=true",
                    "test-compile",
                    "failsafe:integration-test",
                    "failsafe:verify",
                ],
                calls[0],
            )
            self.assertTrue(log_path.exists())
            calls.clear()
            with self.assertRaises(module.SafeSetupError):
                module.run_maven_it(
                    Path(temp_dir), run_values, [], 43210, object(), it_tests="ScheduleGamedayOwnershipIT,-DskipITs"
                )
            self.assertEqual([], calls)
            self.assertEqual(1, len(list((Path(temp_dir) / "logs").glob("*.log"))))

    def test_it_tests_option_requires_run_it_and_is_validated_before_reading_env(self):
        env_reader = patch.object(module, "parse_env_file", side_effect=AssertionError("must not read .env"))
        tunnel = patch.object(module, "ssh_mysql_tunnel", side_effect=AssertionError("must not open tunnel"))
        with env_reader as read_env, tunnel as open_tunnel:
            stderr = io.StringIO()
            with patch.object(module.sys, "argv", ["prog", "--it-tests", "ScheduleGamedayOwnershipIT"]), \
                    redirect_stderr(stderr), self.assertRaises(SystemExit) as exit_info:
                module.main()
            self.assertEqual(2, exit_info.exception.code)
            self.assertIn("--it-tests", stderr.getvalue())

            stderr = io.StringIO()
            with patch.object(
                module.sys, "argv", ["prog", "--confirm-create", "--it-tests", "ScheduleGamedayOwnershipIT"]
            ), redirect_stderr(stderr), self.assertRaises(SystemExit) as exit_info:
                module.main()
            self.assertEqual(2, exit_info.exception.code)

            for invalid in ("UnknownIT", "ScheduleGamedayOwnershipIT,-DskipTests", ""):
                stderr = io.StringIO()
                with patch.object(
                    module.sys, "argv", ["prog", "--run-it", "a" * 32, "--it-tests", invalid]
                ), redirect_stderr(stderr):
                    self.assertEqual(1, module.main())
                self.assertIn("安全停止", stderr.getvalue())
            read_env.assert_not_called()
            open_tunnel.assert_not_called()

    def test_run_it_passes_selector_to_maven_runner_without_changing_default(self):
        class FakeTunnel:
            def __enter__(self):
                return 43210, object()

            def __exit__(self, exc_type, exc_value, traceback):
                return False

        config = {"SERVER_IP": "203.0.113.1", "SERVER_USER": "runner", "SERVER_PASSWORD": "ssh-secret"}
        run_config = dict(config, MYSQL_ACCOUNT_HOST="203.0.113.7")
        it_values = {
            "F1_IT_DATABASE": "f1_it_0123456789abcdef",
            "F1_IT_DB_USERNAME": "f1_it_0123456789abcdef",
            "F1_IT_DB_PASSWORD": "app-secret",
            "F1_IT_RUN_ID": "a" * 32,
        }
        for argv_tail, expected in (
            (["--it-tests", "ScheduleGamedayOwnershipIT,RoundOwnershipLockWaitIT"],
             "ScheduleGamedayOwnershipIT,RoundOwnershipLockWaitIT"),
            ([], None),
        ):
            with self.subTest(argv_tail=argv_tail), tempfile.TemporaryDirectory() as temp_dir, \
                    patch.object(module.sys, "argv", ["prog", "--run-it", "a" * 32, *argv_tail]), \
                    patch.object(module, "parse_env_file", return_value=(config, [])), \
                    patch.object(module, "state_dir", return_value=Path(temp_dir)), \
                    patch.object(module, "load_run_configuration", return_value=(run_config, it_values, [])), \
                    patch.object(module, "ssh_mysql_tunnel", return_value=FakeTunnel()), \
                    patch.object(module, "tunnel_config", return_value=run_config), \
                    patch.object(module, "mysql_connection", return_value=FakeConnection("db")), \
                    patch.object(module, "validate_it_connection"), \
                    patch.object(module, "run_maven_it", return_value=(0, Path(temp_dir) / "x.log")) as runner, \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                self.assertEqual(0, module.main())
                self.assertEqual(expected, runner.call_args.kwargs["it_tests"])

    def test_maven_stops_when_ssh_tunnel_ownership_is_lost(self):
        class FakeMavenProcess:
            def __init__(self):
                self.stdout = io.StringIO("")
                self.stderr = io.StringIO("")
                self.terminated = False

            def wait(self, timeout=None):
                return 0

            def terminate(self):
                self.terminated = True

            def kill(self):
                return None

        process = FakeMavenProcess()
        run_values = {
            "F1_IT_DATABASE": "f1_it_0123456789abcdef",
            "F1_IT_DB_USERNAME": "f1_it_0123456789abcdef",
            "F1_IT_DB_PASSWORD": "app-secret",
            "F1_IT_RUN_ID": "a" * 32,
        }
        with tempfile.TemporaryDirectory() as temp_dir, patch.object(
            module.subprocess, "Popen", return_value=process
        ), patch.object(
            module, "_assert_tunnel_active", side_effect=module.SafeSetupError("tunnel lost")
        ), self.assertRaises(module.SafeSetupError):
            module.run_maven_it(Path(temp_dir), run_values, [], 43210, object())
        self.assertTrue(process.terminated)

    def test_tunnel_uses_strict_host_key_and_never_passes_ssh_password_in_args_or_env(self):
        class FakeProcess:
            def __init__(self, **kwargs):
                self.pid = 24680
                self.stderr = io.BytesIO(b"")
                self.returncode = None

            def poll(self):
                return self.returncode

            def terminate(self):
                self.returncode = 0

            def wait(self, timeout=None):
                return self.returncode

        config = {
            "SERVER_IP": "203.0.113.1",
            "SERVER_USER": "runner",
            "SERVER_PASSWORD": "ssh-secret-value",
            "MVP_SOURCE_MYSQL_PORT": "3306",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            ssh_dir = home / ".ssh"
            ssh_dir.mkdir(mode=0o700)
            known_hosts = ssh_dir / "known_hosts"
            known_hosts.write_text("203.0.113.1 ssh-ed25519 AAAA\n", encoding="utf-8")
            known_hosts.chmod(0o600)
            popen_calls = []

            def fake_popen(command, **kwargs):
                popen_calls.append((command, kwargs))
                return FakeProcess(**kwargs)

            def fake_run(command, **kwargs):
                return type("Completed", (), {"returncode": 0, "stdout": "24680\n"})()

            with (
                patch.object(module.Path, "home", return_value=home),
                patch.object(module.shutil, "which", side_effect=lambda name: f"/usr/bin/{name}"),
                patch.object(module.subprocess, "run", side_effect=fake_run),
                patch.object(module.subprocess, "Popen", side_effect=fake_popen),
                patch.object(module, "_capture_stream", return_value=None),
                patch.object(module, "_probe_mysql_tunnel", return_value=True),
            ):
                with module.ssh_mysql_tunnel(config, home, ["ssh-secret-value"]) as (port, process):
                    self.assertEqual(24680, process.pid)
                    self.assertGreater(port, 0)
                    command, kwargs = popen_calls[0]
                    self.assertIn("StrictHostKeyChecking=yes", command)
                    self.assertIn("ConnectTimeout=10", command)
                    self.assertIn("PreferredAuthentications=password", command)
                    self.assertIn("NumberOfPasswordPrompts=1", command)
                    self.assertIn("SSH_ASKPASS_REQUIRE", kwargs["env"])
                    self.assertEqual("force", kwargs["env"]["SSH_ASKPASS_REQUIRE"])
                    self.assertEqual(module.subprocess.DEVNULL, kwargs["stdin"])
                    self.assertNotIn("ssh-secret-value", " ".join(command))
                    self.assertNotIn("ssh-secret-value", " ".join(kwargs["env"].values()))
                    self.assertTrue(any(f"127.0.0.1:{port}:127.0.0.1:3306" == arg for arg in command))
                    auth_file = next(home.glob("ssh-auth-*.secret"))
                    helper_file = next(home.glob("ssh-askpass-*.py"))
                    self.assertEqual(0o600, auth_file.stat().st_mode & 0o777)
                    self.assertEqual(0o700, helper_file.stat().st_mode & 0o777)
                    self.assertEqual("ssh-secret-value", auth_file.read_text(encoding="utf-8"))
                    compile(helper_file.read_text(encoding="utf-8"), str(helper_file), "exec")
                self.assertFalse(list(home.glob("ssh-auth-*.secret")))
                self.assertFalse(list(home.glob("ssh-askpass-*.py")))

    def test_rejects_foreign_listener_before_mysql_greeting_probe(self):
        class FakeProcess:
            pid = 24680

            def __init__(self, **kwargs):
                self.stderr = io.BytesIO(b"")
                self.returncode = None
                self.terminated = False

            def poll(self):
                return self.returncode

            def terminate(self):
                self.terminated = True
                self.returncode = 0

            def wait(self, timeout=None):
                return self.returncode

        config = {
            "SERVER_IP": "203.0.113.1",
            "SERVER_USER": "runner",
            "SERVER_PASSWORD": "ssh-secret-value",
            "MVP_SOURCE_MYSQL_PORT": "3306",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            ssh_dir = home / ".ssh"
            ssh_dir.mkdir(mode=0o700)
            known_hosts = ssh_dir / "known_hosts"
            known_hosts.write_text("203.0.113.1 ssh-ed25519 AAAA\n", encoding="utf-8")
            known_hosts.chmod(0o600)
            processes = []

            def fake_popen(command, **kwargs):
                process = FakeProcess(**kwargs)
                processes.append(process)
                return process

            def fake_run(command, **kwargs):
                output = "90001\n" if "lsof" in command[0] else ""
                return type("Completed", (), {"returncode": 0, "stdout": output})()

            with (
                patch.object(module.Path, "home", return_value=home),
                patch.object(module.shutil, "which", side_effect=lambda name: f"/usr/bin/{name}"),
                patch.object(module.subprocess, "run", side_effect=fake_run),
                patch.object(module.subprocess, "Popen", side_effect=fake_popen),
                patch.object(module, "_capture_stream"),
                patch.object(module, "_probe_mysql_tunnel", return_value=True) as probe,
                self.assertRaises(module.SafeSetupError),
                module.ssh_mysql_tunnel(config, home),
            ):
                self.fail("foreign listener must be rejected")
            probe.assert_not_called()
            self.assertEqual(1, len(processes))
            self.assertTrue(processes[0].terminated)


if __name__ == "__main__":
    unittest.main()
