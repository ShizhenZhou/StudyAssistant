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

    def test_basic_test_only_selects_real_test_compilation(self):
        from unittest.mock import patch
        import local_checks as local
        for files, tasks in [(["app/src/androidTest/java/p/OneTest.kt", "README.md", "tools/example.py"], ["compileIntegrationAndroidTestKotlin"]),
                             (["app/src/integration/java/p/Host.kt", "docs/development.md"], ["compileIntegrationAndroidTestKotlin"]),
                             (["app/src/test/java/p/OneTest.kt"], ["testDebugUnitTest"]),
                             (["README.md"], [])]:
            with patch.object(local, "changed_files", return_value=("a"*40, files)), patch.object(local, "git", return_value="b"*40):
                plan = local.plan_checks(Path("synthetic"))
                self.assertEqual(plan["tasks"], tasks)
                self.assertIn("does not assert a cache hit", plan["reuse"])

    def test_scoped_inputs_ignore_docs_jvm_and_android_tests_for_main_compile(self):
        from verification import task_inputs, source_digest
        import subprocess
        with tempfile.TemporaryDirectory(dir=TOOLS.parent / "app/build/tool-tests") as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            files = ["README.md", "app/src/main/Main.kt", "app/src/test/Jvm.kt", "app/src/androidTest/Device.kt"]
            for name in files:
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text("initial")
            jvm_before = source_digest(root, task_inputs(["testDebugUnitTest"]))
            compile_before = source_digest(root, task_inputs(["compileDebugKotlin"]))
            apk_before = source_digest(root, task_inputs(["assembleIntegrationAndroidTest"]))
            for name in [files[0], files[2]]:
                (root / name).write_text("changed")
                self.assertNotEqual(source_digest(root, task_inputs(["testDebugUnitTest"])), jvm_before)
                self.assertEqual(source_digest(root, task_inputs(["compileDebugKotlin"])), compile_before)
                self.assertEqual(source_digest(root, task_inputs(["assembleIntegrationAndroidTest"])), apk_before)
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
