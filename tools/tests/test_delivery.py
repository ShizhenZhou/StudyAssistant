"""Small offline regression for the current delivery contract. Never dispatched by CI."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import local_checks
import release
import apk_delta
import apk_archive_delta
from release_common import (COMPAT_BRANCH, apk_identity, apk_payload, extract_candidate,
                            release_branch_allowed, release_tag, version_from_tag, sha256, validate_manifest)


class DeliveryTest(unittest.TestCase):
    def source_profile(self, root, distribution, version, tag_prefix=None):
        directory = root / "config/distributions"
        directory.mkdir(parents=True, exist_ok=True)
        profile = (Path(__file__).resolve().parents[2] / f"config/distributions/{distribution}.properties").read_text(encoding="utf-8")
        profile = "\n".join("versionName=" + version if line.startswith("versionName=") else line for line in profile.splitlines()) + "\n"
        if distribution == "compat":
            prefix = tag_prefix or "compat_v"
            profile = "\n".join("tagPrefix=" + prefix if line.startswith("tagPrefix=") else line for line in profile.splitlines()) + "\n"
        if distribution == "compat" and not version.endswith("_Compat"):
            profile = profile.replace("apkPrefix=SA\n", "apkPrefix=SACompat\n")
        (directory / f"{distribution}.properties").write_text(profile, encoding="utf-8")

    def test_public_asset_failure_uses_one_official_alternate(self):
        from types import SimpleNamespace
        github = release.GitHub()
        url = "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v0.7.1/fixture.apk"
        failed = SimpleNamespace(returncode=22, stdout=b"")
        ok = SimpleNamespace(returncode=0, stdout=b"expected bytes")
        wrong_sha = SimpleNamespace(returncode=0, stdout=b"unwanted bytes")
        wrong_size = SimpleNamespace(returncode=0, stdout=b"{}")
        asset = dict(id=123, name="fixture.apk", size=len(ok.stdout),
                     digest="sha256:" + hashlib.sha256(ok.stdout).hexdigest())
        for primary in (failed, wrong_sha, wrong_size, release.subprocess.TimeoutExpired("curl", 130)):
            with self.subTest(primary=primary), \
                 patch.object(release.shutil, "which", return_value="curl.exe"), \
                 patch.object(github, "api", return_value=dict(asset, browser_download_url=url)), \
                 patch.object(release, "command", side_effect=[primary, ok]) as command:
                self.assertEqual(ok.stdout, github.download_release_asset(asset))
                self.assertEqual(2, command.call_count)
                self.assertEqual(url, command.call_args.args[0][-1])
                for attempt in command.call_args_list:
                    args = attempt.args[0]
                    self.assertEqual(str(asset["size"] + 1), args[args.index("--max-filesize") + 1])

        with patch.object(release.shutil, "which", return_value="curl.exe"), \
             patch.object(github, "api") as api, \
             patch.object(release, "command", return_value=ok) as command:
            self.assertEqual(ok.stdout, github.download_release_asset(asset))
            self.assertEqual(1, command.call_count)
            api.assert_not_called()

        for alternate in (dict(asset, browser_download_url="https://example.com/private"),
                          dict(asset, browser_download_url=url.replace("fixture.apk", "other.apk")),
                          dict(asset, id=124, browser_download_url=url),
                          dict(asset, digest="sha256:" + "f" * 64, browser_download_url=url)):
            with self.subTest(alternate=alternate), \
                 patch.object(release.shutil, "which", return_value="curl.exe"), \
                 patch.object(github, "api", return_value=alternate), \
                 patch.object(release, "command", return_value=failed) as command:
                with self.assertRaises(release.ReleaseError):
                    github.download_release_asset(asset)
                self.assertEqual(1, command.call_count)

        with patch.object(release.shutil, "which", return_value="curl.exe"), \
             patch.object(github, "api") as api, \
             patch.object(release, "command", side_effect=[wrong_size, wrong_sha]) as command:
            with self.assertRaises(release.ReleaseError):
                github.download_release_asset(dict(asset, browser_download_url=url))
            self.assertEqual(2, command.call_count)
            api.assert_not_called()

        with patch.object(release.shutil, "which", return_value=None), \
             patch.object(github, "call", return_value=wrong_size.stdout) as call, \
             patch.object(release, "build_opener") as opener:
            response = opener.return_value.open.return_value.__enter__.return_value
            response.read.return_value = ok.stdout
            self.assertEqual(ok.stdout, github.download_release_asset(dict(asset, browser_download_url=url)))
            call.assert_called_once_with("api", "repos/ShizhenZhou/StudyAssistant/releases/assets/123?download=1",
                                         "-H", "Accept: application/octet-stream")
            opener.return_value.open.assert_called_once_with(url, timeout=120)
            response.read.assert_called_once_with(asset["size"] + 1)

    def test_only_provenance_reuses_verified_download_cache(self):
        data = b"expected bytes"
        identity = dict(id=123, name="fixture.apk", digest="sha256:" + hashlib.sha256(data).hexdigest())
        asset = dict(identity, size=len(data))
        artifact = dict(identity, size_in_bytes=len(data), archive_download_url="https://api.github.com/artifact")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            github = release.GitHub(root)
            coordinator = release.Release(root, github)
            path = root / asset["name"]
            path.write_bytes(data)
            with patch.object(github, "download_release_asset", return_value=data) as public, \
                 patch.object(github, "download", return_value=data) as authenticated:
                self.assertEqual(data, coordinator.verified_download(asset, "release-asset"))
                self.assertEqual(data, coordinator.verified_download(asset, "release-asset"))
                public.assert_called_once_with(asset)
                self.assertEqual(data, coordinator.verified_download(artifact, "action-artifact"))
                self.assertEqual(data, coordinator.verified_download(artifact, "action-artifact"))
                authenticated.assert_called_once_with(artifact["archive_download_url"])
                self.assertEqual(data, coordinator.download_uploaded_asset(asset, path))
                self.assertEqual(2, public.call_count)
                path.write_bytes(b"modified bytes")
                with self.assertRaises(release.ReleaseError):
                    coordinator.download_uploaded_asset(asset, path)

    def manifest(self):
        return dict(schema=3, package="com.zsz.studyassistant", version="0.6.8.7", versionCode=88,
                    minSdk=36, targetSdk=37, commit="a"*40, buildUtc="20261030120000",
                    candidate="StudyAssistant-0.6.8.7-20261030120000-candidate.apk",
                    candidateSha256="b"*64, payloadSha256="c"*64, expectedSigner="d"*64)

    def test_default_local_plan_does_not_compile_or_test(self):
        self.assertEqual([], local_checks.plan_checks()["tasks"])
        self.assertEqual(["compileModernDebugKotlin"], local_checks.plan_checks(compile_app=True)["tasks"])

    def test_targeted_test_owns_its_compilation_without_expanding_scope(self):
        plan = local_checks.plan_checks(["example.Test.method"], compile_app=True)
        self.assertEqual(["testModernIntegrationUnitTest"], plan["tasks"])
        self.assertEqual(["example.Test.method"], plan["selectors"])
        with self.assertRaises(ValueError): local_checks.plan_checks(["*"])

    def test_build_manifest_does_not_claim_test_success(self):
        value = self.manifest()
        self.assertEqual(value, validate_manifest(value))
        for extra in ({"jvm": {"tests": 1}}, {"planSha256": "e"*64}):
            with self.assertRaises(ValueError): validate_manifest(dict(value, **extra))

    def test_compat_distribution_is_bound_to_apk_and_never_latest_or_cross_line_delta(self):
        value = self.manifest()
        value.update(distribution="compat", minSdk=28, maxSdk=35, version="0.7.4.1", versionCode=135,
                     candidate="StudyAssistant-0.7.4.1-20261030120000-candidate.apk")
        self.assertEqual("compat_v0.7.4.1", release_tag(value["version"], "compat"))
        self.assertEqual("v0.7.4.1", release_tag(value["version"]))
        self.assertEqual(value["version"], version_from_tag(release_tag(value["version"], "compat")))
        validate_manifest(value)
        for fields in (dict(minSdk=36), dict(maxSdk=36), dict(distribution="unknown")):
            with self.assertRaises(ValueError): validate_manifest(dict(value, **fields))
        self.assertTrue(release_branch_allowed("main", "beta", "compat"))
        self.assertFalse(release_branch_allowed(COMPAT_BRANCH, "beta", "main"))
        self.assertFalse(release_branch_allowed(COMPAT_BRANCH, "stable", "compat"))
        self.assertTrue(release_branch_allowed(COMPAT_BRANCH, "stable", "compat", historical=True))
        self.assertEqual("false", release.make_latest(dict(distribution="compat", channel="stable")))
        self.assertEqual("0.7.5.3", release.numeric_version("0.7.5.3_Compat"))
        self.assertEqual("0.7.6", release.numeric_version("0.7.6_beta2_Compat"))
        self.assertEqual("compat_v0.7.5.3_Compat", release_tag("0.7.5.3_Compat", "compat"))
        archived = dict(version="0.7.5.3_Compat", distribution="compat")
        self.assertEqual("compat_v0.7.5.3_Compat", release.state_tag(archived))
        self.assertEqual("v0.7.5.3_Compat", release.state_tag(dict(archived, releaseTag="v0.7.5.3_Compat")))
        with self.assertRaises(release.ReleaseError): release.state_tag(dict(archived, releaseTag="v0.7.5.3"))
        with self.assertRaises(ValueError): release_tag("0.7.5.3_Compat", "main")
        self.assertEqual("true", release.make_latest(dict(channel="stable")))
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "compat.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/distribution.json", json.dumps(dict(distribution="compat", minSdk=28, maxSdk=35)))
            metadata = release.update_manifest(apk, value, value)
            self.assertEqual(2, metadata["schema"])
            self.assertEqual("compat", metadata["distribution"])
            self.assertEqual(35, metadata["maxSdk"])
            apk.with_name(apk.stem + "-update-manifest.json").write_text(json.dumps(metadata), encoding="utf-8")
            bases = [apk_delta.read_base(apk)]
            release.validate_delta_distribution(bases, "compat")
            with self.assertRaises(release.ReleaseError): release.validate_delta_distribution(bases, "main")

    def test_compat_start_requires_explicit_identity_and_freezes_dispatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / "app").mkdir()
            self.source_profile(root, "compat", "0.7.4.1")
            self.source_profile(root, "main", "0.7.4.2")
            calls = []
            existing_tags = set()
            class GitHub:
                def require_authentication(self): pass
                def api(self, path, value=None, **kwargs):
                    calls.append((path, value))
                    if path.startswith("git/ref/tags/") and path.removeprefix("git/ref/tags/") in existing_tags:
                        return {"ref": path.removeprefix("git/")}
                    if path.startswith("releases/tags/") and path.removeprefix("releases/tags/") in existing_tags:
                        return {"tag_name": path.removeprefix("releases/tags/"), "draft": False, "prerelease": False}
                    return {"workflow_runs": []} if "runs?" in path else None
            r = release.Release(root, GitHub())
            def fake_git(*args, **kwargs):
                values = {("status", "--porcelain"): "", ("branch", "--show-current"): "main",
                          ("rev-parse", "HEAD"): "a"*40, ("log", "-1", "--format=%s"): "feat: support older Android"}
                return type("Result", (), {"stdout": values[args].encode(), "returncode": 0})()
            with patch.object(r, "git", side_effect=fake_git), patch.object(r, "remote", return_value="a"*40):
                with self.assertRaises(release.ReleaseError): r.start("0.7.4.1")
                self.assertEqual([], calls)
                r.start("0.7.4.1", distribution="compat")
                r.start("0.7.4.1", distribution="compat")
            dispatches = [v for p, v in calls if p.endswith("/dispatches")]
            self.assertEqual(1, len(dispatches))
            self.assertEqual("compat", dispatches[0]["inputs"]["release_distribution"])
            self.assertEqual("stable", dispatches[0]["inputs"]["release_channel"])
            self.assertIn(("git/ref/tags/compat_v0.7.4.1", None), calls)
            compat_path = r.path
            # Same commit/version may be published twice; pending states must not collide.
            self.source_profile(root, "main", "0.7.4.1")
            with patch.object(r, "git", side_effect=fake_git), patch.object(r, "remote", return_value="a"*40):
                r.start("0.7.4.1")
            self.assertNotEqual(compat_path, r.path)
            self.assertNotIn("distribution", r.state)
            self.assertEqual(2, len([v for p, v in calls if p.endswith("/dispatches")]))
            self.source_profile(root, "compat", "0.7.4.1_Compat")
            with patch.object(r, "git", side_effect=fake_git), patch.object(r, "remote", return_value="a"*40):
                r.start("0.7.4.1_Compat", distribution="compat")
            self.assertEqual("stable", r.state["channel"])
            self.assertIn(("git/ref/tags/compat_v0.7.4.1_Compat", None), calls)

            version = "0.7.7_Compat"
            self.source_profile(root, "compat", version, tag_prefix="v")
            with patch.object(r, "git", side_effect=fake_git), patch.object(r, "remote", return_value="a"*40):
                r.start(version, distribution="compat")
            self.assertEqual("v" + version, r.state["releaseTag"])
            self.assertIn(("git/ref/tags/compat_v" + version, None), calls)
            self.assertIn(("git/ref/tags/v" + version, None), calls)

            version = "0.7.6_Compat"
            existing_tags.add("compat_v" + version)
            self.source_profile(root, "compat", version, tag_prefix="v")
            with patch.object(r, "git", side_effect=fake_git), patch.object(r, "remote", return_value="a"*40):
                with self.assertRaisesRegex(release.ReleaseError, "Tag exists"):
                    r.start(version, distribution="compat")
                existing_tags.add("v" + version)
                with self.assertRaisesRegex(release.ReleaseError, "unambiguous"):
                    r.start(version, replacement=True, distribution="compat")
                existing_tags.remove("v" + version)
                r.start(version, replacement=True, distribution="compat")
            self.assertEqual("compat_v" + version, r.state["releaseTag"])

    def test_beta_candidate_and_signed_apk_keep_number_but_update_manifest_uses_numeric_version(self):
        base = "0.6.9.9"
        value = self.manifest()
        with tempfile.TemporaryDirectory() as tmp:
            for suffix in ("", "_beta", "_beta1", "_beta10"):
                version = base + suffix
                badging = (f"package: name='com.zsz.studyassistant' versionCode='102' versionName='{version}'\n"
                           "sdkVersion:'36'\ntargetSdkVersion:'37'")
                identity = apk_identity(badging)
                apk = Path(tmp) / f"StudyAssistant-{version}-{value['buildUtc']}.apk"
                apk.write_bytes(b"fixture")
                candidate = validate_manifest(dict(value, **identity, candidate=apk.stem + "-candidate.apk"))
                metadata = release.update_manifest(apk, identity, candidate)
                self.assertEqual(version, candidate["version"])
                self.assertEqual(base, metadata["version"])
                self.assertEqual(apk.name, metadata["apkName"])
            for suffix in ("_beta0", "_beta01", "_beta1more"):
                with self.assertRaises(ValueError):
                    apk_identity(badging.replace(version, base + suffix))

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

    def test_mandatory_is_explicit_stable_and_immutable_on_resume(self):
        self.assertEqual("SA-0.7.5.4_Compat-20261119120000.apk", release.published_apk_name("0.7.5.4_Compat", "20261119120000"))
        self.assertEqual("SA-ENF-0.7.5.4_Compat-20261119120000.apk", release.published_apk_name("0.7.5.4_Compat", "20261119120000", True))
        self.assertEqual("SA-0.7.6_beta1_Compat-20261119120000.apk", release.published_apk_name("0.7.6_beta1_Compat", "20261119120000"))
        self.assertEqual("SA-0.7.5-20261117120000.apk", release.published_apk_name("0.7.5", "20261117120000"))
        self.assertEqual("SA-ENF-0.7.5-20261117120000.apk", release.published_apk_name("0.7.5", "20261117120000", True))
        self.assertEqual("StudyAssistant-0.7.4.2-20261116120000-ENF.apk", release.published_apk_name("0.7.4.2", "20261116120000", True))
        state = {"channel": "stable"}
        release.bind_mandatory(state, True)
        release.bind_mandatory(state, None)
        self.assertTrue(state["mandatory"])
        with self.assertRaises(release.ReleaseError): release.bind_mandatory(state, False)
        legacy = {"dispatchAttempted": True}
        release.bind_mandatory(legacy, None)
        self.assertNotIn("mandatory", legacy)
        with self.assertRaises(release.ReleaseError): release.bind_mandatory(legacy, True)
        for value in ("true", 1, None):
            with self.assertRaises(release.ReleaseError): release.mandatory_release({"mandatory": value})
        with self.assertRaises(release.ReleaseError): release.mandatory_release({"channel": "beta", "mandatory": True})
        notes = "- 最低支持 Android 16+。 本版本为正式版。"
        body = release.release_body(notes, state)
        self.assertTrue(body.split("\n\n")[0].endswith("本版本为正式版。本次更新极其重要，是强制更新"))
        self.assertEqual(1, body.count("本次更新极其重要，是强制更新"))
        self.assertNotIn("本次更新极其重要", release.release_body(notes, {"channel": "stable", "mandatory": False}))

    def test_gdiff_reconstructs_bytes_and_rejects_out_of_bounds_or_truncation(self):
        import gzip
        import struct
        base = bytes(range(256)) * 400
        target = b"new header" + base[100:] + b"changed signing block" + base[:150]
        delta = apk_delta.encode(base, target)
        self.assertEqual(target, apk_delta.apply(base, delta, len(target)))
        # Same wire format, including repeated seeds, shifted matches and random literals.
        import random
        random_bytes = random.Random(42).randbytes(180_000)
        repeated = b"shared seed text" * 2
        cases = [(base, target), (random_bytes, b"prefix" + random_bytes[91:155_000] + b"suffix"),
                 (repeated + b"short" + random_bytes + repeated + random_bytes,
                  b"inserted" + repeated + random_bytes), (b"a", b"b"),
                 (random_bytes, random.Random(43).randbytes(25_000))]
        for before, after in cases:
            legacy = apk_delta.encode(before, after)
            candidates = [legacy] + [apk_delta.encode_matched(before, after, block) for block in (16, 32)]
            for candidate in candidates:
                self.assertEqual(after, apk_delta.apply(before, candidate, len(after)))
            best = apk_delta.encode_best(before, after)
            self.assertEqual(min(map(len, candidates)), len(best))
            self.assertEqual(best, apk_delta.encode_best(before, after))
        # W3C's independently specified example: ABCDEFG -> ABXYCDBCDE.
        example = apk_delta.MAGIC + bytes([249, 0, 0, 2, 2, 88, 89, 249, 0, 2, 2, 249, 0, 1, 4, 0])
        self.assertEqual(b"ABXYCDBCDE", apk_delta.apply(b"ABCDEFG", gzip.compress(example), 10))
        for stream in (apk_delta.MAGIC + b"\xfe" + struct.pack(">ii", len(base), 1) + b"\x00",
                       apk_delta.MAGIC + b"\xfe" + struct.pack(">ii", 0, -1) + b"\x00",
                       apk_delta.MAGIC + b"\x02a", apk_delta.MAGIC + b"\x01a\x00trailing"):
            with self.assertRaises(ValueError): apk_delta.apply(base, gzip.compress(stream), 1)
        with self.assertRaises(ValueError): apk_delta.apply(base, delta, len(target) - 1)

    def test_optional_delta_assets_keep_full_apk_and_manifest_integrity_checks(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            files = {name: folder / name for name in ("target.apk", "update-manifest.json", "target-from-1.gdiff.gz")}
            for name, path in files.items(): path.write_bytes(name.encode())
            assets = [dict(id=asset_id, name=name, size=path.stat().st_size, digest="sha256:" + sha256(path))
                      for asset_id, (name, path) in enumerate(files.items(), 1)]
            release.validate_release_assets(assets, files, complete=True)
            with self.assertRaises(release.ReleaseError): release.validate_release_assets(assets[:-1], files, complete=True)
            with self.assertRaises(release.ReleaseError):
                release.validate_release_assets([dict(assets[0], id=0)], files)
            assets[-1]["digest"] = "sha256:" + "0" * 64
            with self.assertRaises(release.ReleaseError): release.validate_release_assets(assets, files, complete=True)
            with self.assertRaises(release.ReleaseError):
                release.delta_files(folder / "target.apk", {"deltas": [{"format": apk_delta.FORMAT, "patchName": "../private.gdiff.gz"}]})

    def test_missing_delta_recovers_only_the_frozen_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            base = folder / "base.apk"; base.write_bytes(bytes(range(256)) * 100)
            record = dict(path=str(base), baseSha256=sha256(base), baseSize=base.stat().st_size, baseVersionCode=1)
            original = folder / "sign-old"; original.mkdir()
            target = original / "target.apk"; target.write_bytes(base.read_bytes() + b"signed target")
            metadata = apk_delta.build(record, target, 2, original)
            with self.assertRaises(ValueError): apk_delta.build(record, target, 1, original)
            self.assertEqual(metadata, apk_delta.build(record, target, 1, original, allow_same_version=True))
            with self.assertRaises(ValueError):
                apk_delta.build(dict(record, baseVersionCode=3), target, 2, original, allow_same_version=True)
            with self.assertRaises(ValueError):
                apk_delta.build(record, base, 1, original, allow_same_version=True)
            frozen = dict(metadata)
            recovered = folder / "sign-new"; recovered.mkdir()
            restored = recovered / target.name; restored.write_bytes(target.read_bytes())
            self.assertEqual(metadata, apk_delta.build(record, restored, 2, recovered, expected=metadata))
            self.assertEqual((original / metadata["patchName"]).read_bytes(), (recovered / metadata["patchName"]).read_bytes())
            changed = folder / "sign-changed"; changed.mkdir()
            wrong = changed / target.name; wrong.write_bytes(target.read_bytes() + b"different")
            with self.assertRaisesRegex(ValueError, "frozen release metadata"):
                apk_delta.build(record, wrong, 2, changed, expected=metadata)
            self.assertFalse((changed / metadata["patchName"]).exists())
            self.assertEqual(frozen, metadata)
            # Historical frozen artifacts retain the old byte-for-byte encoding.
            legacy_patch = apk_delta.encode(base.read_bytes(), target.read_bytes())
            legacy_metadata = dict(metadata, patchSha256=apk_delta.digest(legacy_patch), patchSize=len(legacy_patch))
            legacy_dir = folder / "legacy-recovered"
            self.assertEqual(legacy_metadata, apk_delta.build(record, target, 2, legacy_dir, expected=legacy_metadata))
            self.assertEqual(legacy_patch, (legacy_dir / metadata["patchName"]).read_bytes())

    @unittest.skipUnless(sys.platform == "win32", "PowerShell release entry point")
    def test_release_entry_forwards_verify_without_delta_python(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            wrapper = Path(release.__file__).with_suffix(".ps1")
            staged_wrapper = folder / wrapper.name
            staged_wrapper.write_bytes(wrapper.read_bytes())
            (folder / "codex-env.ps1").write_text("", encoding="utf-8")
            (folder / "release.py").write_text(
                "import json, sys; print(json.dumps(sys.argv[1:]))", encoding="utf-8")
            env = dict(os.environ, PATH=str(Path(sys.executable).parent) + os.pathsep + os.environ["PATH"],
                       STUDYASSISTANT_RELEASE_PYTHON=str(folder / "missing.exe"))
            result = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                     staged_wrapper, "verify", "--run", "123"],
                                    env=env, capture_output=True, text=True, timeout=20)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(["verify", "--run", "123"], json.loads(result.stdout))

    def test_release_delta_preparation_requires_classic_only_for_writes(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            base = folder / "base.apk"; base.write_bytes(bytes(range(256)) * 100)
            signed = folder / "target.apk"; signed.write_bytes(base.read_bytes() + b"signed target")
            record = dict(path=str(base), baseSha256=sha256(base), baseSize=base.stat().st_size, baseVersionCode=1)
            manifest = {"versionCode": 2}
            coordinator = release.Release(folder)
            coordinator.state = {}
            with patch.dict(os.environ, STUDYASSISTANT_RELEASE_PYTHON=str(folder / "missing.exe")):
                coordinator.prepare_deltas(signed, manifest)
                coordinator.state = {"deltaBases": [record], "legacyDeltaBaseCodes": [1]}
                with self.assertRaisesRegex(release.ReleaseError, "STUDYASSISTANT_RELEASE_PYTHON"):
                    coordinator.prepare_deltas(signed, manifest)
                self.assertNotIn("deltas", coordinator.state)
                frozen = apk_delta.build(record, signed, 2, folder)
                coordinator.state["deltas"] = [frozen]
                path = folder / frozen["patchName"]
                coordinator.prepare_deltas(signed, manifest)
                path.write_bytes(b"altered")
                with self.assertRaisesRegex(release.ReleaseError, "Local delta artifact changed"):
                    coordinator.prepare_deltas(signed, manifest)
                self.assertEqual(b"altered", path.read_bytes())
                path.unlink()
                with self.assertRaisesRegex(release.ReleaseError, "STUDYASSISTANT_RELEASE_PYTHON"):
                    coordinator.prepare_deltas(signed, manifest)
                self.assertFalse(path.exists())
                self.assertEqual([frozen], coordinator.state["deltas"])

    def test_archive_delta_preserves_zip_bytes_and_rejects_invalid_ranges(self):
        import gzip
        import random
        import struct
        raw = random.Random(917).randbytes(220_000) + b"repeated dex strings" * 5000
        def apk(dex, compression=zipfile.ZIP_DEFLATED):
            out = io.BytesIO()
            with zipfile.ZipFile(out, "w", compression=compression, compresslevel=6) as archive:
                archive.writestr("classes.dex", dex)
                archive.writestr("classes2.dex", b"unchanged dex" * 400)
                archive.writestr("assets/resource", b"resource bytes" * 100)
                archive.comment = b"retain exact archive comment"
            return out.getvalue()
        base = apk(raw)
        target = apk(raw[:900] + b"new dex instructions" + raw[919:])
        delta = apk_archive_delta.encode(base, target)
        self.assertEqual(target, apk_archive_delta.apply(base, delta, len(target)))
        self.assertLess(len(delta), len(apk_delta.encode_best(base, target)))
        unpacked = gzip.decompress(delta)
        header_size = struct.unpack(">I", unpacked[4:8])[0]
        header = json.loads(unpacked[8:8 + header_size])
        self.assertEqual(b"SAZ1", unpacked[:4])
        self.assertTrue(header["targetRanges"])
        self.assertEqual(apk_delta.MAGIC, unpacked[8 + header_size:13 + header_size])
        def corrupted(value, tail=None):
            encoded = json.dumps(value, separators=(",", ":")).encode()
            return gzip.compress(b"SAZ1" + struct.pack(">I", len(encoded)) + encoded +
                                 (unpacked[8 + header_size:] if tail is None else tail))
        for field, value in (("offset", -1), ("rawSize", apk_archive_delta.MAX_RAW + 1),
                             ("compressedSize", len(target)), ("level", 0), ("level", True)):
            changed = json.loads(json.dumps(header)); changed["targetRanges"][0][field] = value
            with self.assertRaises(ValueError): apk_archive_delta.apply(base, corrupted(changed), len(target))
        changed = dict(header, targetRanges=[header["targetRanges"][0]] * 17)
        with self.assertRaises(ValueError): apk_archive_delta.apply(base, corrupted(changed), len(target))
        for invalid in (gzip.compress(b"SAZ1" + struct.pack(">I", 65537)),
                        corrupted(header, unpacked[8 + header_size:] + b"tail"),
                        corrupted(header, unpacked[8 + header_size:-1])):
            with self.assertRaises(ValueError): apk_archive_delta.apply(base, invalid, len(target))
        first = header["baseRanges"][0]
        payload = base[first["offset"]:first["offset"] + first["compressedSize"]]
        for bad in (payload[:-1], payload + b"tail"):
            with self.assertRaises(ValueError): apk_archive_delta.inflate(bad, first["rawSize"])
        with self.assertRaises(apk_archive_delta.NotApplicable): apk_archive_delta.encode(base, apk(raw, zipfile.ZIP_STORED))

    def test_archive_delta_only_publication_and_frozen_recovery(self):
        import random
        raw = random.Random(917).randbytes(220_000) + b"repeated dex strings" * 5000
        def apk(dex):
            out = io.BytesIO()
            with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
                archive.writestr("classes.dex", dex)
            return out.getvalue()
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            base = folder / "base.apk"; base.write_bytes(apk(raw))
            target = folder / "target.apk"; target.write_bytes(apk(raw[:900] + b"new dex instructions" + raw[919:]))
            record = dict(path=str(base), baseSha256=sha256(base), baseSize=base.stat().st_size, baseVersionCode=1)
            with patch.object(apk_delta, "build", side_effect=AssertionError("legacy generation forbidden")):
                deltas = release.build_deltas([record], target, 3)
            self.assertEqual([apk_archive_delta.FORMAT], [item["format"] for item in deltas])
            self.assertEqual("target-from-1.gdiff.gz", deltas[0]["patchName"])
            legacy = apk_delta.build(record, target, 3, folder / "old-release")
            historical = dict(deltas[0], patchName="target-zip-from-1.gdiff.gz")
            for frozen in (deltas, [historical], [legacy], [legacy, historical]):
                for frozen_base, delta in release.frozen_delta_pairs([record], frozen):
                    builder = apk_delta if delta["format"] == apk_delta.FORMAT else apk_archive_delta
                    destination = folder / ("recovered-" + delta["format"])
                    restored = builder.build(frozen_base, target, 3, destination, expected=delta)
                    self.assertEqual(delta, restored)
                    self.assertEqual(delta["patchSha256"], sha256(destination / delta["patchName"]))
            for invalid in ([], deltas + deltas, [dict(deltas[0], baseVersionCode=9)]):
                with self.assertRaises(release.ReleaseError): release.frozen_delta_pairs([record], invalid)
            with self.assertRaises(ValueError):
                apk_archive_delta.build(record, target, 3, folder / "unsafe",
                    expected=dict(deltas[0], patchName="../outside.gdiff.gz"))
            records = [dict(record, baseVersionCode=code) for code in range(1, 9)]
            def fake_build(item, signed, code, output, **kwargs):
                return dict(item, format=apk_archive_delta.FORMAT,
                            patchName=f"target-from-{item['baseVersionCode']}.gdiff.gz", patchSize=100)
            with patch.object(apk_delta, "build", side_effect=AssertionError("legacy generation forbidden")), \
                    patch.object(apk_archive_delta, "build", side_effect=fake_build):
                selected = release.build_deltas(records, target, 9)
            self.assertEqual(8, len(selected))
            self.assertTrue(all(item["format"] == apk_archive_delta.FORMAT for item in selected))
            def legacy_build(item, signed, code, output, **kwargs):
                return dict(fake_build(item, signed, code, output, **kwargs), format=apk_delta.FORMAT)
            with patch.object(apk_delta, "build", side_effect=legacy_build), \
                    patch.object(apk_archive_delta, "build", side_effect=fake_build):
                mixed = release.build_deltas(records[:2], target, 9, legacy_codes=[1])
            self.assertEqual([apk_delta.FORMAT, apk_archive_delta.FORMAT], [d["format"] for d in mixed])
            with self.assertRaises(release.ReleaseError):
                release.build_deltas(records[:2], target, 9, legacy_codes=[99])
            with self.assertRaises(release.ReleaseError):
                release.frozen_delta_pairs(records, [deltas[0]] * 8)
            with patch.object(apk_archive_delta, "build", side_effect=apk_archive_delta.NotApplicable()):
                with self.assertRaises(release.ReleaseError): release.build_deltas([record], target, 3)

    def test_start_requires_merged_main_and_dispatches_only_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / "app").mkdir()
            self.source_profile(root, "main", "0.6.8.7")
            calls = []
            class GitHub:
                def require_authentication(self): pass
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
            self.assertEqual({"expected_sha", "run_description", "release_channel"}, set(dispatches[0]["inputs"]))
            self.assertEqual("stable", dispatches[0]["inputs"]["release_channel"])

    def dispatch_command(self, auth_results, dispatch_results, calls):
        def fake_command(args, *, data=None, **kwargs):
            if args[0] == "git":
                output = {("status", "--porcelain"): b"", ("branch", "--show-current"): b"main",
                          ("rev-parse", "HEAD"): b"a" * 40, ("log", "-1", "--format=%s"): b"fix: delivery"}
                return subprocess.CompletedProcess(args, 0, output[tuple(args[1:])], b"")
            self.assertEqual("api", args[1])
            path = args[2].removeprefix(f"repos/{release.REPO}/")
            if path == "user":
                calls.append(("auth", None))
                error = auth_results.pop(0)
                return subprocess.CompletedProcess(args, bool(error), b"", error)
            if path.endswith("/dispatches"):
                calls.append(("dispatch", json.loads(data)))
                error = dispatch_results.pop(0)
                if isinstance(error, Exception):
                    raise error
                return subprocess.CompletedProcess(args, bool(error), b"", error)
            if path.startswith("git/ref/heads/"):
                return subprocess.CompletedProcess(args, 0, json.dumps({"object": {"sha": "a" * 40}}).encode(), b"")
            if path.startswith("git/ref/tags/"):
                return subprocess.CompletedProcess(args, 1, b"", b"gh: Not Found (HTTP 404)\n")
            self.assertIn("/runs?", path)
            calls.append(("runs", None))
            return subprocess.CompletedProcess(args, 0, b'{"workflow_runs":[]}', b"")
        return fake_command

    def test_start_checks_authentication_before_dispatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.source_profile(root, "main", "0.7.6.4")
            calls = []
            rejected = b"gh: Bad credentials (HTTP 401)\n"
            with patch.object(release, "command", self.dispatch_command([rejected, b""], [b""], calls)):
                with self.assertRaisesRegex(release.ReleaseError, "authentication"):
                    release.Release(root).start("0.7.6.4")
                self.assertEqual(["auth"], [kind for kind, _ in calls])
                self.assertIn("not dispatched", release.Release(root).load_status("0.7.6.4")["status"])
                release.Release(root).start("0.7.6.4")
            self.assertEqual(["auth", "auth", "dispatch", "runs"], [kind for kind, _ in calls])

    def test_start_retries_confirmed_dispatch_rejection_once_after_run_lookup(self):
        rejected = b"gh: Bad credentials (HTTP 401)\r\n"
        for second in (b"", rejected):
            with self.subTest(second_rejected=bool(second)), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                self.source_profile(root, "main", "0.7.6.4")
                calls = []
                with patch.object(release, "command", self.dispatch_command([b"", b""], [rejected, second], calls)):
                    with self.assertRaisesRegex(release.ReleaseError, "HTTP 401"):
                        release.Release(root).start("0.7.6.4", mandatory=True)
                    with self.assertRaisesRegex(release.ReleaseError, "mandatory"):
                        release.Release(root).start("0.7.6.4", mandatory=False)
                    if second:
                        with self.assertRaisesRegex(release.ReleaseError, "HTTP 401"):
                            release.Release(root).start("0.7.6.4")
                    else:
                        release.Release(root).start("0.7.6.4")
                    release.Release(root).start("0.7.6.4")
                kinds = [kind for kind, _ in calls]
                self.assertEqual(["auth", "dispatch", "runs", "auth", "dispatch"], kinds[:5])
                dispatches = [value for kind, value in calls if kind == "dispatch"]
                self.assertEqual(2, len(dispatches))
                self.assertEqual(dispatches[0], dispatches[1])

    def test_start_never_retries_uncertain_dispatch(self):
        errors = [b"gh: Bad Gateway (HTTP 502)\n", subprocess.TimeoutExpired("gh", 120)]
        for problem in errors:
            with self.subTest(problem=problem), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                self.source_profile(root, "main", "0.7.6.4")
                calls = []
                with patch.object(release, "command", self.dispatch_command([b""], [problem], calls)):
                    with self.assertRaises((release.ReleaseError, subprocess.TimeoutExpired)):
                        release.Release(root).start("0.7.6.4")
                    status = release.Release(root).start("0.7.6.4")
                self.assertIn("pending", status["status"])
                self.assertEqual(["auth", "dispatch", "runs"], [kind for kind, _ in calls])


if __name__ == "__main__": unittest.main()
