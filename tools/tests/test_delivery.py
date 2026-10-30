"""Small offline regression for the current delivery contract. Never dispatched by CI."""
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import local_checks
import release
from release_common import apk_payload, extract_candidate, sha256, validate_manifest


class DeliveryTest(unittest.TestCase):
    def manifest(self):
        return dict(schema=3, package="com.zsz.studyassistant", version="0.6.8.7", versionCode=88,
                    minSdk=36, targetSdk=37, commit="a"*40, buildUtc="20261030120000",
                    candidate="StudyAssistant-0.6.8.7-20261030120000-candidate.apk",
                    candidateSha256="b"*64, payloadSha256="c"*64, expectedSigner="d"*64)

    def test_default_local_plan_does_not_compile_or_test(self):
        self.assertEqual([], local_checks.plan_checks()["tasks"])
        self.assertEqual(["compileDebugKotlin"], local_checks.plan_checks(compile_app=True)["tasks"])

    def test_targeted_test_owns_its_compilation_without_expanding_scope(self):
        plan = local_checks.plan_checks(["example.Test.method"], compile_app=True)
        self.assertEqual(["testIntegrationUnitTest"], plan["tasks"])
        self.assertEqual(["example.Test.method"], plan["selectors"])
        with self.assertRaises(ValueError): local_checks.plan_checks(["*"])

    def test_build_manifest_does_not_claim_test_success(self):
        value = self.manifest()
        self.assertEqual(value, validate_manifest(value))
        for extra in ({"jvm": {"tests": 1}}, {"planSha256": "e"*64}):
            with self.assertRaises(ValueError): validate_manifest(dict(value, **extra))

    def test_old_evidence_manifest_still_requires_real_test_totals(self):
        value = dict(self.manifest(), schema=2, planSha256="e"*64,
                     jvm=dict(tests=1, failures=0, errors=0, skipped=0))
        validate_manifest(value)
        value["jvm"]["failures"] = 1
        with self.assertRaises(ValueError): validate_manifest(value)

    def test_candidate_hash_and_payload_still_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp); apk = p / "synthetic.apk"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("assets/build-time-utc.txt", "20261030120000")
                z.writestr("classes.dex", b"synthetic, not installable")
            value = self.manifest()
            value.update(candidateSha256=sha256(apk), payloadSha256=apk_payload(apk)[0])
            def archive(manifest):
                out = io.BytesIO()
                with zipfile.ZipFile(out, "w") as z:
                    z.writestr("release-manifest.json", json.dumps(manifest))
                    z.writestr(manifest["candidate"], apk.read_bytes())
                return out.getvalue()
            self.assertEqual(value, extract_candidate(archive(value), p / "ok")[0])
            bad = dict(value, candidateSha256="f"*64)
            with self.assertRaises(ValueError): extract_candidate(archive(bad), p / "bad")

    def test_later_build_failure_cannot_reuse_an_old_success(self):
        jobs = [dict(id=1, name="build", conclusion="success"),
                dict(id=2, name="build", conclusion="failure")]
        with self.assertRaises(release.ReleaseError): release.required_jobs(jobs, [])

    def test_start_requires_merged_main_and_dispatches_only_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / "app").mkdir()
            (root / "app/build.gradle.kts").write_text('val appVersionName = "0.6.8.7"')
            calls = []
            class GitHub:
                def api(self, path, value=None, **kwargs):
                    calls.append((path, value))
                    return {"workflow_runs": []} if "runs?" in path else None
            r = release.Release(root, GitHub())
            branch = ["Develop"]
            def fake_git(*args, **kwargs):
                values = {("status", "--porcelain"): "", ("branch", "--show-current"): branch[0],
                          ("rev-parse", "HEAD"): "a"*40, ("log", "-1", "--format=%s"): "ci: simplify delivery"}
                return type("Result", (), {"stdout": values[args].encode(), "returncode": 0})()
            with patch.object(r, "git", side_effect=fake_git), patch.object(r, "remote", return_value="a"*40):
                with self.assertRaises(release.ReleaseError): r.start("0.6.8.7")
                self.assertEqual([], calls)
                branch[0] = "main"
                r.start("0.6.8.7"); r.start("0.6.8.7")
            dispatches = [v for p, v in calls if p.endswith("/dispatches")]
            self.assertEqual(1, len(dispatches))
            self.assertEqual({"expected_sha", "run_description"}, set(dispatches[0]["inputs"]))


if __name__ == "__main__": unittest.main()
