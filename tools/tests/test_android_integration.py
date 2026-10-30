"""Safety and result accounting; never connects to an Android device."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import sys
import tempfile
import contextlib
import io
import json
import subprocess

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

SPEC = importlib.util.spec_from_file_location("android_integration", Path(__file__).resolve().parents[1] / "run-android-integration.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)

class AndroidRunnerTest(unittest.TestCase):
    def setUp(self):
        self.test_folder = Path(__file__).resolve().parents[2] / "app/build/tool-tests"
        self.test_folder.mkdir(parents=True, exist_ok=True)

    def test_cli_requires_explicit_execution_scope_before_prepare(self):
        with patch.object(sys, "argv", ["runner", "--serial", "emulator-5554", "--prepare"]), \
             patch.object(RUNNER, "prepare_build") as prepare, patch.object(RUNNER, "run") as run, \
             patch.object(RUNNER, "android_inventory") as inventory, contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(RUNNER.main(), 1)
        self.assertIn("--test or --all", errors.getvalue())
        prepare.assert_not_called(); run.assert_not_called(); inventory.assert_not_called()

    def test_cli_all_and_read_only_listing_preserve_full_inventory(self):
        for arguments in [["--serial", "emulator-5554", "--all"], ["--list"]]:
            with self.subTest(arguments=arguments), patch.object(sys, "argv", ["runner", *arguments]), \
                 patch.object(RUNNER, "android_inventory", return_value=["p.One#one"]), \
                 patch.object(RUNNER, "run") as run, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(RUNNER.main(), 0)
                if "--all" in arguments: self.assertEqual(run.call_args.args[2], ["p.One#one"])
                else: run.assert_not_called()

    def test_cli_test_all_and_plan_are_mutually_exclusive(self):
        for arguments in [["--test", "p.One", "--all"], ["--plan", "synthetic.json", "--all"],
                          ["--plan", "synthetic.json", "--test", "p.One"]]:
            with self.subTest(arguments=arguments), patch.object(sys, "argv", ["runner", *arguments]), \
                 patch.object(RUNNER, "prepare_build") as prepare, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error: RUNNER.main()
                self.assertEqual(error.exception.code, 2)
                prepare.assert_not_called()

    def test_powershell_rejects_missing_or_conflicting_scope_before_environment_setup(self):
        import shutil
        powershell = shutil.which("pwsh") or shutil.which("powershell")
        if not powershell: self.skipTest("PowerShell is unavailable")
        with tempfile.TemporaryDirectory(dir=self.test_folder) as folder:
            script = Path(folder) / "run-android-tests.ps1"
            script.write_text((Path(__file__).resolve().parents[1] / script.name).read_text(), encoding="utf-8")
            (script.parent / "codex-env.ps1").write_text("throw 'environment setup unexpectedly reached'", encoding="utf-8")
            for arguments in [[], ["-All", "-TestClass", "p.One"]]:
                with self.subTest(arguments=arguments):
                    result = subprocess.run([powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script),
                                             "-Serial", "emulator-5554", *arguments], capture_output=True, text=True,
                                            encoding="utf-8", errors="replace", timeout=30)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("TestClass", result.stderr)
                    self.assertNotIn("environment setup unexpectedly reached", result.stderr)

    @contextlib.contextmanager
    def isolated_execution(self, root, process, install_error=None):
        def tool(arguments, timeout=60):
            if "install" in arguments:
                if install_error: raise RuntimeError(install_error)
                return "Success"
            if "badging" in arguments:
                package = "com.zsz.studyassistant.integration" + (".test" if "androidTest" in str(arguments[-1]) else "")
                return f"package: name='{package}'\nsdkVersion:'36'"
            if "xmltree" in arguments: return 'android:targetPackage="com.zsz.studyassistant.integration"'
            return {"ro.kernel.qemu": "1", "ro.build.version.sdk": "36", "ro.build.fingerprint": "synthetic",
                    "ro.product.cpu.abi": "x86_64"}[arguments[-1]]
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(RUNNER, "ROOT", root))
            stack.enter_context(patch.dict(RUNNER.os.environ, {"ANDROID_HOME": "synthetic-sdk", "CI": "true"}))
            stack.enter_context(patch.object(RUNNER, "checked", side_effect=tool))
            stack.enter_context(patch.object(RUNNER, "load_build", return_value={"targetSha256": "a"*64, "testSha256": "b"*64}))
            stack.enter_context(patch.object(RUNNER, "git", return_value="c"*40))
            stack.enter_context(patch.object(RUNNER, "android_inventory", return_value=["p.One#one"]))
            stack.enter_context(patch.object(RUNNER, "execution_identity", return_value="d"*64))
            command = stack.enter_context(patch.object(RUNNER.subprocess, "run", side_effect=[process]))
            output = stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
            yield command, output

    def assert_failure_files(self, root, stage, expected_text):
        report = json.loads((root / "app/build/android-integration/evidence-api-36.json").read_text())
        self.assertFalse(report["success"])
        self.assertEqual(report["stage"], stage)
        self.assertEqual(report["passed"], [])
        public = (root / "app/build/android-integration/instrumentation.log").read_text()
        logs = list((root / ".local-notes/verification/android").glob("*.log"))
        self.assertEqual(len(logs), 1)
        self.assertEqual(logs[0].read_text(), public)
        self.assertIn(expected_text, public)

    def test_instrumentation_timeout_retains_partial_bytes_and_failed_evidence(self):
        partial = b"INSTRUMENTATION_STATUS: test=one\nINSTRUMENTATION_STATUS_CODE: 1\n"
        with tempfile.TemporaryDirectory(dir=self.test_folder) as folder:
            root = Path(folder)
            error = subprocess.TimeoutExpired(["do-not-print-command"], 600, output=partial, stderr=b"synthetic timeout detail\n")
            with self.isolated_execution(root, error) as (command, output):
                with self.assertRaises(RuntimeError): RUNNER.run("emulator-5554")
                self.assertEqual(command.call_count, 1)
            self.assert_failure_files(root, "instrumentation", "synthetic timeout detail")
            self.assertIn(partial.decode(), (root / "app/build/android-integration/instrumentation.log").read_text())
            self.assertIn("test=one", output.getvalue())
            self.assertNotIn("do-not-print-command", output.getvalue())

    def test_instrumentation_nonzero_and_bad_summary_stay_failed_with_logs(self):
        for result, stage in [(subprocess.CompletedProcess([], 1, "synthetic process failure\n", ""), "instrumentation"),
                              (subprocess.CompletedProcess([], 0, "FAILURES!!!\nTests run: 1, Failures: 1\n", ""), "result-validation")]:
            with self.subTest(stage=stage), tempfile.TemporaryDirectory(dir=self.test_folder) as folder:
                root = Path(folder)
                with self.isolated_execution(root, result) as (command, output):
                    with self.assertRaises((RuntimeError, ValueError)): RUNNER.run("emulator-5554")
                    self.assertEqual(command.call_count, 1)
                self.assert_failure_files(root, stage, result.stdout)
                self.assertIn(result.stdout.strip().splitlines()[-1], output.getvalue())

    def test_timeout_and_nonzero_failure_block_older_success_reuse(self):
        for process in [subprocess.TimeoutExpired(["synthetic-command"], 600, output=b"partial\n"),
                        subprocess.CompletedProcess([], 1, "synthetic failure\n", "")]:
            with self.subTest(process=type(process).__name__), tempfile.TemporaryDirectory(dir=self.test_folder) as folder:
                root = Path(folder)
                store = RUNNER.EvidenceStore(root / ".local-notes/verification/android")
                prior = store.begin("d"*64, ["p.One#one"])
                (store.folder / prior["log"]).write_text("synthetic prior pass", encoding="utf-8")
                store.finish(prior, True)
                self.assertIsNotNone(store.find("d"*64, ["p.One#one"]))
                with self.isolated_execution(root, process):
                    with self.assertRaises(RuntimeError): RUNNER.run("emulator-5554")
                self.assertIsNone(store.find("d"*64, ["p.One#one"]))

    def test_install_failure_records_stage_without_running_instrumentation(self):
        with tempfile.TemporaryDirectory(dir=self.test_folder) as folder:
            root = Path(folder)
            with self.isolated_execution(root, None, "adb exit=1: INSTALL_FAILED_TEST_ONLY") as (command, output):
                with self.assertRaises(RuntimeError): RUNNER.run("emulator-5554")
                command.assert_not_called()
            self.assert_failure_files(root, "install-target", "INSTALL_FAILED_TEST_ONLY")
            self.assertIn("install-target", output.getvalue())

    def test_android_tool_failure_diagnostic_redacts_arguments_paths_and_tokens(self):
        sensitive = "ghp_" + "x"*36
        stderr = f"INSTALL_FAILED_TEST_ONLY C:\\private\\fixture.apk /private/fixture.apk https://example.test/?token=secret token={sensitive}"
        result = subprocess.CompletedProcess([], 7, "", stderr)
        with patch.object(RUNNER.subprocess, "run", return_value=result):
            with self.assertRaises(RuntimeError) as error:
                RUNNER.checked(["synthetic-sdk/adb", "private-command-argument"])
        diagnostic = str(error.exception)
        self.assertIn("adb", diagnostic); self.assertIn("exit=7", diagnostic)
        self.assertIn("INSTALL_FAILED_TEST_ONLY", diagnostic)
        for value in [sensitive, "private-command-argument", "private", "example.test", "token=secret"]:
            self.assertNotIn(value, diagnostic)

    def test_android_tool_timeout_never_prints_command_arguments(self):
        error = subprocess.TimeoutExpired(["synthetic-sdk/aapt", "do-not-print-argument"], 60,
                                          stderr=b"synthetic timeout /private/fixture.apk\n")
        with patch.object(RUNNER.subprocess, "run", side_effect=error):
            with self.assertRaises(RuntimeError) as raised: RUNNER.checked(error.cmd)
        diagnostic = str(raised.exception)
        self.assertIn("aapt", diagnostic); self.assertIn("after=60s", diagnostic)
        self.assertIn("synthetic timeout", diagnostic)
        self.assertNotIn("do-not-print-argument", diagnostic)
        self.assertNotIn("private", diagnostic)

    def test_existing_apks_reject_changed_source_or_bytes(self):
        self.assertTrue(hasattr(RUNNER, "record_build"), "APK provenance is not implemented")
        folder = Path(__file__).resolve().parents[2] / "app/build/tool-tests"
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            root = Path(tmp)
            for relative in RUNNER.APK_PATHS:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"synthetic")
            with patch.object(RUNNER, "ROOT", root), patch.object(RUNNER, "source_digest", return_value="a"*64), patch.object(RUNNER, "git", return_value="b"*40):
                RUNNER.record_build()
                RUNNER.load_build()
                (root / RUNNER.APK_PATHS[0]).write_bytes(b"changed")
                with self.assertRaises(ValueError): RUNNER.load_build()

    def test_physical_serial_is_rejected_before_any_tool(self):
        with patch.object(RUNNER, "checked") as tool:
            with self.assertRaises(ValueError): RUNNER.run("physical-fixture", 16)
            tool.assert_not_called()
    def test_non_emulator_is_rejected_before_install(self):
        with patch.dict(RUNNER.os.environ, {"ANDROID_HOME": "synthetic-sdk"}), patch.object(RUNNER, "checked", return_value="0") as tool:
            with self.assertRaises(RuntimeError): RUNNER.run("emulator-5554", 16)
            self.assertEqual(tool.call_count, 1)
            self.assertNotIn("install", tool.call_args.args[0])
    def test_wrong_package_is_rejected_before_install(self):
        with patch.dict(RUNNER.os.environ, {"ANDROID_HOME": "synthetic-sdk"}), patch.object(RUNNER, "checked", side_effect=["1", "package: name='com.zsz.studyassistant'"]) as tool:
            with self.assertRaises(RuntimeError): RUNNER.run("emulator-5554", 16)
            self.assertEqual(tool.call_count, 2)
    def test_missing_failed_or_incomplete_suite_is_not_success(self):
        for text in ["", "OK (0 tests)", "OK (15 tests)", "OK (16 tests)\nFAILURES!!!"]:
            with self.assertRaises(RuntimeError): RUNNER.passed_count(text, 16)
        self.assertEqual(RUNNER.passed_count("OK (16 tests)\n", 16), 16)

    def test_below_actual_apk_min_sdk_is_rejected_before_install(self):
        replies = ["1", "package: name='com.zsz.studyassistant.integration'\nsdkVersion:'36'",
                   "package: name='com.zsz.studyassistant.integration.test'\nsdkVersion:'36'",
                   'android:targetPackage="com.zsz.studyassistant.integration"', "35"]
        with patch.dict(RUNNER.os.environ, {"ANDROID_HOME": "synthetic-sdk"}), patch.object(RUNNER, "checked", side_effect=replies) as tool, \
             patch.object(RUNNER, "load_build", return_value={}), patch.object(RUNNER, "android_inventory", return_value=["p.One#one"]):
            with self.assertRaisesRegex(ValueError, "below actual APK minSdk"): RUNNER.run("emulator-5554")
            self.assertFalse(any("install" in call.args[0] for call in tool.call_args_list))

    def test_runner_and_parser_edits_expire_execution_evidence_without_expiring_apks(self):
        import subprocess
        from verification import EvidenceStore
        folder = Path(__file__).resolve().parents[2] / "app/build/tool-tests"
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            for name in [*RUNNER.APK_PATHS, "app/src/main/Main.kt", "tools/run-android-integration.py", "tools/verification.py", "tools/unrelated.py"]:
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text("synthetic")
            report = dict(api=36, fingerprint="synthetic", abi="x86_64")
            with patch.object(RUNNER, "ROOT", root), patch.object(RUNNER, "git", return_value="a"*40):
                build = RUNNER.record_build()
                identity = RUNNER.execution_identity(build, report)
                store = EvidenceStore(root / "evidence")
                record = store.begin(identity, ["p.One#one"])
                (store.folder / record["log"]).write_text("synthetic pass")
                store.finish(record, True)
                self.assertIsNotNone(store.find(identity, ["p.One#one"]))
                (root / "tools/unrelated.py").write_text("unrelated edit")
                self.assertEqual(RUNNER.execution_identity(build, report), identity)
                for name in ["tools/run-android-integration.py", "tools/verification.py"]:
                    path = root / name; path.write_text("changed implementation")
                    self.assertEqual(RUNNER.load_build(), build)
                    changed = RUNNER.execution_identity(build, report)
                    self.assertNotEqual(changed, identity)
                    self.assertIsNone(store.find(changed, ["p.One#one"]))
                    path.write_text("synthetic")

    def test_ci_source_recalculation_has_complete_checkout_history(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/build.yml").read_text()
        gate = workflow.split("  verification:\n", 1)[1]
        checkout = gate.split("uses: actions/checkout@v4", 1)[1].split("- uses:", 1)[0]
        self.assertIn("fetch-depth: 0", checkout)
        ci = (Path(__file__).resolve().parents[1] / "ci-verification.py").read_text()
        self.assertIn('current = make_plan(base=value.get("base"))', ci)
