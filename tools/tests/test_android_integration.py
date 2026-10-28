"""Safety and result accounting; never connects to an Android device."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

SPEC = importlib.util.spec_from_file_location("android_integration", Path(__file__).resolve().parents[1] / "run-android-integration.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)

class AndroidRunnerTest(unittest.TestCase):
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
