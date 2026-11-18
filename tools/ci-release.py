"""Create an unsigned candidate identity; schema 3 does not claim tests were run."""
from release_common import android_build_tools
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from release_common import apk_identity, apk_payload, apk_distribution, release_branch_allowed, sha256, validate_manifest
from distribution_config import source_distribution, build_flavor

ROOT = Path(__file__).resolve().parents[1]
def main():
    ref = os.environ.get("GITHUB_REF")
    distribution = os.environ.get("RELEASE_DISTRIBUTION", "main")
    channel = os.environ.get("RELEASE_CHANNEL", "stable")
    if channel == "none" and ref == "refs/heads/main":
        channel = "stable"  # Manual main build retains the existing default behavior.
    profile = source_distribution(ROOT, distribution)
    flavor = build_flavor(distribution)
    if os.environ.get("CI") != "true" or not release_branch_allowed(
            (ref or "").removeprefix("refs/heads/"), channel, distribution):
        raise RuntimeError("Candidate creation is a CI-only operation")
    source = ROOT / f"app/build/outputs/apk/{flavor}/release/app-{flavor}-release-unsigned.apk"
    tools = android_build_tools(os.environ["ANDROID_HOME"], ROOT)
    identity = apk_identity(subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(source)], text=True))
    identity.update(apk_distribution(source))
    if (identity["version"], identity["versionCode"], identity["minSdk"]) != (profile["versionName"], profile["versionCode"], profile["minSdk"]):
        raise RuntimeError("APK identity differs from source distribution profile")
    if identity.get("distribution", "main") != distribution:
        raise RuntimeError("Requested distribution differs from APK payload")
    check = subprocess.run([str(tools / "apksigner"), "verify", str(source)], capture_output=True)
    if check.returncode == 0:
        raise RuntimeError("CI release candidate must not carry an official signature")
    payload, stamp = apk_payload(source)
    code = (ROOT / "app/src/main/java/com/zsz/studyassistant/data/update/UpdateChecker.kt").read_text(encoding="utf-8")
    cert = re.search(r'EXPECTED_CERT_SHA256 = "([0-9a-f]{64})"', code)[1]
    manifest = dict(schema=3, **identity, commit=os.environ["GITHUB_SHA"], buildUtc=stamp,
        candidate=f"StudyAssistant-{identity['version']}-{stamp}-candidate.apk",
        candidateSha256=sha256(source), payloadSha256=payload, expectedSigner=cert)
    validate_manifest(manifest)
    folder = ROOT / "app/build/release-candidate"
    folder.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, folder / manifest["candidate"])
    (folder / "release-manifest.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(manifest))
if __name__ == "__main__": main()
