"""Create an unsigned candidate identity; schema 3 does not claim tests were run."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from release_common import apk_identity, apk_payload, sha256, validate_manifest

ROOT = Path(__file__).resolve().parents[1]
def main():
    ref = os.environ.get("GITHUB_REF")
    if os.environ.get("CI") != "true" or not (ref == "refs/heads/main" or
            (ref == "refs/heads/Develop" and os.environ.get("RELEASE_CHANNEL") == "beta")):
        raise RuntimeError("Candidate creation is a CI-only operation")
    source = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    identity = apk_identity(subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(source)], text=True))
    check = subprocess.run([str(tools / "apksigner"), "verify", str(source)], capture_output=True)
    if check.returncode == 0:
        raise RuntimeError("CI release candidate must not carry an official signature")
    payload, stamp = apk_payload(source)
    code = (ROOT / "app/src/main/java/com/zsz/studyassistant/data/UpdateChecker.kt").read_text(encoding="utf-8")
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
