"""Local checks with content-bound evidence; --plan never runs checks or creates reports."""
import argparse
import json
import os
import platform
import re
import shutil
from pathlib import Path
import subprocess
import sys
import time
from verification import (ROOT, EvidenceStore, changed_files, digest, environment_identity,
                          file_sha, git, source_digest, task_inputs, write_json)


# Only actual tool-test inputs belong here; app Gradle files are synthetic fixtures.
TOOL_INPUTS = ["tools", ".github", ".githooks", ".gitignore"]
DOCUMENTATION_TESTS = ["com.zsz.studyassistant.ui.AboutDocumentationTest", "com.zsz.studyassistant.ui.ChangelogFormatTest"]


def tool_environment_identity():
    # Resolve tools after PATH changes; irrelevant JDK/SDK/cache prefixes do not expire Python tests.
    tools = {}
    for name in ("git", "powershell.exe", "pwsh"):
        path = shutil.which(name)
        tools[name] = (path, file_sha(path)) if path and Path(path).is_file() else None
    return digest([sys.version, sys.executable, platform.system(), platform.release(), platform.machine(),
                   os.environ.get("PSModulePath", ""), tools])


def inferred_basic_tests(root, changed):
    selected = set()
    for name in changed:
        if name.startswith("app/src/test/"):
            path = Path(root) / name
            if not name.endswith("Test.kt") or not path.is_file(): return None
            text = path.read_text(encoding="utf-8")
            package = re.search(r"(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*$", text)
            if not package or not re.search(r"\bclass\s+" + re.escape(path.stem) + r"\b", text) or not re.search(r"@(?:org\.junit\.)?Test\b", text):
                return None  # Shared helpers, removed files or unfamiliar structures retain full JVM coverage.
            selected.add(package[1] + "." + path.stem)
        if name == "README.md" or name.startswith("docs/readme/"):
            selected.update(DOCUMENTATION_TESTS)
    return sorted(selected)


def run_step(root, store, name, command, identity, force=False, outputs=(), unchanged=lambda: True, family=None):
    cached = None if force else store.find(identity, [name], family)
    def output_hash(path):
        if path.is_file(): return file_sha(path)
        if path.is_dir():
            files = [(str(f.relative_to(path)), file_sha(f)) for f in sorted(path.rglob("*")) if f.is_file()]
            return digest(files) if files else None
        return None
    def output_hashes():
        return {str(p.relative_to(root)): output_hash(p) for p in outputs}
    if name.startswith("gradle") and not outputs:
        cached = None  # A log alone cannot prove Gradle outputs still exist.
    if cached and outputs and (cached.get("outputs") != output_hashes() or any(v is None for v in output_hashes().values())):
        cached = None
    if cached:
        print("Reusing: " + name)
        return dict(name=name, reused=True, log=cached["log"], identity=identity, exitCode=0)
    record = store.begin(identity, [name], family)
    print("Checking: " + name, flush=True)
    log = store.folder / record["log"]
    try:
        with log.open("wb") as output:
            result = subprocess.run([str(v) for v in command], cwd=root, stdout=output, stderr=subprocess.STDOUT)
        record["outputs"] = output_hashes()
        stable = unchanged()
        store.finish(record, result.returncode == 0 and stable and all(v is not None for v in record["outputs"].values()))
    except BaseException:
        if not log.exists(): log.write_bytes(b"Check did not start\n")
        store.finish(record, False)
        raise
    if result.returncode:
        raise RuntimeError(f"Check failed: {name} (exit {result.returncode}). See {log}")
    if not record["success"]:
        raise RuntimeError("Inputs changed or required outputs are missing: " + name)
    return dict(name=name, reused=False, log=record["log"], identity=identity, exitCode=0)


def plan_checks(root, mode="Basic", selectors=(), base="origin/main", release=False, android=False):
    if mode != "Full" and (release or android):
        raise ValueError("Release and CompileAndroidTests require -Mode Full")
    base_sha, changed = changed_files(root, base)
    selection = "explicit" if selectors else "full" if mode == "Full" else "affected inputs"
    if mode == "Basic" and not selectors:
        inferred = inferred_basic_tests(root, changed)
        selectors = inferred or ()
        if inferred is None: selection = "full JVM: shared/deleted/unknown test input"
    tasks = []
    android_changes = any(p.startswith(("app/src/androidTest/", "app/src/integration/")) for p in changed)
    if mode == "Full" or selectors:
        tasks.append("testDebugUnitTest")
    else:
        jvm_changes = any(p.startswith("app/src/test/") for p in changed)
        main_changes = any((p.startswith(("app/", "gradle/")) and not p.startswith(("app/src/test/", "app/src/androidTest/", "app/src/integration/"))) or
                           p in ("gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "gradle.properties") for p in changed)
        if jvm_changes: tasks.append("testDebugUnitTest")
        elif main_changes: tasks.append("compileDebugKotlin")
    if mode == "Basic" and android_changes: tasks.append("compileIntegrationAndroidTestKotlin")
    if release: tasks.append("assembleRelease")
    if android: tasks.extend(["assembleIntegration", "assembleIntegrationAndroidTest"])
    return dict(schema=1, steps=["diff", "diff-staged", "credentials", "tool-tests"] + tasks + (["sqlite"] if mode == "Full" and not selectors else []),
                inputs={task: task_inputs([task]) for task in tasks},
                reuse="Per-step content + environment + log + required output hashes; plan does not assert a cache hit. Full Git-visible source drift is audited throughout execution.",
                mode=mode, base=base_sha, changedFiles=changed, tasks=tasks,
                selectors=sorted(selectors), selectionReason=selection, sqlite=mode == "Full" and not selectors,
                credentialScope="current Git index", commit=git(root, "rev-parse", "HEAD"))


def execute(root, plan, force=False):
    folder = Path(root) / ".local-notes/verification/local"
    store = EvidenceStore(folder)
    source = source_digest(root)
    env = environment_identity()
    report = dict(plan, sourceDigest=source, environment=env, success=False, steps=[])
    report_path = folder.parent / (str(time.time_ns()) + "-summary.json")
    python = [sys.executable, "-B"]
    try:
        for name, command in [("diff", ["git", "diff", "--check"]), ("diff-staged", ["git", "diff", "--cached", "--check"]),
                              ("credentials", python + ["tools/check-secrets.py", "--tracked"])]:
            report["steps"].append(run_step(root, store, name, command, digest([name, source, env]), force=True))
        tool_inputs = source_digest(root, TOOL_INPUTS)
        tool_env = tool_environment_identity()
        report["toolEnvironment"] = tool_env
        report["steps"].append(run_step(root, store, "tool-tests", python + ["-m", "unittest", "discover", "-s", "tools/tests", "-p", "test_*.py"], digest(["tools-v2", tool_inputs, tool_env]), force,
                                       unchanged=lambda: source_digest(root) == source))
        for task in plan["tasks"]:
            command = [str(Path(root) / "gradlew.bat"), task, "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process"]
            selectors = plan["selectors"] if task == "testDebugUnitTest" else []
            for selector in selectors: command.extend(["--tests", selector])
            inputs = source_digest(root, task_inputs([task]))
            output_paths = {
                "compileDebugKotlin": ["app/build/tmp/kotlin-classes/debug"],
                "compileIntegrationAndroidTestKotlin": ["app/build/tmp/kotlin-classes/integrationAndroidTest"],
                "testDebugUnitTest": ["app/build/test-results/testDebugUnitTest"],
                "assembleIntegration": ["app/build/outputs/apk/integration/app-integration.apk"],
                "assembleIntegrationAndroidTest": ["app/build/outputs/apk/androidTest/integration/app-integration-androidTest.apk"],
                "assembleRelease": ["app/build/outputs/apk/release/app-release-unsigned.apk"],
            }
            if task == "testDebugUnitTest" and plan["sqlite"]:
                output_paths[task] += ["app/build/identity-work/migration-plan.json", "app/build/identity-work/tag-merge-schema.sql",
                                       "app/build/generated/source/kapt/debug/com/zsz/studyassistant/data/AppDatabase_Impl.java"]
            identity = digest(["gradle-v2", inputs, env, task, selectors, plan["sqlite"]])
            report["steps"].append(run_step(root, store, "gradle-" + task, command, identity, force,
                outputs=[Path(root) / p for p in output_paths[task]],
                unchanged=lambda: source_digest(root) == source,
                family=digest(["gradle-v2", inputs, env])))
        if plan["sqlite"]:
            outputs = [Path(root) / p for p in ("app/build/identity-work/migration-plan.json", "app/build/identity-work/tag-merge-schema.sql",
                       "app/build/generated/source/kapt/debug/com/zsz/studyassistant/data/AppDatabase_Impl.java")]
            inputs = [file_sha(p) for p in outputs]
            report["steps"].append(run_step(root, store, "sqlite", python + ["tools/verify-identity-migration.py"],
                digest(["sqlite", source_digest(root, task_inputs(["compileDebugKotlin"]) + ["tools/verify-identity-migration.py"]), env, inputs]), force,
                unchanged=lambda: source_digest(root) == source))
        if source_digest(root) != source:
            raise RuntimeError("Inputs changed while checks were running; evidence cannot certify the current tree")
        report["success"] = True
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        report["error"] = str(error)
        raise
    finally:
        write_json(report_path, report)
        print("Report: " + str(report_path))
    print("Checks passed; steps are marked executed or reused. No signing, installation or publication.")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["Basic", "Full"], default="Basic")
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--test-class", action="append", default=[])
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--android", action="store_true")
    parser.add_argument("--plan", action="store_true")
    parser.add_argument("--force", action="store_true", help="Explicit fresh verification without widening scope")
    args = parser.parse_args()
    try:
        plan = plan_checks(ROOT, args.mode, args.test_class, args.base, args.release, args.android)
        if args.plan: print(json.dumps(plan, indent=2))
        else: execute(ROOT, plan, args.force)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__": sys.exit(main())
