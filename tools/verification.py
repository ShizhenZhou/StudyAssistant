"""Shared, conservative verification policy. No build/device/network side effects on import."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
POLICY = 4
MAIN = "app/src/main/java/com/zsz/studyassistant/"
ANDROID = "app/src/androidTest/"

# Reviewed presentation/pure calculation modules. Adding Android/system behavior here
# requires removing the exemption; tool regression checks that platform dependencies stay absent.
JVM_ONLY_PATHS = {MAIN + name for name in (
    "ui/L10nTables.kt", "ui/ThemeColors.kt", "ui/SmoothShape.kt", "ui/CropMath.kt",
    "data/GradeVerdict.kt", "data/BuildIdentity.kt", "data/CalendarDates.kt"
)}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def file_sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root).decode("utf-8").strip()


def source_digest(root, prefixes=None):
    """Hash Git-visible bytes, including deletions/untracked sources, never ignored local data."""
    names = git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard").split("\0")
    values = []
    for name in sorted(set(filter(None, names))):
        if prefixes and not any(name == p or name.startswith(p.rstrip("/") + "/") for p in prefixes):
            continue
        path = Path(root) / name
        if path.is_symlink():
            values.append((name, "symlink", os.readlink(path)))
        else:
            values.append((name, file_sha(path) if path.is_file() else "deleted"))
    return digest(values)


# Test-only and prose edits do not change production APK bytes. Tool implementations
# are separate inputs for the actions they control, rather than every Gradle output.
GRADLE_INPUTS = ["gradle", "gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "app/build.gradle.kts", "app/proguard-rules.pro"]
MAIN_INPUTS = GRADLE_INPUTS + ["app/src/main"]
BUILD_INPUTS = MAIN_INPUTS + ["app/src/integration", "app/src/androidTest"]


def task_inputs(tasks):
    values = set(MAIN_INPUTS)
    if any("UnitTest" in t for t in tasks): values.update(["app/src/test", "README.md", "docs/readme"])
    if any("Integration" in t for t in tasks): values.update(["app/src/integration", "app/src/androidTest"])
    if any("Release" in t for t in tasks): values.add("app/src/release")
    if any("Debug" in t for t in tasks): values.add("app/src/debug")
    return sorted(values)


def platform_constraints(min_sdk, policy=POLICY):
    if policy not in (2, 3, 4):
        raise ValueError("Unknown verification policy")
    if not isinstance(min_sdk, int) or min_sdk < 1 or min_sdk > 37:
        raise ValueError("Unsupported actual minSdk")
    apis = [35, 36] if min_sdk <= 35 else ([36, 37] if policy == 2 else [36]) if min_sdk == 36 else [37]
    return dict(minSdk=min_sdk, abi="x86_64", platforms=[dict(api=api, imageApi="37.0" if api == 37 else str(api), target="google_apis", arch="x86_64") for api in apis])


def actual_min_sdk(root=ROOT):
    text = (Path(root) / "app/build.gradle.kts").read_text(encoding="utf-8")
    values = re.findall(r"(?m)^\s*minSdk\s*=\s*(\d+)\s*$", text)
    if len(values) != 1: raise ValueError("Cannot establish actual minSdk from source")
    return int(values[0])


def validate_plan(plan):
    policy = plan.get("policy")
    if policy == 1:
        constraints = platform_constraints(28)  # Archived policy 1 always used API35/36.
    elif policy in (2, 3, POLICY):
        constraints = plan.get("platformConstraints", {})
        if constraints != platform_constraints(constraints.get("minSdk"), policy):
            raise ValueError("Invalid platform constraints")
    else:
        raise ValueError("Unknown verification policy")
    apis = [r.get("api") for r in plan["devices"]]
    allowed = [r["api"] for r in constraints["platforms"]]
    if len(set(apis)) != len(apis) or any(api not in allowed for api in apis):
        raise ValueError("Plan API constraints mismatch")
    if policy in (2, 3, POLICY):
        inventory = plan.get("inventory")
        if not inventory or inventory != sorted(set(inventory)):
            raise ValueError("Missing exact test inventory")
        expected = device_plan(plan["policyPaths"], inventory, bool(plan.get("base")), constraints["minSdk"], plan.get("platformMigration", False), policy)
        if plan.get("mode") == "check": expected = []
        if plan["devices"] != expected: raise ValueError("Device plan differs from declared policy inputs")
    return constraints


def environment_identity():
    # Values/paths never go in public reports; hashes only. CI device evidence uses getprop instead.
    values = {k: os.environ.get(k, "") for k in ("JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME", "JAVA_OPTS", "GRADLE_OPTS", "PATH")}
    for key, name in [("JAVA_HOME", "release"), ("ANDROID_HOME", "build-tools/36.0.0/source.properties"),
                      ("ANDROID_HOME", "platforms/android-37/source.properties"),
                      ("ANDROID_HOME", "platforms/android-37.0/source.properties")]:
        path = Path(values[key]) / name if values[key] else None
        values[key + "/" + name] = file_sha(path) if path and path.is_file() else "missing"
    return digest([platform.platform(), sys.version, values])


def android_inventory(root=ROOT):
    tests = []
    for path in sorted((Path(root) / ANDROID).rglob("*.kt")):
        source = re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S)
        source = re.sub(r"//[^\n]*", "", source)
        annotations = len(re.findall(r"(?m)^\s*@Test\b", source))
        if not annotations:
            continue
        package = re.search(r"(?m)^package\s+([\w.]+)", source)
        classes = re.findall(r"(?m)^class\s+(\w+)", source)
        methods = re.findall(r"@Test\s+(?:\([^)]*\)\s*)?fun\s+(\w+)\s*\(", source)
        if not package or len(classes) != 1 or len(methods) != annotations:
            raise ValueError("Unsupported Android test declaration: " + path.name)
        tests.extend(package[1] + "." + classes[0] + "#" + method for method in methods)
    if not tests or len(set(tests)) != len(tests):
        raise ValueError("Empty or duplicate Android test inventory")
    return sorted(tests)


def select_tests(inventory, selectors=()):
    if not selectors:
        return sorted(inventory)
    chosen = set()
    for selector in selectors:
        matches = [t for t in inventory if t == selector or t.split("#")[0] == selector]
        if not matches:
            raise ValueError("Unknown test selector: " + selector)
        chosen.update(matches)
    return sorted(chosen)


def device_plan(changed, inventory, baseline_known=True, min_sdk=28, platform_migration=False, policy=POLICY):
    """Only audited narrow mappings may reduce scope; unknown paths require full coverage."""
    groups = set()
    full = not baseline_known or platform_migration
    required_apis = [p["api"] for p in platform_constraints(min_sdk, policy)["platforms"]]
    for path in changed:
        if path.startswith(("docs/", "app/src/test/")) or path in (
            "README.md", "README.en.md", "README.ja.md", "README.ko.md", "README.zh-TW.md",
            "AGENTS.md", "DATA_SCHEMA.md", "@version-only"
        ):
            continue
        if path in (MAIN + "ui/L10n.kt", MAIN + "ui/Changelog.kt"):
            continue
        if policy >= 4 and path in JVM_ONLY_PATHS:
            continue
        if path.startswith("tools/") or path.startswith(".githooks/") or path == ".gitignore" or (policy >= 4 and path.startswith(".github/")):
            # Legacy plans required devices for runner/policy changes; policy4 validates tools in CI.
            if policy <= 3 and path in ("tools/run-android-integration.py", "tools/run-android-tests.ps1", "tools/verification.py", "tools/ci-verification.py"):
                full = True
            continue
        if path in {MAIN + "data/" + n + ".kt" for n in ("UpdateDownloadMonitor", "UpdateDownloadNotifications", "UpdateDownloads")}:
            groups.add("notifications")
        elif path in {MAIN + p for p in ("data/CaptureFlowStore.kt", "ui/CameraScreen.kt", "ui/CropScreen.kt", "ui/CaptureRecoveryGate.kt")}:
            groups.add("capture")
        elif path.startswith(ANDROID) and path.endswith("Test.kt"):
            name = Path(path).stem
            if name in ("UpdateDownloadsIntegrationTest", "UpdatePageIntegrationTest", "UpdateDownloadNotificationsIntegrationTest"):
                groups.add("notifications")  # FakeTransfers is shared by notifications and the update page.
                continue
            if name not in ("UpdateDownloadNotificationsIntegrationTest", "CaptureFlowStoreIntegrationTest", "CaptureRecoveryIntegrationTest", "CaptureUiRegressionTest"):
                full = True  # Other files may own shared fixtures; no filename-only dependency inference.
                continue
            matches = [t for t in inventory if t.split("#")[0].endswith("." + name)]
            if matches:
                groups.update(matches)
            else:
                full = True  # Deleted/renamed/shared test input cannot silently remove coverage.
        else:
            full = True
    if full:
        return [dict(api=api, tests=sorted(inventory), reason="full: unknown/shared inputs or no verified baseline") for api in required_apis]
    selected = {t for t in groups if "#" in t}
    if "notifications" in groups:
        selected.update(t for t in inventory if any(c in t for c in (".UpdateDownloadsIntegrationTest#", ".UpdateDownloadNotificationsIntegrationTest#", ".UpdatePageIntegrationTest#"))
                        or ".UiSmokeTest#updateNotification" in t
                        or t.endswith(".UiSmokeTest#updatePageRestoresWaitingAndCompletedTasksAfterLeaving"))
    if "capture" in groups:
        selected.update(t for t in inventory if any(c in t for c in (".CaptureFlowStoreIntegrationTest#", ".CaptureRecoveryIntegrationTest#", ".CaptureUiRegressionTest#")))
    if groups and not selected:
        raise ValueError("Required device group has no tests")
    apis = required_apis if "notifications" in groups or any("#" in g for g in groups) else [max(required_apis)]
    return [dict(api=api, tests=sorted(selected), reason="affected device paths") for api in apis] if selected else []


def changed_files(root, base):
    if not base or base.startswith("-"):
        raise ValueError("A valid base reference is required")
    base_sha = git(root, "rev-parse", "--verify", base + "^{commit}")
    subprocess.run(["git", "merge-base", "--is-ancestor", base_sha, "HEAD"], cwd=root, check=True)
    names = set(filter(None, git(root, "diff", "--name-only", "--no-renames", "-z", base_sha).split("\0")))
    names.update(filter(None, git(root, "ls-files", "--others", "--exclude-standard", "-z").split("\0")))
    return base_sha, sorted(names)


def make_plan(root=ROOT, base=None):
    commit = git(root, "rev-parse", "HEAD")
    base_sha, changed = changed_files(root, base) if base else (None, [])
    policy_paths = list(changed)
    if base_sha and "app/build.gradle.kts" in changed:
        before = git(root, "show", base_sha + ":app/build.gradle.kts")
        after = (Path(root) / "app/build.gradle.kts").read_text(encoding="utf-8").strip()
        def without_version(text):
            return re.sub(r'(?m)^(\s*(?:val appVersionName\s*=|versionCode\s*=?)\s*).+$', r'\1<version>', text.replace("\r\n", "\n"))
        if without_version(before) == without_version(after):
            policy_paths[policy_paths.index("app/build.gradle.kts")] = "@version-only"
    min_sdk = actual_min_sdk(root)
    migration = False
    if base_sha:
        previous = git(root, "show", base_sha + ":app/build.gradle.kts")
        old = re.search(r"(?m)^\s*minSdk\s*=\s*(\d+)\s*$", previous)
        migration = not old or int(old[1]) != min_sdk
    inventory = android_inventory(root)
    return dict(schema=1, policy=POLICY, commit=commit, base=base_sha, changedFiles=changed,
                policyPaths=policy_paths, inventory=inventory, platformMigration=migration,
                platformConstraints=platform_constraints(min_sdk),
                devices=device_plan(policy_paths, inventory, bool(base_sha), min_sdk, migration))


def instrumentation_results(text, expected):
    text = text.replace("\r\n", "\n")
    if any(x in text for x in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED")):
        raise ValueError("Instrumentation failed")
    declared = {int(n) for n in re.findall(r"INSTRUMENTATION_STATUS: numtests=(\d+)", text)}
    summary = re.findall(r"(?m)^OK \(([1-9][0-9]*) tests?\)$", text)
    if declared != {len(expected)} or summary != [str(len(expected))]:
        raise ValueError("Runner discovery/summary differs from selected tests")
    seen = []
    status = {}
    for line in text.splitlines():
        match = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
        if match:
            status[match[1]] = match[2]
        code = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)$", line)
        if code:
            value = int(code[1])
            if value != 1:
                if value != 0 or set(status) != {"class", "test"}:
                    raise ValueError("Failed, skipped, or unidentified Android test")
                seen.append(status["class"] + "#" + status["test"])
            status = {}
    if sorted(seen) != sorted(expected):
        raise ValueError("Executed test identities differ from selected tests")
    return dict(passed=sorted(seen), skipped=[])


def reusable_record(records, identity, tests):
    required = set(tests)
    for record in sorted(records, key=lambda r: r.get("order", 0), reverse=True):
        if record.get("identity") != identity:
            continue
        covered = set(record.get("tests", []))
        if not record.get("success") and required & covered:
            return None
        if record.get("success") and required <= covered:
            return record
    return None


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


class EvidenceStore:
    """Local-only evidence. Incomplete/failed latest attempts block stale successful records."""
    def __init__(self, folder):
        self.folder = Path(folder)

    def find(self, identity, tests, family=None):
        records = []
        for path in self.folder.glob("*.json"):
            try:
                record = json.loads(path.read_text(encoding="utf-8"))
                if record.get("success"):
                    log = self.folder / record["log"]
                    if log.parent != self.folder or not log.is_file() or file_sha(log) != record.get("logSha256"):
                        record["success"] = False
                records.append(record)
            except (OSError, ValueError, KeyError, TypeError):
                return None  # Damaged history must not resurrect an older pass.
        found = reusable_record(records, identity, tests)
        if found and family and any(r.get("family") == family and not r.get("success") and r.get("order", 0) > found["order"] for r in records):
            return None
        return found

    def begin(self, identity, tests, family=None):
        order = time.time_ns()
        record = dict(schema=1, identity=identity, family=family, tests=sorted(tests), success=False, order=order, log=str(order) + ".log")
        write_json(self.folder / (str(order) + ".json"), record)
        return record

    def finish(self, record, success):
        record.update(success=bool(success), logSha256=file_sha(self.folder / record["log"]))
        write_json(self.folder / (str(record["order"]) + ".json"), record)


def validate_device_evidence(plan, artifact, reports, run_id, attempt):
    expected = {item["api"]: item for item in plan["devices"]}
    if len(expected) != len(plan["devices"]): raise ValueError("Duplicate plan API")
    if len(reports) != len(expected) or len({r.get("api") for r in reports}) != len(reports):
        raise ValueError("Missing/duplicate device evidence")
    for report in reports:
        item = expected.get(report.get("api"))
        if not item or report.get("schema") != 1 or report.get("commit") != plan["commit"] or report.get("runId") != run_id:
            raise ValueError("Foreign device evidence")
        if not isinstance(report.get("attempt"), int) or not 1 <= report["attempt"] <= attempt:
            raise ValueError("Invalid evidence attempt")
        if report.get("success") is not True or report.get("skipped") != [] or report.get("tests") != item["tests"] or report.get("passed") != item["tests"]:
            raise ValueError("Incomplete device coverage")
        if plan.get("policy") in (2, 3, POLICY):
            minimum = plan["platformConstraints"]["minSdk"]
            if report.get("targetMinSdk") != minimum or type(report.get("testMinSdk")) is not int or not 1 <= report["testMinSdk"] <= report["api"]:
                raise ValueError("Device APK minimum SDK differs from plan")
        if not report.get("fingerprint") or report.get("abi") != "x86_64":
            raise ValueError("Missing/wrong device environment")
        if any(report.get(k) != artifact.get(k) or not re.fullmatch(r"[0-9a-f]{64}", str(artifact.get(k, ""))) for k in ("targetSha256", "testSha256")):
            raise ValueError("Device APK identity mismatch")


def validate_ci_evidence(report, manifest, run_id, attempt):
    if report.get("schema") != 1 or report.get("success") is not True or report.get("runId") != run_id or not 1 <= report.get("attempt", 0) <= attempt:
        raise ValueError("Invalid CI verification report")
    plan, build = report["plan"], report["build"]
    if plan.get("schema") != 1 or plan.get("policy") not in (1, 2, 3, POLICY) or plan.get("commit") != manifest["commit"]:
        raise ValueError("Verification plan/source mismatch")
    constraints = validate_plan(plan)
    if plan["policy"] in (2, 3, POLICY) and "minSdk" in manifest and manifest["minSdk"] != constraints["minSdk"]:
        raise ValueError("Candidate minimum SDK differs from plan")
    if digest(plan) != manifest.get("planSha256") or build.get("planSha256") != digest(plan):
        raise ValueError("Verification plan was changed")
    if build.get("commit") != manifest["commit"] or build.get("runId") != run_id or not 1 <= build.get("attempt", 0) <= report["attempt"]:
        raise ValueError("Foreign build evidence")
    if build.get("candidateSha256") != manifest["candidateSha256"]:
        raise ValueError("Verification refers to another candidate")
    jvm = build.get("jvm", {})
    if not isinstance(jvm.get("tests"), int) or jvm["tests"] < 1 or any(jvm.get(k) != 0 for k in ("failures", "errors", "skipped")):
        raise ValueError("Missing or unsuccessful full JVM evidence")
    validate_device_evidence(plan, build.get("apks") or {}, report.get("devices", []), run_id, report["attempt"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    plan = make_plan(base=args.base)
    if args.output:
        write_json(args.output, plan)
    print(json.dumps(plan, indent=2))


if __name__ == "__main__": main()
