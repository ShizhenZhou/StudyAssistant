"""Create an allowlisted unsigned candidate artifact, using only clean CI outputs."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from release_common import apk_identity, apk_payload, sha256, validate_manifest
from verification import digest, validate_pre_main_evidence, validate_promotion

ROOT = Path(__file__).resolve().parents[1]
def main():
    if os.environ.get("CI") != "true":
        raise RuntimeError("Candidate creation is a CI-only operation")
    source = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    identity = apk_identity(subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(source)], text=True))
    check = subprocess.run([str(tools / "apksigner"), "verify", str(source)], capture_output=True)
    if check.returncode == 0:
        raise RuntimeError("CI release candidate must not carry an official signature")
    payload, stamp = apk_payload(source)
    if os.environ.get("TEST_BEFORE_MAIN") == "true":
        folder = ROOT / "app/build/verification"
        report = json.loads((folder / "pre-main/verification.json").read_text(encoding="utf-8"))
        run_id, attempt = int(os.environ["GITHUB_RUN_ID"]), int(os.environ["GITHUB_RUN_ATTEMPT"])
        validate_pre_main_evidence(report, run_id, attempt, os.environ["GITHUB_SHA"])
        validate_promotion(json.loads((folder / "promotion.json").read_text(encoding="utf-8")), report, run_id, attempt, os.environ["GITHUB_SHA"])
        totals = report["build"]["jvm"]
    else:
        totals = {k: 0 for k in ("tests", "failures", "errors", "skipped")}
        for file in (ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
            node = ET.parse(file).getroot()
            for key in totals: totals[key] += int(node.get(key, 0))
    if totals["tests"] < 1 or any(totals[key] for key in ("failures", "errors", "skipped")):
        raise RuntimeError("No successful full JVM report")
    code = (ROOT / "app/src/main/java/com/zsz/studyassistant/data/UpdateChecker.kt").read_text(encoding="utf-8")
    cert = re.search(r'EXPECTED_CERT_SHA256 = "([0-9a-f]{64})"', code)[1]
    plan = json.loads((ROOT / "app/build/verification/plan.json").read_text(encoding="utf-8"))
    manifest = dict(schema=2, **identity, commit=os.environ["GITHUB_SHA"], buildUtc=stamp, planSha256=digest(plan),
        candidate=f"StudyAssistant-{identity['version']}-{stamp}-candidate.apk",
        candidateSha256=sha256(source), payloadSha256=payload, expectedSigner=cert, jvm=totals)
    validate_manifest(manifest)
    folder = ROOT / "app/build/release-candidate"
    folder.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, folder / manifest["candidate"])
    (folder / "release-manifest.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(manifest))
if __name__ == "__main__": main()
