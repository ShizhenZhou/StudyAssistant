"""Staged releases use synthetic reports/APIs only; never run Gradle, sign, or contact GitHub."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import release
import verification as v


class StagedReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        source = self.root / "app/build.gradle.kts"
        source.parent.mkdir(parents=True)
        source.write_text('val appVersionName = "9.9.9"', encoding="utf-8")
        self.commit, self.before = "a" * 40, "b" * 40
        self.plan = dict(schema=1, policy=4, commit=self.commit, base=self.before, changedFiles=["README.md"],
                         policyPaths=["README.md"], inventory=["p.One#one"], platformMigration=False,
                         platformConstraints=v.platform_constraints(36), devices=[], mode="release")
        self.pre = dict(schema=1, runId=42, attempt=1, success=True, plan=self.plan, devices=[],
                        staged=dict(sourceBranch="Develop", mainBefore=self.before),
                        build=dict(commit=self.commit, runId=42, attempt=1, planSha256=v.digest(self.plan),
                                   candidateSha256=None, jvm=dict(tests=7, failures=0, errors=0, skipped=0), apks=None))
        self.jobs = [dict(id=i, name=name, run_attempt=1, conclusion="success", completed_at="2026-10-30T00:01:00Z")
                     for i, name in enumerate(("build", "Windows tool tests", "Pre-main verification gate"), 1)]
        self.status = dict(id=10, state="success", context=v.promotion_context(42, 1),
                           description=v.promotion_description(self.commit, 3),
                           target_url="https://github.com/ShizhenZhou/StudyAssistant/actions/runs/42/job/3",
                           created_at="2026-10-30T00:02:00Z")
        self.promotion = dict(schema=1, runId=42, commit=self.commit, mainSha=self.commit, mainBefore=self.before,
                              preMainSha256=v.digest(self.pre), gate=v.pre_main_gate(self.jobs, self.pre),
                              status=self.status, observedAt="2026-10-30T00:03:00Z")
        self.manifest = dict(commit=self.commit, candidateSha256="c" * 64, planSha256=v.digest(self.plan), minSdk=36)
        self.final = {key: value for key, value in self.pre.items() if key != "staged"}
        self.final.update(preMain=self.pre, promotion=self.promotion,
                          build=dict(self.pre["build"], candidateSha256=self.manifest["candidateSha256"]))

    def archive(self):
        value = io.BytesIO()
        with zipfile.ZipFile(value, "w") as out: out.writestr("verification.json", json.dumps(self.pre))
        return value.getvalue()

    def run_metadata(self):
        return dict(id=42, event="workflow_dispatch", head_branch="Develop", head_sha=self.commit,
                    status="in_progress", conclusion=None, display_title="release: fixture",
                    path=".github/workflows/build.yml", run_attempt=1)

    def coordinator(self):
        requests, refs, statuses = [], {"main": self.before, "Develop": self.commit}, []
        run = self.run_metadata()
        outer = self
        class GitHub:
            def api(self, path, value=None, **kwargs):
                requests.append((path, value))
                if path == "actions/runs/42": return run
                if "/jobs?" in path: return {"jobs": outer.jobs}
                if "/artifacts?" in path:
                    return {"artifacts": [dict(id=7, name="pre-main-evidence", expired=False, archive_download_url="pre")]}
                if path.startswith("commits/") and "/statuses?" in path: return statuses
                if path.startswith("statuses/"):
                    statuses.append(dict(value, id=10, created_at="2026-10-30T00:02:00Z")); return statuses[-1]
                raise AssertionError(path)
            def download(self, path): return outer.archive()
        tool = release.Release(self.root, GitHub())
        tool.bind("9.9.9", self.commit)
        tool.state.update(testBeforeMain=True, runId=42, mainBefore=self.before, verificationBase=self.before)
        tool.save()
        calls = []
        def git(*args, **kwargs):
            calls.append(args)
            if args[0] == "status": stdout = b""
            elif args[0] == "rev-parse": stdout = outer.commit.encode()
            else: stdout = b""
            return SimpleNamespace(stdout=stdout, returncode=0)
        tool.git = git
        tool.remote = lambda branch: refs[branch]
        def push(branch, commit):
            calls.append(("push", branch, commit)); refs[branch] = commit
        tool.push = push
        return tool, requests, calls, refs, statuses, run

    def test_final_evidence_binds_original_tests_candidate_and_post_gate_promotion(self):
        v.validate_ci_evidence(self.final, self.manifest, 42, 1)
        mutations = [lambda r: r["promotion"].update(mainSha="d" * 40),
                     lambda r: r["promotion"]["status"].update(created_at="2026-10-30T00:00:00Z"),
                     lambda r: r["promotion"]["status"].update(context=v.promotion_context(43, 1)),
                     lambda r: r["promotion"]["status"].update(target_url="https://github.com/foreign/job/3"),
                     lambda r: r["preMain"]["build"].update(candidateSha256="c" * 64),
                     lambda r: r["build"]["jvm"].update(tests=8),
                     lambda r: r["promotion"].update(preMainSha256="0" * 64)]
        for change in mutations:
            bad = copy.deepcopy(self.final); change(bad)
            with self.assertRaises(ValueError): v.validate_ci_evidence(bad, self.manifest, 42, 1)

    def test_pre_main_gate_rejects_later_failure_or_foreign_attempt(self):
        v.pre_main_gate(self.jobs, self.pre)
        for replacement in [dict(self.jobs[0], id=4, conclusion="failure"),
                            dict(self.jobs[0], id=4, run_attempt=2),
                            dict(self.jobs[2], id=4, run_attempt=2)]:
            with self.assertRaises(ValueError): v.pre_main_gate(self.jobs + [replacement], self.pre)
        bad = copy.deepcopy(self.pre); bad["build"]["jvm"]["skipped"] = 1
        with self.assertRaises(ValueError): v.validate_pre_main_evidence(bad, 42, 1, self.commit)

    def test_reused_github_jobs_keep_original_execution_identity_but_real_reruns_do_not(self):
        jobs = [dict(row, run_id=42, head_sha=self.commit, runner_id=100, runner_name="runner",
                     started_at="2026-10-30T00:00:00Z", steps=[dict(number=1, name="test", status="completed",
                         conclusion="success", started_at="2026-10-30T00:00:00Z", completed_at=row["completed_at"])])
                for row in self.jobs]
        clones = [dict(jobs[0], id=4, run_attempt=2), dict(jobs[2], id=5, run_attempt=2)]
        self.assertEqual(v.pre_main_gate(jobs + clones, self.pre), v.pre_main_gate(jobs, self.pre))
        for field, value in [("started_at", "2026-10-30T00:00:01Z"), ("head_sha", "d" * 40),
                             ("run_id", 43), ("steps", [dict(name="another execution")])]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                v.pre_main_gate(jobs + [dict(clones[0], **{field: value})], self.pre)
        with self.assertRaises(ValueError):
            v.pre_main_gate(jobs + clones + [dict(clones[0], id=6, conclusion="failure")], self.pre)

    def test_start_dispatches_one_staged_run_and_never_moves_main(self):
        requests, pushes, calls = [], [], []
        class GitHub:
            def api(self, path, value=None, **kwargs):
                if path.startswith("git/ref/tags/"): return None
                if path.endswith("/dispatches"): requests.append(value); return None
                raise AssertionError(path)
        tool = release.Release(self.root, GitHub())
        def git(*args, **kwargs):
            calls.append(args)
            stdout = b"" if args[0] == "status" else b"Develop" if args[0] == "branch" else b"feat: release [skip ci]" if args[0] == "log" else self.commit.encode()
            return SimpleNamespace(stdout=stdout, returncode=0)
        tool.git, tool.remote = git, lambda branch: self.before
        tool.push = lambda branch, commit: pushes.append((branch, commit))
        tool.status = lambda: {"phase": "ci"}
        with patch.object(release, "command"), patch.object(release, "make_plan", return_value=self.plan):
            tool.start("9.9.9", full_verification=True, test_before_main=True)
            tool.start("9.9.9", full_verification=True, test_before_main=True)
        self.assertEqual(pushes, [("Develop", self.commit)])
        self.assertFalse(any(row[0] in ("switch", "merge") for row in calls))
        self.assertEqual(len(requests), 1)
        self.assertEqual(requests[0]["ref"], "Develop")
        self.assertEqual(requests[0]["inputs"]["promotion_base_sha"], self.before)
        self.assertEqual(requests[0]["inputs"]["test_before_main"], "true")
        with self.assertRaises(release.ReleaseError): tool.start("9.9.9", full_verification=True)

    def test_promote_checks_full_evidence_before_ff_then_records_exact_status(self):
        tool, requests, calls, refs, statuses, run = self.coordinator()
        with patch.object(release, "make_plan", return_value=self.plan):
            result = tool.promote(42)
            self.assertTrue(result["promotedMain"])
            self.assertEqual(refs["main"], self.commit)
            self.assertIn(("push", "main", self.commit), calls)
            self.assertEqual(statuses, [self.status])
            tool.promote(42)
        self.assertEqual(len([row for row in calls if row[0] == "push"]), 1)
        self.assertEqual(len([row for row in requests if row[0].startswith("statuses/")]), 1)

    def test_promote_failure_never_moves_refs_or_writes_status(self):
        for failure in ("missing gate", "failed later", "source plan", "changed main", "wrong source"):
            with self.subTest(failure=failure):
                tool, requests, calls, refs, statuses, run = self.coordinator()
                original = copy.deepcopy(self.jobs)
                if failure == "missing gate": self.jobs = self.jobs[:-1]
                elif failure == "failed later": self.jobs += [dict(self.jobs[0], id=4, conclusion="failure")]
                elif failure == "changed main": refs["main"] = "d" * 40
                elif failure == "wrong source": run["head_branch"] = "main"
                plan = dict(self.plan, inventory=["p.Other#two"]) if failure == "source plan" else self.plan
                with patch.object(release, "make_plan", return_value=plan), self.assertRaises((ValueError, release.ReleaseError)):
                    tool.promote(42)
                self.assertFalse(any(row[0] in ("switch", "merge", "push") for row in calls))
                self.assertEqual(statuses, [])
                self.jobs = original

    def test_develop_source_needs_fresh_remote_gate_and_exact_promotion_status(self):
        tool, requests, calls, refs, statuses, run = self.coordinator()
        statuses.append(self.status)
        tool.validate_run_promotion(run, self.final, self.jobs)
        with self.assertRaises(release.ReleaseError): tool.validate_run_promotion(run, self.pre, self.jobs)
        statuses.append(dict(self.status, id=11, state="failure"))
        with self.assertRaises(release.ReleaseError): tool.validate_run_promotion(run, self.final, self.jobs)
        # Historical main release evidence remains usable without staging fields/statuses.
        tool.validate_run_promotion(dict(run, head_branch="main"), self.pre)

    def test_staged_final_collector_requires_device_success_and_unchanged_remote_status(self):
        spec = importlib.util.spec_from_file_location("ci_staged_test", TOOLS / "ci-verification.py")
        ci = importlib.util.module_from_spec(spec); spec.loader.exec_module(ci)
        folder = self.root / "verification"
        v.write_json(folder / "pre-main/verification.json", self.pre)
        v.write_json(folder / "promotion.json", self.promotion)
        v.write_json(self.root / "app/build/release-candidate/release-manifest.json", self.manifest)
        needs = {key: dict(result="success") for key in ("build", "windows-tools", "pre-main-verification", "candidate")}
        needs["android-device"] = dict(result="skipped")
        def api(path): return dict(object=dict(sha=self.commit))
        def pages(path, field=None): return self.jobs if field else [self.status]
        import os
        with patch.object(ci, "ROOT", self.root), patch.object(ci, "FOLDER", folder), patch.object(ci, "api", side_effect=api), patch.object(ci, "pages", side_effect=pages), patch.object(ci, "make_plan", return_value=self.plan), patch.dict(os.environ, GITHUB_RUN_ID="42", GITHUB_RUN_ATTEMPT="1", NEEDS_JSON=json.dumps(needs)):
            ci.collect_staged()
            self.assertEqual(json.loads((folder / "verification.json").read_text()), self.final)
            needs["android-device"]["result"] = "failure"
            with patch.dict(os.environ, NEEDS_JSON=json.dumps(needs)), self.assertRaises(ValueError): ci.collect_staged()
            with patch.object(ci, "pages", side_effect=lambda path, field=None: self.jobs if field else [dict(self.status, state="failure")]), self.assertRaises(ValueError): ci.collect_staged()


if __name__ == "__main__": unittest.main()
