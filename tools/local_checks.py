"""Explicit, minimal local checks. No automatic test selection or full-suite fallback."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]

def plan_checks(selectors=(), compile_app=False):
    if any(not value.strip() or value.strip() == "*" for value in selectors):
        raise ValueError("Choose a specific test class or method")
    tasks = ["testDebugUnitTest"] if selectors else ["compileDebugKotlin"] if compile_app else []
    return dict(steps=["diff", "diff-staged", "credentials"], tasks=tasks, selectors=list(selectors),
                note="No device tests, online checks or implicit full suite. Gradle reuses unchanged task outputs.")

def execute(plan):
    commands = [["git", "diff", "--check"], ["git", "diff", "--cached", "--check"],
                [sys.executable, "-B", str(ROOT / "tools/check-secrets.py"), "--tracked"]]
    if plan["tasks"]:
        command = [str(ROOT / "gradlew.bat"), *plan["tasks"], "--console=plain"]
        for selector in plan["selectors"]: command.extend(["--tests", selector])
        commands.append(command)
    folder = ROOT / ".local-notes/verification/local"
    folder.mkdir(parents=True, exist_ok=True)
    log = folder / (str(time.time_ns()) + ".log")
    with log.open("wb") as out:
        for command in commands:
            result = subprocess.run(command, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT)
            if result.returncode:
                raise RuntimeError(f"Local check failed ({result.returncode}); inspect {log}. Scope was not expanded.")
    print("Selected checks passed. Log: " + str(log))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-class", action="append", default=[])
    parser.add_argument("--compile", action="store_true")
    parser.add_argument("--plan", action="store_true")
    args = parser.parse_args()
    try:
        plan = plan_checks(args.test_class, args.compile)
        if args.plan: print(json.dumps(plan, indent=2))
        else: execute(plan)
    except (OSError, ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr); return 1
    return 0

if __name__ == "__main__": sys.exit(main())
