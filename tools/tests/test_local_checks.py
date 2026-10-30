"""Local result reuse tests: real subprocesses, synthetic output, no Gradle or Android."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))


class LocalChecksTest(unittest.TestCase):
    def test_subset_failure_invalidates_an_older_full_result(self):
        from local_checks import run_step
        from verification import EvidenceStore
        folder = TOOLS.parent / "app/build/tool-tests"
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            root = Path(tmp)
            store = EvidenceStore(root / "evidence")
            cmd = [sys.executable, "-c", "print('synthetic')"]
            run_step(root, store, "gradle", cmd, "full", family="same-source")
            with self.assertRaises(RuntimeError):
                run_step(root, store, "gradle", [sys.executable, "-c", "raise SystemExit(7)"], "subset", family="same-source")
            self.assertFalse(run_step(root, store, "gradle", cmd, "full", family="same-source")["reused"])

    def test_output_change_and_source_drift_invalidate_evidence(self):
        from local_checks import run_step
        from verification import EvidenceStore
        folder = TOOLS.parent / "app/build/tool-tests"
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            root = Path(tmp)
            store = EvidenceStore(root / "evidence")
            output = root / "generated"
            cmd = [sys.executable, "-c", "from pathlib import Path; Path('generated').write_text('current')"]
            run_step(root, store, "compile", cmd, "inputs", outputs=[output])
            output.write_text("stale")
            self.assertFalse(run_step(root, store, "compile", cmd, "inputs", outputs=[output])["reused"])
            with self.assertRaises(RuntimeError):
                run_step(root, store, "compile", cmd, "inputs", force=True, unchanged=lambda: False)
            self.assertIsNone(store.find("inputs", ["compile"]))

    def test_successful_stderr_is_not_an_error(self):
        from local_checks import run_step
        from verification import EvidenceStore
        folder = TOOLS.parent / "app/build/tool-tests"
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            result = run_step(Path(tmp), EvidenceStore(Path(tmp) / "evidence"), "stderr",
                              [sys.executable, "-c", "import sys; print('synthetic',file=sys.stderr)"], "inputs")
            self.assertEqual(result["exitCode"], 0)

    def test_reuse_and_later_failure_never_resurrect_an_old_pass(self):
        self.assertIsNotNone(importlib.util.find_spec("local_checks"), "Local reuse is not implemented")
        from local_checks import run_step
        from verification import EvidenceStore
        folder = TOOLS.parent / "app/build/tool-tests"
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            root = Path(tmp)
            store = EvidenceStore(root / "evidence")
            cmd = [sys.executable, "-B", "-c", "from pathlib import Path; p=Path('calls'); p.write_text(p.read_text()+'x' if p.exists() else 'x')"]
            first = run_step(root, store, "tool", cmd, "inputs")
            second = run_step(root, store, "tool", cmd, "inputs")
            self.assertFalse(first["reused"])
            self.assertTrue(second["reused"])
            self.assertEqual((root / "calls").read_text(), "x")
            # Same action/input identity, but a forced fresh attempt fails (e.g. environmental fault).
            with self.assertRaises(RuntimeError):
                run_step(root, store, "tool", [sys.executable, "-c", "raise SystemExit(7)"], "inputs", force=True)
            self.assertFalse(run_step(root, store, "tool", cmd, "inputs")["reused"])
            self.assertEqual((root / "calls").read_text(), "xx")

    def test_modified_log_prevents_reuse(self):
        self.assertIsNotNone(importlib.util.find_spec("local_checks"), "Local reuse is not implemented")
        from local_checks import run_step
        from verification import EvidenceStore
        folder = TOOLS.parent / "app/build/tool-tests"
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            root = Path(tmp)
            store = EvidenceStore(root / "evidence")
            cmd = [sys.executable, "-c", "print('synthetic')"]
            first = run_step(root, store, "tool", cmd, "inputs")
            (store.folder / first["log"]).write_text("changed")
            self.assertFalse(run_step(root, store, "tool", cmd, "inputs")["reused"])

    def test_basic_never_duplicates_ci_tests_or_compilation(self):
        from unittest.mock import patch
        import local_checks as local
        for files in (["app/src/androidTest/java/p/OneTest.kt", "README.md", "tools/example.py"],
                      ["app/src/integration/java/p/Host.kt"], ["app/src/main/Main.kt"],
                      ["app/src/test/java/p/OneTest.kt"], ["README.md"]):
            with patch.object(local, "changed_files", return_value=("a"*40, files)), patch.object(local, "git", return_value="b"*40):
                plan = local.plan_checks(Path("synthetic"))
            self.assertEqual(plan["tasks"], [])
            self.assertEqual(plan["steps"], ["diff", "diff-staged", "credentials"])
            self.assertEqual(plan["testOwner"], "ci")

    def test_basic_scopes_changed_jvm_classes_and_keeps_unknown_helpers_full(self):
        from unittest.mock import patch
        import local_checks as local
        folder = TOOLS.parent / "app/build/tool-tests"; folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            root = Path(tmp)
            name = "app/src/test/java/p/ChangedTest.kt"
            path = root / name; path.parent.mkdir(parents=True)
            path.write_text("package p\nclass ChangedTest { @Test fun checkIt() {} }", encoding="utf-8")
            def plan(paths, selectors=()):
                with patch.object(local, "changed_files", return_value=("a"*40, paths)), patch.object(local, "git", return_value="b"*40):
                    return local.plan_checks(root, selectors=selectors)
            self.assertEqual(plan([name])["recommendedSelectors"], ["p.ChangedTest"])
            self.assertEqual(plan([name])["selectors"], [])
            self.assertEqual(plan([name], ["p.Explicit"])["selectors"], ["p.Explicit"])
            self.assertIsNone(plan([name, "app/src/test/java/p/SharedFixture.kt"])["recommendedSelectors"])
            path.unlink()
            self.assertIsNone(plan([name])["recommendedSelectors"])

    def test_explicit_local_diagnostics_do_not_expand_to_other_ci_suites(self):
        from unittest.mock import patch
        import local_checks as local
        with patch.object(local,"changed_files",return_value=("a"*40,["app/src/androidTest/p/DeviceTest.kt"])),patch.object(local,"git",return_value="b"*40):
            plan=local.plan_checks(Path("synthetic"),selectors=["p.OnlyThisTest"])
            self.assertEqual(plan["tasks"],["testDebugUnitTest"])
            self.assertNotIn("tool-tests",plan["steps"])
            self.assertEqual(plan["testOwner"],"local-diagnostic")
            full=local.plan_checks(Path("synthetic"),mode="Full")
            self.assertIn("tool-tests",full["steps"])
            self.assertTrue(full["sqlite"])

    def test_tool_identity_is_independent_of_gradle_environment_and_version_fields(self):
        from unittest.mock import patch
        import local_checks as local
        self.assertTrue(hasattr(local, "tool_environment_identity"))
        before = local.tool_environment_identity()
        with patch.dict("os.environ", {"JAVA_HOME": "different-jdk", "ANDROID_HOME": "different-sdk", "GRADLE_USER_HOME": "different-cache"}):
            self.assertEqual(local.tool_environment_identity(), before)
        self.assertNotIn("app/build.gradle.kts", local.TOOL_INPUTS)

    def test_tool_identity_tracks_pwsh_resolution_and_binary_changes(self):
        from unittest.mock import patch
        import local_checks as local
        folder = TOOLS.parent / "app/build/tool-tests"; folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            first = Path(tmp) / "pwsh-a"; second = Path(tmp) / "pwsh-b"
            first.write_bytes(b"version-one"); second.write_bytes(b"version-two")
            def identity(path):
                with patch.object(local.shutil, "which", side_effect=lambda name: str(path) if name == "pwsh" else None):
                    return local.tool_environment_identity()
            before = identity(first)
            self.assertNotEqual(before, identity(second))
            first.write_bytes(b"updated-in-place")
            self.assertNotEqual(before, identity(first))

    def test_scoped_inputs_ignore_docs_jvm_and_android_tests_for_main_compile(self):
        from verification import task_inputs, source_digest
        import subprocess
        with tempfile.TemporaryDirectory(dir=TOOLS.parent / "app/build/tool-tests") as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            files = ["README.md", "app/src/main/Main.kt", "app/src/test/Jvm.kt", "app/src/androidTest/Device.kt", "docs/readme/README.en.md"]
            for name in files:
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text("initial")
            jvm_before = source_digest(root, task_inputs(["testDebugUnitTest"]))
            compile_before = source_digest(root, task_inputs(["compileDebugKotlin"]))
            apk_before = source_digest(root, task_inputs(["assembleIntegrationAndroidTest"]))
            for name in [files[0], files[2], files[4]]:
                (root / name).write_text("changed")
                self.assertNotEqual(source_digest(root, task_inputs(["testDebugUnitTest"])), jvm_before)
                self.assertEqual(source_digest(root, task_inputs(["compileDebugKotlin"])), compile_before)
                self.assertEqual(source_digest(root, task_inputs(["assembleIntegrationAndroidTest"])), apk_before)
                (root / name).write_text("initial")
            (root / files[3]).write_text("changed")
            self.assertEqual(source_digest(root, task_inputs(["compileDebugKotlin"])), compile_before)
            self.assertNotEqual(source_digest(root, task_inputs(["assembleIntegrationAndroidTest"])), apk_before)
            (root / files[1]).write_text("changed")
            self.assertNotEqual(source_digest(root, task_inputs(["compileDebugKotlin"])), compile_before)

    def test_compile_output_directory_is_required_and_bound(self):
        from local_checks import run_step
        from verification import EvidenceStore
        with tempfile.TemporaryDirectory(dir=TOOLS.parent / "app/build/tool-tests") as tmp:
            root = Path(tmp); store = EvidenceStore(root / "evidence"); output = root / "classes"
            cmd = [sys.executable, "-c", "from pathlib import Path; p=Path('classes/A.class'); p.parent.mkdir(exist_ok=True); p.write_text('fresh')"]
            run_step(root, store, "gradle-compile", cmd, "inputs", outputs=[output])
            self.assertTrue(run_step(root, store, "gradle-compile", cmd, "inputs", outputs=[output])["reused"])
            (output / "A.class").unlink()
            self.assertFalse(run_step(root, store, "gradle-compile", cmd, "inputs", outputs=[output])["reused"])

    def test_basic_jvm_selector_preserves_changed_device_test_compilation(self):
        from unittest.mock import patch
        import local_checks as local
        for path in ["app/src/androidTest/java/p/OneTest.kt", "app/src/integration/java/p/Host.kt"]:
            with patch.object(local, "changed_files", return_value=("a"*40, [path, "README.md"])), patch.object(local, "git", return_value="b"*40):
                plan = local.plan_checks(Path("synthetic"), selectors=["p.JvmTest"])
                self.assertEqual(plan["tasks"], ["testDebugUnitTest", "compileIntegrationAndroidTestKotlin"])
                self.assertEqual(plan["selectors"], ["p.JvmTest"])
