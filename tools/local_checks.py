"""Local checks with content-bound evidence; --plan never runs checks or creates reports."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
from verification import (ROOT, EvidenceStore, changed_files, digest, environment_identity,
                          file_sha, git, source_digest, write_json)


def run_step(root, store, name, command, identity, force=False, outputs=(), unchanged=lambda: True, family=None):
    cached = None if force else store.find(identity, [name], family)
    def output_hashes():
        return {str(p.relative_to(root)): file_sha(p) if p.is_file() else None for p in outputs}
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
    tasks = []
    if mode == "Full" or selectors:
        tasks.append("testDebugUnitTest")
    elif any(p.startswith(("app/", "gradle/")) or p in ("gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "gradle.properties") for p in changed):
        tasks.append("compileDebugKotlin")
    if release: tasks.append("assembleRelease")
    if android: tasks.extend(["assembleIntegration", "assembleIntegrationAndroidTest"])
    return dict(schema=1, mode=mode, base=base_sha, changedFiles=changed, tasks=tasks,
                selectors=sorted(selectors), sqlite=mode == "Full" and not selectors,
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
        tool_inputs = source_digest(root, ["tools", ".github", ".githooks", ".gitignore", "app/build.gradle.kts"])
        report["steps"].append(run_step(root, store, "tool-tests", python + ["-m", "unittest", "discover", "-s", "tools/tests", "-p", "test_*.py"], digest(["tools", tool_inputs, env]), force,
                                       unchanged=lambda: source_digest(root) == source))
        if plan["tasks"]:
            command = [str(Path(root) / "gradlew.bat")] + plan["tasks"] + ["--console=plain", "-Pkotlin.compiler.execution.strategy=in-process"]
            for selector in plan["selectors"]: command.extend(["--tests", selector])
            outputs = [Path(root) / p for p in ("app/build/identity-work/migration-plan.json", "app/build/identity-work/tag-merge-schema.sql",
                                                "app/build/generated/source/kapt/debug/com/zsz/studyassistant/data/AppDatabase_Impl.java")]
            identity = digest(["gradle", source, env, plan["tasks"], plan["selectors"]])
            step = run_step(root, store, "gradle", command, identity, force, outputs=outputs if plan["sqlite"] else (),
                            unchanged=lambda: source_digest(root) == source, family=digest(["gradle", source, env]))
            report["steps"].append(step)
            if plan["sqlite"]:
                inputs = [file_sha(p) for p in outputs]
                report["steps"].append(run_step(root, store, "sqlite", python + ["tools/verify-identity-migration.py"], digest(["sqlite", source, env, inputs]), force))
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
