"""Prepare a CI test plan and aggregate public evidence; never dispatches CI."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import subprocess
import time
import xml.etree.ElementTree as ET
from verification import (ROOT, digest, make_plan, validate_ci_evidence, write_json,
                          validate_pre_main_evidence, validate_promotion, pre_main_gate, promotion_context)

FOLDER = ROOT / "app/build/verification"


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", "repos/" + os.environ["GITHUB_REPOSITORY"] + "/" + path], text=True))


def pages(path, field=None):
    result = []
    for page in range(1, 101):
        value = api(f"{path}{'&' if '?' in path else '?'}per_page=100&page={page}")
        batch = value[field] if field else value
        result.extend(batch)
        if len(batch) < 100: return result
    raise ValueError("Remote evidence pagination exceeds bound")


def plan():
    base = os.environ.get("BASE_SHA") or None
    if base == "0" * 40: base = None  # First push has no usable parent.
    value = make_plan(base=base)
    value["mode"] = os.environ.get("MODE", "check")
    if os.environ.get("TEST_BEFORE_MAIN") == "true":
        before = os.environ.get("PROMOTION_BASE_SHA", "")
        if (value["mode"] != "release" or os.environ["GITHUB_REF"] != "refs/heads/Develop" or
            before == value["commit"] or api("git/ref/heads/main")["object"]["sha"] != before):
            raise ValueError("Staged release must start on Develop before main promotion")
        write_json(FOLDER / "staged.json", dict(sourceBranch="Develop", mainBefore=before))
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
    manifest = read(ROOT / "app/build/release-candidate/release-manifest.json") if os.environ["MODE"] == "release" and os.environ.get("TEST_BEFORE_MAIN") != "true" else None
    value = dict(commit=value["commit"], runId=int(os.environ["GITHUB_RUN_ID"]), attempt=int(os.environ["GITHUB_RUN_ATTEMPT"]),
                 planSha256=digest(value), candidateSha256=manifest["candidateSha256"] if manifest else None, jvm=totals,
                 apks=read(ROOT / "app/build/outputs/apk/integration-build.json") if value["devices"] else None)
    write_json(FOLDER / "build.json", value)


def collect(pre_main=False):
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
    if pre_main: report["staged"] = read(FOLDER / "staged.json")
    manifest = dict(commit=value["commit"], candidateSha256=build_report["candidateSha256"], planSha256=digest(value))
    validate_ci_evidence(report, manifest, report["runId"], report["attempt"])
    if pre_main: validate_pre_main_evidence(report, report["runId"], report["attempt"], value["commit"])
    write_json(FOLDER / "verification.json", report)
    print("Required verification evidence is complete")


def await_promotion():
    report = read(FOLDER / "pre-main/verification.json")
    run_id, attempt, commit = int(os.environ["GITHUB_RUN_ID"]), int(os.environ["GITHUB_RUN_ATTEMPT"]), os.environ["GITHUB_SHA"]
    validate_pre_main_evidence(report, run_id, attempt, commit)
    current = make_plan(base=report["plan"].get("base")); current["mode"] = "release"
    if current != report["plan"]: raise ValueError("Staged source plan changed")
    write_json(FOLDER / "plan.json", report["plan"])
    gate = pre_main_gate(pages(f"actions/runs/{run_id}/jobs?filter=all", "jobs"), report)
    deadline = time.monotonic() + 30 * 60
    while True:
        main = api("git/ref/heads/main")["object"]["sha"]
        if main not in (commit, report["staged"]["mainBefore"]):
            raise ValueError("main moved to an unrelated source during staged release")
        statuses = pages(f"commits/{commit}/statuses")
        found = [s for s in statuses if s.get("context") == promotion_context(run_id, report["attempt"])]
        if main == commit and found:
            status = max(found, key=lambda row: row["id"])
            status = {key: status[key] for key in ("id", "state", "context", "target_url", "description", "created_at")}
            promotion = dict(schema=1, runId=run_id, commit=commit, mainSha=main,
                             mainBefore=report["staged"]["mainBefore"], preMainSha256=digest(report), gate=gate,
                             status=status, observedAt=datetime.now(timezone.utc).isoformat())
            validate_promotion(promotion, report, run_id, attempt, commit)
            write_json(FOLDER / "promotion.json", promotion)
            print("Exact tested source was promoted after the successful pre-main gate")
            return
        if time.monotonic() >= deadline: raise ValueError("Timed out waiting for explicit coordinator promotion; rerun only the failed job")
        time.sleep(15)


def collect_staged():
    needs = json.loads(os.environ["NEEDS_JSON"])
    if any(needs.get(name, {}).get("result") != "success" for name in ("build", "windows-tools", "pre-main-verification", "candidate")):
        raise ValueError("Staged release jobs did not succeed")
    pre_main = read(FOLDER / "pre-main/verification.json")
    required = "success" if pre_main["plan"]["devices"] else "skipped"
    if needs.get("android-device", {}).get("result") != required:
        raise ValueError("Final device jobs do not match the original pre-main plan")
    manifest = read(ROOT / "app/build/release-candidate/release-manifest.json")
    report = {key: value for key, value in pre_main.items() if key != "staged"}
    report.update(attempt=int(os.environ["GITHUB_RUN_ATTEMPT"]), preMain=pre_main,
                  build=dict(pre_main["build"], candidateSha256=manifest["candidateSha256"]),
                  promotion=read(FOLDER / "promotion.json"))
    current = make_plan(base=report["plan"].get("base")); current["mode"] = "release"
    if current != report["plan"]: raise ValueError("Final staged source plan changed")
    validate_ci_evidence(report, manifest, int(os.environ["GITHUB_RUN_ID"]), report["attempt"])
    gate = pre_main_gate(pages(f"actions/runs/{report['runId']}/jobs?filter=all", "jobs"), pre_main)
    if gate != report["promotion"]["gate"] or api("git/ref/heads/main")["object"]["sha"] != manifest["commit"]:
        raise ValueError("Promotion gate/main changed after candidate creation")
    statuses = [s for s in pages(f"commits/{manifest['commit']}/statuses") if s.get("context") == report["promotion"]["status"]["context"]]
    if not statuses or {key: max(statuses, key=lambda row: row["id"]).get(key) for key in report["promotion"]["status"]} != report["promotion"]["status"]:
        raise ValueError("Promotion status changed after candidate creation")
    write_json(FOLDER / "verification.json", report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["plan", "build", "collect", "pre-main", "await-promotion", "collect-staged"])
    args = parser.parse_args()
    if os.environ.get("CI") != "true": raise ValueError("CI-only evidence writer")
    {"plan": plan, "build": build, "collect": collect, "pre-main": lambda: collect(True),
     "await-promotion": await_promotion, "collect-staged": collect_staged}[args.operation]()


if __name__ == "__main__": main()
