"""Scan Git index blobs without displaying credential values. No network access."""
import argparse
import codecs
import fnmatch
from pathlib import Path
import re
import subprocess
import sys

CONTENT_PATTERNS = [re.compile(p) for p in (
    r"sk-[A-Za-z0-9_\-]{20,}", r"AKID[A-Za-z0-9]{16,}",
    r"LTAI[A-Za-z0-9]{12,}", r"AIza[0-9A-Za-z_\-]{30,}",
    r"gh[pousr]_[A-Za-z0-9]{30,}", r"github_pat_[A-Za-z0-9_]{20,}",
    r"xox[baprs]-[A-Za-z0-9\-]{10,}", r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
)]
PRIVATE_DIRS = {"keys", ".keys", ".secrets", ".adb-keys"}
PRIVATE_SUFFIXES = {".jks", ".keystore", ".pem", ".p12", ".pfx", ".key", ".secret"}
PRIVATE_NAMES = {"secrets.properties", "local.properties", "local_secret", "adbkey", "adbkey.pub"}
BINARY_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".apk", ".aab",
                   ".jar", ".aar", ".so", ".woff", ".woff2", ".ttf", ".otf", ".zip"}


def git(repo, *args):
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True)
    if result.returncode:
        # Git's stderr may include content or private paths. Do not echo it.
        raise RuntimeError("Cannot read Git index; scan did not complete.")
    return result.stdout


def private_name(name):
    path = Path(name.lower())
    return (bool(set(path.parts) & PRIVATE_DIRS) or path.suffix in PRIVATE_SUFFIXES
            or path.name in PRIVATE_NAMES or path.name == ".env" or path.name.startswith(".env.")
            or path.name.startswith(("local_secrets", "api_key"))
            or fnmatch.fnmatchcase(path.name, "lineage*.bin"))


def decode_text(data):
    if data.startswith((codecs.BOM_UTF32_LE, codecs.BOM_UTF32_BE)):
        return data.decode("utf-32", errors="replace")
    if data.startswith((codecs.BOM_UTF16_LE, codecs.BOM_UTF16_BE)):
        return data.decode("utf-16", errors="replace")
    return data.decode("utf-8-sig", errors="replace")


def scan(repo, tracked=False):
    args = ("ls-files", "--cached", "-z") if tracked else (
        "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z")
    # NUL-separated paths handle spaces, non-ASCII names and Git-quoted names correctly.
    names = [n.decode("utf-8") for n in git(repo, *args).split(b"\0") if n]
    problems = []
    for name in names:
        if private_name(name):
            problems.append(f"Private filename: {name!r}")
            continue
        if Path(name).suffix.lower() in BINARY_SUFFIXES:
            continue
        # Read staged bytes, not the possibly different working copy. Do not skip large text.
        text = decode_text(git(repo, "show", ":" + name))
        if any(pattern.search(text) for pattern in CONTENT_PATTERNS):
            problems.append(f"Credential pattern: {name!r}")
    return names, problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tracked", action="store_true", help="Scan all tracked index files (CI)")
    args = parser.parse_args()
    try:
        root = git(Path.cwd(), "rev-parse", "--show-toplevel").decode("utf-8").strip()
        names, problems = scan(root, args.tracked)
    except (OSError, RuntimeError, UnicodeError):
        print("FAIL: Git index could not be scanned. Commit/check must stop.", file=sys.stderr)
        return 1
    if problems:
        print(f"FAIL: {len(problems)} potential credential file(s); values are not displayed.")
        for problem in problems:
            print("  " + problem)
        return 1
    print(f"PASS: scanned {len(names)} index file(s); no known credential patterns found.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
