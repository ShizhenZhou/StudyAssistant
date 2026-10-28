"""Verification policy and evidence tests, using only synthetic source and reports."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))


class VerificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.find_spec("verification")
        cls.v = __import__("verification") if spec else None

    def api(self):
        self.assertIsNotNone(self.v, "Shared verification policy is not implemented")
        return self.v

    def test_unknown_and_missing_baseline_require_both_full_suites(self):
        v = self.api()
        inventory = ["p.A#one", "p.B#two"]
        for files, known in [(["app/src/main/java/NewFeature.kt"], True), ([], False)]:
            plan = v.device_plan(files, inventory, known)
            self.assertEqual([(r["api"], r["tests"]) for r in plan], [(35, inventory), (36, inventory)])

    def test_docs_do_not_require_device_but_notifications_cover_both_paths(self):
        v = self.api()
        inventory = ["com.zsz.studyassistant.data.UpdateDownloadNotificationsIntegrationTest#done", "p.Unrelated#one"]
        self.assertEqual(v.device_plan(["docs/development.md"], inventory, True), [])
        plan = v.device_plan(["app/src/main/java/com/zsz/studyassistant/data/UpdateDownloadNotifications.kt"], inventory, True)
        self.assertEqual([r["api"] for r in plan], [35, 36])
        self.assertEqual(plan[0]["tests"], inventory[:1])

    def test_download_shared_fixture_keeps_page_recovery_and_notification_consumers(self):
        v = self.api()
        inventory = ["com.zsz.studyassistant.data.UpdateDownloadsIntegrationTest#one",
                     "com.zsz.studyassistant.data.UpdateDownloadNotificationsIntegrationTest#two",
                     "com.zsz.studyassistant.ui.UiSmokeTest#updatePageRestoresWaitingAndCompletedTasksAfterLeaving"]
        for path in ["app/src/main/java/com/zsz/studyassistant/data/UpdateDownloads.kt",
                     "app/src/androidTest/java/com/zsz/studyassistant/data/UpdateDownloadsIntegrationTest.kt"]:
            plan = v.device_plan([path], inventory, True)
            self.assertEqual(plan[0]["tests"], sorted(inventory))

    def test_inventory_and_selection_reject_typos_and_missing_methods(self):
        v = self.api()
        folder = TOOLS.parent / "app/build/tool-tests"
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            root = Path(tmp)
            folder = root / "app/src/androidTest/java/p"
            folder.mkdir(parents=True)
            (folder / "OneTest.kt").write_text('package p\nclass OneTest {\n@Test fun first() {}\n@Test fun second() {}\n}', encoding="utf-8")
            inventory = v.android_inventory(root)
            self.assertEqual(inventory, ["p.OneTest#first", "p.OneTest#second"])
            self.assertEqual(v.select_tests(inventory, ["p.OneTest"]), inventory)
            self.assertEqual(v.select_tests(inventory, ["p.OneTest#second"]), inventory[1:])
            for bad in ["p.Missing", "p.OneTest#typo", ""]:
                with self.assertRaises(ValueError): v.select_tests(inventory, [bad])

    def test_result_identity_is_checked_not_only_count(self):
        v = self.api()
        log = "INSTRUMENTATION_STATUS: class=p.A\nINSTRUMENTATION_STATUS: test=one\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n"
        self.assertEqual(v.instrumentation_results(log, ["p.A#one"]), {"passed": ["p.A#one"], "skipped": []})
        for changed in [log.replace("test=one", "test=other"), log.replace("CODE: 0", "CODE: -3"), log + "INSTRUMENTATION_FAILED", log + log]:
            with self.assertRaises(ValueError): v.instrumentation_results(changed, ["p.A#one"])

    def test_later_failure_blocks_older_full_success(self):
        v = self.api()
        records = [dict(identity="same", tests=["a", "b"], success=True, order=1),
                   dict(identity="same", tests=["a"], success=False, order=2)]
        self.assertIsNone(v.reusable_record(records, "same", ["a"]))
        self.assertIsNotNone(v.reusable_record(records, "same", ["b"]))
        self.assertIsNone(v.reusable_record(records, "different", ["b"]))
        self.assertIsNone(v.reusable_record(records[:1], "same", ["missing"]))

    def test_gate_rejects_missing_wrong_artifact_skipped_and_foreign_run(self):
        v = self.api()
        plan = {"schema": 1, "commit": "a"*40, "devices": [{"api": 36, "tests": ["p.A#one"]}]}
        artifact = {"targetSha256": "b"*64, "testSha256": "c"*64}
        report = dict(schema=1, commit="a"*40, runId=42, attempt=1, api=36, fingerprint="synthetic", abi="x86_64",
                      **artifact, tests=["p.A#one"], passed=["p.A#one"], skipped=[], success=True)
        v.validate_device_evidence(plan, artifact, [report], 42, 2)
        for reports in [[], [dict(report, testSha256="d"*64)], [dict(report, runId=43)],
                        [dict(report, success=False)], [dict(report, skipped=["p.A#one"])],
                        [dict(report, attempt=3)], [report, report]]:
            with self.assertRaises(ValueError): v.validate_device_evidence(plan, artifact, reports, 42, 2)

    def test_ci_gate_binds_plan_candidate_and_jvm_reports(self):
        v = self.api()
        self.assertTrue(hasattr(v, "validate_ci_evidence"), "CI evidence gate is not implemented")
        plan = dict(schema=1, policy=1, commit="a"*40, base="b"*40, changedFiles=["README.md"], devices=[])
        manifest = dict(commit="a"*40, candidateSha256="c"*64, planSha256=v.digest(plan))
        build = dict(commit="a"*40, runId=42, attempt=1, planSha256=v.digest(plan), candidateSha256="c"*64,
                     jvm=dict(tests=3, failures=0, errors=0, skipped=0), apks=None)
        report = dict(schema=1, success=True, runId=42, attempt=1, plan=plan, build=build, devices=[])
        v.validate_ci_evidence(report, manifest, 42, 2)
        for bad in [dict(report, success=False), dict(report, runId=43),
                    dict(report, build=dict(build, candidateSha256="d"*64)),
                    dict(report, build=dict(build, jvm=dict(tests=0, failures=0, errors=0, skipped=0))),
                    dict(report, plan=dict(plan, devices=[dict(api=36, tests=["p.A#one"])]))]:
            with self.assertRaises(ValueError): v.validate_ci_evidence(bad, manifest, 42, 2)


if __name__ == "__main__": unittest.main()
