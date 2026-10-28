"""Run selected isolated Android tests; existing APKs require matching provenance."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from verification import (BUILD_INPUTS, EvidenceStore, android_inventory, digest, environment_identity,
                          file_sha, git, instrumentation_results, select_tests, source_digest, write_json)

ROOT = Path(__file__).resolve().parents[1]
APK_PATHS = ("app/build/outputs/apk/integration/app-integration.apk",
             "app/build/outputs/apk/androidTest/integration/app-integration-androidTest.apk")
BUILD_RECORD = "app/build/outputs/apk/integration-build.json"


def checked(args, timeout=60):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        raise RuntimeError("Android tool failed: " + Path(str(args[0])).name)
    return result.stdout


def require_emulator_serial(serial):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("Only a local emulator serial is accepted.")


def record_build():
    record = dict(schema=1, commit=git(ROOT, "rev-parse", "HEAD"), inputs=source_digest(ROOT, BUILD_INPUTS),
                  environment=environment_identity(), targetSha256=file_sha(ROOT / APK_PATHS[0]), testSha256=file_sha(ROOT / APK_PATHS[1]))
    write_json(ROOT / BUILD_RECORD, record)
    return record


def load_build():
    record = json.loads((ROOT / BUILD_RECORD).read_text(encoding="utf-8"))
    if record.get("schema") != 1 or record.get("inputs") != source_digest(ROOT, BUILD_INPUTS):
        raise ValueError("APK source inputs changed; rebuild the isolated APKs")
    if any(record.get(key) != file_sha(ROOT / path) for key, path in zip(("targetSha256", "testSha256"), APK_PATHS)):
        raise ValueError("APK bytes differ from their build record")
    return record


def prepare_build(use_existing=False):
    try:
        record = load_build()
        if record.get("environment") == environment_identity():
            print("Reusing verified integration APKs")
            return record
        if use_existing: raise ValueError("Build environment changed")
    except (OSError, ValueError):
        if use_existing: raise ValueError("No matching existing APK pair; build explicitly first")
    before = source_digest(ROOT, BUILD_INPUTS)
    subprocess.run([str(ROOT / "gradlew.bat"), "assembleIntegration", "assembleIntegrationAndroidTest", "--console=plain",
                    "-Pkotlin.compiler.execution.strategy=in-process"], cwd=ROOT, check=True)
    if source_digest(ROOT, BUILD_INPUTS) != before:
        raise ValueError("Source changed during APK build")
    return record_build()


def expected_test_count():
    return len(android_inventory(ROOT))


def passed_count(text, expected):
    # Compatibility helper; production execution additionally checks every test identity below.
    declared = {int(n) for n in re.findall(r"INSTRUMENTATION_STATUS: numtests=(\d+)", text)}
    summaries = re.findall(r"(?m)^OK \(([1-9][0-9]*) tests?\)$", text)
    if (declared and declared != {expected}) or summaries != [str(expected)] or re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED", text):
        raise RuntimeError("Missing, failed or incomplete Android test summary")
    return expected


def run(serial, expected=None, selectors=(), api=None, force=False, report_path=None):
    require_emulator_serial(serial)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk: raise RuntimeError("Android SDK is not configured")
    suffix = ".exe" if os.name == "nt" else ""
    adb = Path(sdk) / "platform-tools" / ("adb" + suffix)
    aapt = Path(sdk) / "build-tools/36.0.0" / ("aapt" + suffix)
    if checked([adb, "-s", serial, "shell", "getprop", "ro.kernel.qemu"]).strip() != "1":
        raise RuntimeError("Selected device is not an emulator. No installation performed.")
    target, tests = [ROOT / path for path in APK_PATHS]
    for path, package in [(target, "com.zsz.studyassistant.integration"), (tests, "com.zsz.studyassistant.integration.test")]:
        badging = checked([aapt, "dump", "badging", path])
        if not re.search(r"^package: name='" + re.escape(package) + "'", badging, re.M):
            raise RuntimeError("Unexpected APK package. No installation performed.")
    manifest = checked([aapt, "dump", "xmltree", tests, "AndroidManifest.xml"])
    if not re.search(r'android:targetPackage[^\n]*"com\.zsz\.studyassistant\.integration"', manifest):
        raise RuntimeError("Unexpected instrumentation target. No installation performed.")
    build = load_build()
    chosen = select_tests(android_inventory(ROOT), selectors)
    if expected is not None and expected != len(chosen):
        raise ValueError("Expected test count cannot override the selected inventory")
    def prop(name): return checked([adb, "-s", serial, "shell", "getprop", name]).strip()
    actual_api = int(prop("ro.build.version.sdk"))
    if api is not None and api != actual_api: raise ValueError("Selected emulator API differs from plan")
    report = dict(schema=1, commit=git(ROOT, "rev-parse", "HEAD"), runId=int(os.environ.get("GITHUB_RUN_ID", "0")),
                  attempt=int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")), api=actual_api,
                  fingerprint=prop("ro.build.fingerprint"), abi=prop("ro.product.cpu.abi"),
                  targetSha256=build["targetSha256"], testSha256=build["testSha256"], tests=chosen,
                  passed=[], skipped=[], success=False)
    report_path = report_path or ROOT / "app/build/android-integration" / f"evidence-api-{actual_api}.json"
    store = EvidenceStore(ROOT / ".local-notes/verification/android")
    identity = digest([build, report["api"], report["fingerprint"], report["abi"], environment_identity()])
    cached = None if force or os.environ.get("CI") == "true" else store.find(identity, chosen)
    if cached:
        report.update(success=True, passed=chosen)
        write_json(report_path, report)
        print("Reusing successful Android evidence; no installation or test execution")
        return report
    record = store.begin(identity, chosen)
    log = store.folder / record["log"]
    write_json(report_path, report)
    try:
        for path in (target, tests): checked([adb, "-s", serial, "install", "-r", path], timeout=120)
        # Only validated class#method tokens reach the device shell.
        result = subprocess.run([str(adb), "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(chosen),
                                 "com.zsz.studyassistant.integration.test/androidx.test.runner.AndroidJUnitRunner"],
                                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
        text = result.stdout + result.stderr
        log.write_text(text, encoding="utf-8")
        public_log = ROOT / "app/build/android-integration/instrumentation.log"
        public_log.parent.mkdir(parents=True, exist_ok=True)
        public_log.write_text(text, encoding="utf-8")
        if result.returncode: raise RuntimeError("Instrumentation process failed")
        report.update(instrumentation_results(text, chosen))
        load_build()  # Detect source/APK changes while the test was running.
        report["success"] = True
    finally:
        if not log.exists(): log.write_text("Instrumentation did not complete\n", encoding="utf-8")
        store.finish(record, report["success"])
        write_json(report_path, report)
    print(f"Android checks passed: {len(chosen)}; API {actual_api}; isolated emulator only")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--test", action="append", default=[])
    parser.add_argument("--api", type=int)
    parser.add_argument("--expected-tests", type=int, help="Assert inventory count; never override discovery")
    parser.add_argument("--plan", type=Path, help="Use the selected API row from a verification plan")
    parser.add_argument("--list", action="store_true", help="Read-only test selection preview")
    parser.add_argument("--record-build", action="store_true", help="CI only: record freshly built APK provenance")
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--use-existing", action="store_true")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    try:
        if args.record_build:
            if os.environ.get("CI") != "true": raise ValueError("--record-build requires CI")
            record_build()
            return 0
        selectors = args.test
        if args.plan:
            plan = json.loads(args.plan.read_text(encoding="utf-8"))
            rows = [r for r in plan["devices"] if r["api"] == args.api]
            if plan["commit"] != git(ROOT, "rev-parse", "HEAD") or len(rows) != 1: raise ValueError("Wrong plan/source/API")
            selectors = rows[0]["tests"]
        chosen = select_tests(android_inventory(ROOT), selectors)
        if args.list:
            print(json.dumps(chosen, indent=2))
            return 0
        require_emulator_serial(args.serial or "")
        if args.prepare: prepare_build(args.use_existing)
        run(args.serial, args.expected_tests, chosen, args.api, args.force)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__": sys.exit(main())
