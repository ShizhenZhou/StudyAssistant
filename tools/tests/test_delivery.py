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
import apk_delta
import apk_archive_delta
from release_common import apk_identity, apk_payload, extract_candidate, sha256, validate_manifest


class DeliveryTest(unittest.TestCase):
    def test_public_asset_failure_uses_one_official_alternate(self):
        from types import SimpleNamespace
        github = release.GitHub()
        url = "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v0.7.1/fixture.apk"
        failed = SimpleNamespace(returncode=22, stdout=b"")
        ok = SimpleNamespace(returncode=0, stdout=b"verified by caller")
        with patch.object(release.shutil, "which", return_value="curl.exe"), \
             patch.object(github, "api", return_value={"browser_download_url": url}), \
             patch.object(release, "command", side_effect=[failed, ok]) as command:
            self.assertEqual(ok.stdout, github.download("repos/ShizhenZhou/StudyAssistant/releases/assets/123?download=1", binary=True))
            self.assertEqual(2, command.call_count)
            self.assertEqual(url, command.call_args.args[0][-1])
        with patch.object(release.shutil, "which", return_value="curl.exe"), \
             patch.object(github, "api", return_value={"browser_download_url": "https://example.com/private"}), \
             patch.object(release, "command", return_value=failed) as command:
            with self.assertRaises(release.ReleaseError):
                github.download("repos/ShizhenZhou/StudyAssistant/releases/assets/123?download=1", binary=True)
            self.assertEqual(1, command.call_count)

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
            assets = [dict(name=name, size=path.stat().st_size, digest="sha256:" + sha256(path)) for name, path in files.items()]
            release.validate_release_assets(assets, files, complete=True)
            with self.assertRaises(release.ReleaseError): release.validate_release_assets(assets[:-1], files, complete=True)
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
