"""Prepare a CI test plan and aggregate public evidence; never dispatches CI."""
import argparse
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
from verification import ROOT, digest, make_plan, validate_ci_evidence, write_json

FOLDER = ROOT / "app/build/verification"


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def plan():
    base = os.environ.get("BASE_SHA") or None
    if base == "0" * 40: base = None  # First push has no usable parent.
    value = make_plan(base=base)
    value["mode"] = os.environ.get("MODE", "check")
    if value["mode"] == "check": value["devices"] = []
    write_json(FOLDER / "plan.json", value)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
            compile_only = value["mode"] == "check" and (not value.get("base") or any(
                path.startswith(("app/src/androidTest/", "app/src/integration/")) for path in value["changedFiles"]))
            out.write("compile_android_tests=" + ("true" if compile_only else "false") + "\n")
            out.write("devices=" + ("true" if value["devices"] else "false") + "\n")
            out.write("matrix=" + json.dumps({"include": [p for p in value["platformConstraints"]["platforms"] if p["api"] in [r["api"] for r in value["devices"]]] or [dict(api=36, imageApi="36", target="google_apis", arch="x86_64")]}) + "\n")
    print(json.dumps(value, indent=2))


def build():
    value = read(FOLDER / "plan.json")
    totals = {k: 0 for k in ("tests", "failures", "errors", "skipped")}
    for file in (ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
        node = ET.parse(file).getroot()
        for key in totals: totals[key] += int(node.get(key, 0))
    if totals["tests"] < 1 or any(totals[k] for k in ("failures", "errors", "skipped")):
        raise ValueError("Full JVM verification incomplete")
    manifest = read(ROOT / "app/build/release-candidate/release-manifest.json") if os.environ["MODE"] == "release" else None
    value = dict(commit=value["commit"], runId=int(os.environ["GITHUB_RUN_ID"]), attempt=int(os.environ["GITHUB_RUN_ATTEMPT"]),
                 planSha256=digest(value), candidateSha256=manifest["candidateSha256"] if manifest else None, jvm=totals,
                 apks=read(ROOT / "app/build/outputs/apk/integration-build.json") if value["devices"] else None)
    write_json(FOLDER / "build.json", value)


def collect():
    needs = json.loads(os.environ["NEEDS_JSON"])
    for name in ("build", "windows-tools"):
        if needs.get(name, {}).get("result") != "success": raise ValueError(name + " did not succeed")
    value = read(FOLDER / "plan.json")
    required = "success" if value["devices"] else "skipped"
    if needs.get("android-device", {}).get("result") != required: raise ValueError("Device jobs do not match plan")
    current = make_plan(base=value.get("base"))
    current["mode"] = value.get("mode", "check")
    if current["mode"] == "check": current["devices"] = []
    if current != value: raise ValueError("CI plan differs from actual source policy")
    build_report = read(FOLDER / "build.json")
    report = dict(schema=1, runId=int(os.environ["GITHUB_RUN_ID"]), attempt=int(os.environ["GITHUB_RUN_ATTEMPT"]),
                  success=True, plan=value, build=build_report,
                  devices=[read(path) for path in sorted((FOLDER / "devices").glob("evidence-api-*.json"))])
    manifest = dict(commit=value["commit"], candidateSha256=build_report["candidateSha256"], planSha256=digest(value))
    validate_ci_evidence(report, manifest, report["runId"], report["attempt"])
    write_json(FOLDER / "verification.json", report)
    print("Required verification evidence is complete")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["plan", "build", "collect"])
    args = parser.parse_args()
    if os.environ.get("CI") != "true": raise ValueError("CI-only evidence writer")
    {"plan": plan, "build": build, "collect": collect}[args.operation]()


if __name__ == "__main__": main()
