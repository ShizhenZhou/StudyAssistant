"""Synthetic APKs and API replies only. Never signs, contacts GitHub, or reads local credentials."""
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import release
from release_common import apk_identity, apk_payload, extract_candidate, sha256, validate_manifest

class ReleaseTest(unittest.TestCase):
    def setUp(self):
        folder = ROOT / "app/build/tool-tests"; folder.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=folder); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
    def apk(self, name, code=b"synthetic-code", signature=None):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("classes.dex", code)
            z.writestr("resources.arsc", b"synthetic-resources")
            z.writestr("assets/build-time-utc.txt", "20261024120000")
            if signature is not None: z.writestr("META-INF/CERT.SF", signature)
        return path
    def manifest(self, apk):
        payload, stamp = apk_payload(apk)
        return dict(schema=1, package="com.zsz.studyassistant",version="9.9.9",versionCode=99,minSdk=28,targetSdk=37,
            commit="a"*40,buildUtc=stamp,candidate="StudyAssistant-9.9.9-"+stamp+"-candidate.apk",
            candidateSha256=sha256(apk),payloadSha256=payload,expectedSigner="b"*64,jvm={"tests":1,"failures":0,"errors":0,"skipped":0})
    def artifact(self, manifest, apk, extra=False):
        out = io.BytesIO()
        with zipfile.ZipFile(out,"w") as z:
            z.writestr("release-manifest.json",json.dumps(manifest))
            z.writestr(manifest["candidate"],apk.read_bytes())
            if extra:z.writestr("secrets.properties",b"synthetic forbidden entry")
        return out.getvalue()
    def test_signing_metadata_is_allowed_but_code_changes_are_not(self):
        plain=self.apk("plain.apk"); signed=self.apk("signed.apk",signature=b"synthetic-signature")
        changed=self.apk("changed.apk",code=b"different-code",signature=b"synthetic-signature")
        self.assertEqual(apk_payload(plain),apk_payload(signed))
        self.assertNotEqual(apk_payload(plain),apk_payload(changed))
    def test_artifact_only_accepts_two_public_allowlisted_files(self):
        apk=self.apk("input.apk"); manifest=self.manifest(apk)
        found,path=extract_candidate(self.artifact(manifest,apk),self.root/"good")
        self.assertEqual(found,manifest);self.assertEqual(sha256(path),manifest["candidateSha256"])
        with self.assertRaises(ValueError):extract_candidate(self.artifact(manifest,apk,True),self.root/"bad")
        self.assertFalse((self.root/"bad").exists())
    def test_candidate_tampering_and_unknown_manifest_fields_are_rejected(self):
        apk=self.apk("input.apk"); manifest=self.manifest(apk)
        bad=dict(manifest,candidateSha256="0"*64)
        with self.assertRaises(ValueError):extract_candidate(self.artifact(bad,apk),self.root/"bad")
        with self.assertRaises(ValueError):validate_manifest(dict(manifest,privateConfig="forbidden"))
    def test_local_state_cannot_retarget_a_version(self):
        path=self.root/"state.json";state=release.bind_state(path,"9.9.9","a"*40);path.write_text(json.dumps(state))
        before=path.read_bytes()
        with self.assertRaises(release.ReleaseError):release.bind_state(path,"9.9.9","b"*40)
        self.assertEqual(before,path.read_bytes())
    def test_state_cannot_select_a_private_file_as_upload(self):
        with self.assertRaises(release.ReleaseError):release.local_artifact(self.root,"secrets.properties")
        with self.assertRaises(release.ReleaseError):release.local_artifact(self.root,"../outside.apk")
    def test_only_the_updates_section_can_be_published(self):
        text="private header\n## 更新内容\n- public change\n\n## Internal\nprivate footer"
        self.assertEqual(release.update_notes(text),"- public change")
        with self.assertRaises(release.ReleaseError):release.update_notes("missing section")
    def test_draft_timeout_can_recover_only_the_exact_owned_draft(self):
        state={"draftAttempted":True,"version":"9.9.9","commit":"a"*40}
        draft={"draft":True,"tag_name":"v9.9.9","target_commitish":"a"*40,"body":"public","assets":[]}
        self.assertTrue(release.recoverable_draft(draft,state,"public"))
        for changed in [dict(draft,draft=False),dict(draft,target_commitish="b"*40),dict(draft,assets=[{}])]:
            self.assertFalse(release.recoverable_draft(changed,state,"public"))
    def test_uncertain_dispatch_is_queried_without_build_or_second_post(self):
        commit="a"*40;now=datetime.now(timezone.utc).isoformat()
        run={"id":123,"head_sha":commit,"display_title":"release: "+commit,"created_at":now,"status":"in_progress","conclusion":None}
        class FakeGitHub:
            def api(self,path,value=None,**kwargs):
                if value is not None: raise AssertionError("Duplicate dispatch")
                return {"workflow_runs":[run]} if "/runs?" in path else run
        tool=release.Release(self.root,FakeGitHub());tool.bind("9.9.9",commit)
        tool.state.update(dispatchAttempted=True,requestedAt=now);tool.save()
        def git(*args,**kwargs):
            if args==("status","--porcelain"):return SimpleNamespace(stdout=b"",returncode=0)
            if args==("rev-parse","HEAD"):return SimpleNamespace(stdout=commit.encode(),returncode=0)
            raise AssertionError("No build/merge/push should repeat")
        tool.git=git
        with patch.object(release,"command",side_effect=AssertionError("No process should run")):
            self.assertEqual(tool.start("9.9.9")["runId"],123)
            self.assertEqual(tool.start("9.9.9")["runId"],123)
    def test_release_requires_both_android_api_suites_to_succeed(self):
        class SourceReached(Exception): pass
        run = dict(event="workflow_dispatch", head_branch="main", conclusion="success", display_title="release: fixture", head_sha="a"*40)
        good = [dict(name=f"Android emulator tests (API {api})", conclusion="success") for api in (35, 36)]
        for jobs, accepted in [(good, True), (good[:1], False), (good[1:], False),
                ([good[0], dict(good[1], conclusion="failure")], False),
                ([dict(name="Android emulator tests", conclusion="success")], False)]:
            class FakeGitHub:
                def api(self, path):
                    return {"jobs": jobs} if path.endswith("/jobs") else run
            tool = release.Release(self.root, FakeGitHub())
            def reached(commit): raise SourceReached()
            tool.assert_source = reached
            with self.assertRaises(SourceReached if accepted else release.ReleaseError):
                tool.verify(123)

    def test_identity_and_debug_candidates_are_rejected(self):
        good="package: name='com.zsz.studyassistant' versionCode='99' versionName='9.9.9'\nsdkVersion:'28'\ntargetSdkVersion:'37'"
        self.assertEqual(apk_identity(good)["versionCode"],99)
        for bad in [good+"\napplication-debuggable",good.replace("sdkVersion:'28'","sdkVersion:'27'"),good.replace("com.zsz.studyassistant","other")]:
            with self.assertRaises(ValueError):apk_identity(bad)
    def test_ci_does_not_read_repository_signing_secrets(self):
        workflow=(ROOT/".github/workflows/build.yml").read_text(encoding="utf-8")
        self.assertNotIn("secrets.RELEASE_",workflow)
        self.assertNotIn("secrets.properties <<",workflow)
        self.assertNotIn("path: .",workflow)
