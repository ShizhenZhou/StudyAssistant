"""Run selected isolated Android tests; existing APKs require matching provenance."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from verification import (BUILD_INPUTS, EvidenceStore, android_inventory, digest, environment_identity,
                          file_sha, git, instrumentation_results, select_tests, source_digest, validate_plan, write_json)

ROOT = Path(__file__).resolve().parents[1]
APK_PATHS = ("app/build/outputs/apk/integration/app-integration.apk",
             "app/build/outputs/apk/androidTest/integration/app-integration-androidTest.apk")
BUILD_RECORD = "app/build/outputs/apk/integration-build.json"


def output_text(value):
    # TimeoutExpired may contain bytes even when subprocess.run requested text mode.
    return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else (value or "")


def safe_diagnostic(value, limit=2000):
    text = output_text(value)
    text = re.sub(r"https?://[^\s\"'<>]+", "[url]", text, flags=re.I)
    text = re.sub(r"(?i)\bBearer\s+\S+|\b(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]+", "[credential]", text)
    text = re.sub(r"(?i)\b(?:token|api[_-]?key|authorization|password|secret)\s*[:=]\s*\S+", "[credential]", text)
    text = re.sub(r"(?<!\w)(?:[A-Za-z]:[\\/]|\\\\|/)[^\s\"'<>]+", "[path]", text)
    text = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]|[\x00-\x08\x0b-\x1f\x7f]", "", text)
    return text[-limit:]


def checked(args, timeout=60):
    tool = Path(str(args[0])).name
    try:
        result = subprocess.run([str(a) for a in args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired as error:
        detail = safe_diagnostic(error.stderr or error.stdout)
        raise RuntimeError(f"Android tool timed out: {tool}; after={timeout}s; {detail}") from None
    except OSError:
        raise RuntimeError(f"Android tool could not start: {tool}") from None
    if result.returncode:
        raise RuntimeError(f"Android tool failed: {tool}; exit={result.returncode}; {safe_diagnostic(result.stderr or result.stdout)}")
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


def execution_identity(build, report):
    # Runner/parser edits invalidate execution evidence without rebuilding unchanged APKs.
    implementation = source_digest(ROOT, ["tools/run-android-integration.py", "tools/verification.py"])
    return digest([build, report["api"], report["fingerprint"], report["abi"], environment_identity(), implementation])


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
    minimums = []
    for path, package in [(target, "com.zsz.studyassistant.integration"), (tests, "com.zsz.studyassistant.integration.test")]:
        badging = checked([aapt, "dump", "badging", path])
        if not re.search(r"^package: name='" + re.escape(package) + "'", badging, re.M):
            raise RuntimeError("Unexpected APK package. No installation performed.")
        minimum = re.search(r"(?m)^sdkVersion:\s*'(\d+)'", badging)
        if not minimum: raise ValueError("APK minimum SDK is unavailable; no installation performed")
        minimums.append(int(minimum[1]))
    manifest = checked([aapt, "dump", "xmltree", tests, "AndroidManifest.xml"])
    if not re.search(r'android:targetPackage[^\n]*"com\.zsz\.studyassistant\.integration"', manifest):
        raise RuntimeError("Unexpected instrumentation target. No installation performed.")
    build = load_build()
    chosen = select_tests(android_inventory(ROOT), selectors)
    if expected is not None and expected != len(chosen):
        raise ValueError("Expected test count cannot override the selected inventory")
    def prop(name): return checked([adb, "-s", serial, "shell", "getprop", name]).strip()
    actual_api = int(prop("ro.build.version.sdk"))
    if actual_api < max(minimums): raise ValueError("Emulator API is below actual APK minSdk; no installation performed")
    if api is not None and api != actual_api: raise ValueError("Selected emulator API differs from plan")
    report = dict(schema=1, commit=git(ROOT, "rev-parse", "HEAD"), runId=int(os.environ.get("GITHUB_RUN_ID", "0")),
                  attempt=int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")), api=actual_api,
                  targetMinSdk=minimums[0], testMinSdk=minimums[1],
                  fingerprint=prop("ro.build.fingerprint"), abi=prop("ro.product.cpu.abi"),
                  targetSha256=build["targetSha256"], testSha256=build["testSha256"], tests=chosen,
                  passed=[], skipped=[], success=False, stage="ready")
    report_path = report_path or ROOT / "app/build/android-integration" / f"evidence-api-{actual_api}.json"
    store = EvidenceStore(ROOT / ".local-notes/verification/android")
    identity = execution_identity(build, report)
    cached = None if force or os.environ.get("CI") == "true" else store.find(identity, chosen)
    if cached:
        report.update(success=True, passed=chosen, stage="cached")
        write_json(report_path, report)
        print("Reusing successful Android evidence; no installation or test execution")
        return report
    record = store.begin(identity, chosen)
    log = store.folder / record["log"]
    public_log = ROOT / "app/build/android-integration/instrumentation.log"
    text = ""
    write_json(report_path, report)
    try:
        for path, stage in ((target, "install-target"), (tests, "install-tests")):
            report["stage"] = stage
            checked([adb, "-s", serial, "install", "-r", path], timeout=120)
        report["stage"] = "instrumentation"
        # Only validated class#method tokens reach the device shell.
        result = subprocess.run([str(adb), "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(chosen),
                                 "com.zsz.studyassistant.integration.test/androidx.test.runner.AndroidJUnitRunner"],
                                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
        text = output_text(result.stdout) + output_text(result.stderr)
        if result.returncode: raise RuntimeError(f"Instrumentation process failed: exit={result.returncode}")
        report["stage"] = "result-validation"
        report.update(instrumentation_results(text, chosen))
        report["stage"] = "source-validation"
        load_build()  # Detect source/APK changes while the test was running.
        report["success"] = True
        report["stage"] = "complete"
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        if isinstance(error, subprocess.TimeoutExpired):
            text = output_text(error.stdout) + output_text(error.stderr)
            message = f"Instrumentation timed out after {error.timeout}s"
        else:
            message = safe_diagnostic(str(error))
        report["error"] = message
        diagnostic = f"Android checks failed at stage={report['stage']}: {message}"
        text += "\n" + diagnostic + "\n"
        print(diagnostic, file=sys.stderr)
        print("Instrumentation diagnostic tail:\n" + safe_diagnostic("\n".join(text.splitlines()[-40:]), limit=6000), file=sys.stderr)
        raise RuntimeError(diagnostic) from None
    finally:
        log.write_text(text or "Instrumentation did not complete\n", encoding="utf-8")
        public_log.parent.mkdir(parents=True, exist_ok=True)
        public_log.write_text(text or "Instrumentation did not complete\n", encoding="utf-8")
        store.finish(record, report["success"])
        write_json(report_path, report)
    print(f"Android checks passed: {len(chosen)}; API {actual_api}; isolated emulator only")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument("--test", action="append", default=[])
    scope.add_argument("--all", action="store_true", help="Explicitly execute every isolated Android test")
    parser.add_argument("--api", type=int)
    parser.add_argument("--expected-tests", type=int, help="Assert inventory count; never override discovery")
    scope.add_argument("--plan", type=Path, help="Use the selected API row from a verification plan")
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
        if not args.list and not (args.test or args.all or args.plan):
            raise ValueError("Select --test or --all explicitly before execution (CI may use --plan)")
        selectors = args.test
        if args.plan:
            plan = json.loads(args.plan.read_text(encoding="utf-8"))
            validate_plan(plan)
            rows = [r for r in plan["devices"] if r["api"] == args.api]
            if plan["commit"] != git(ROOT, "rev-parse", "HEAD") or len(rows) != 1: raise ValueError("Wrong plan/source/API")
            selectors = rows[0]["tests"]
            if not selectors: raise ValueError("Verification plan must select at least one test")
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
