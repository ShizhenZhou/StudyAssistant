"""Execute only the isolated integration APKs on one explicitly selected emulator."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def checked(args, timeout=60):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        raise RuntimeError("Android tool failed: " + Path(str(args[0])).name)
    return result.stdout

def require_emulator_serial(serial):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("Only a local emulator serial is accepted.")

def expected_test_count():
    # This project uses ordinary JUnit4 @Test methods, not parameterized suites.
    count = 0
    for path in (ROOT / "app/src/androidTest").rglob("*.kt"):
        source = re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.DOTALL)
        source = re.sub(r"//[^\n]*", "", source)
        count += len(re.findall(r"(?m)^\s*@Test\b", source))
    if count < 1:
        raise RuntimeError("No source Android tests discovered.")
    return count

def passed_count(text, expected):
    declared = {int(n) for n in re.findall(r"INSTRUMENTATION_STATUS: numtests=([0-9]+)", text)}
    if declared and declared != {expected}:
        raise RuntimeError("Runner discovery does not match the expected test suite.")
    match = re.search(r"^OK \(([1-9][0-9]*) tests?\)$", text, re.MULTILINE)
    if not match or re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED", text):
        raise RuntimeError("Android tests failed or returned no passing summary.")
    count = int(match.group(1))
    if count != expected:
        raise RuntimeError(f"Expected {expected} Android tests, received {count}.")
    return count

def run(serial, expected):
    require_emulator_serial(serial)  # Before inspecting SDK, connecting or writing anything.
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("Android SDK is not configured.")
    suffix = ".exe" if os.name == "nt" else ""
    adb = Path(sdk) / "platform-tools" / ("adb" + suffix)
    aapt = Path(sdk) / "build-tools/36.0.0" / ("aapt" + suffix)
    if checked([adb, "-s", serial, "shell", "getprop", "ro.kernel.qemu"]).strip() != "1":
        raise RuntimeError("Selected device is not an emulator. No installation performed.")
    target = ROOT / "app/build/outputs/apk/integration/app-integration.apk"
    tests = ROOT / "app/build/outputs/apk/androidTest/integration/app-integration-androidTest.apk"
    for path, package in [(target, "com.zsz.studyassistant.integration"), (tests, "com.zsz.studyassistant.integration.test")]:
        badging = checked([aapt, "dump", "badging", path])
        if not re.search(r"^package: name='" + re.escape(package) + "'", badging, re.MULTILINE):
            raise RuntimeError("Unexpected APK package. No installation performed.")
    manifest = checked([aapt, "dump", "xmltree", tests, "AndroidManifest.xml"])
    if not re.search(r'android:targetPackage[^\n]*"com\.zsz\.studyassistant\.integration"', manifest):
        raise RuntimeError("Unexpected instrumentation target. No installation performed.")
    for path in (target, tests):
        checked([adb, "-s", serial, "install", "-r", path], timeout=120)
    result = checked([adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "com.zsz.studyassistant.integration.test/androidx.test.runner.AndroidJUnitRunner"], timeout=600)
    folder = ROOT / "app/build/android-integration"
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "instrumentation.log").write_text(result, encoding="utf-8")
    count = passed_count(result.replace("\r\n", "\n"), expected)
    print(f"Android integration tests passed: {count}; selected emulator only.")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-tests", type=int, help="Override ordinary JUnit4 source discovery")
    args = parser.parse_args()
    try:
        expected = args.expected_tests if args.expected_tests is not None else expected_test_count()
        if expected < 1:
            raise ValueError("Expected test count must be positive.")
        run(args.serial, expected)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
