"""Isolated Git fixtures only; no real credentials or user data are read."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("secret_scan", ROOT / "tools/check-secrets.py")
SCANNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCANNER)


class SecretScanTest(unittest.TestCase):
    def setUp(self):
        temp = ROOT / "app/build/tool-tests"
        temp.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=temp)
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "-q")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.repo), *args], capture_output=True, check=True)

    def stage(self, name, data):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        self.git("add", "--", name)
        return path

    def test_clean_index(self):
        self.stage("notes.txt", b"ordinary text")
        names, problems = SCANNER.scan(self.repo)
        self.assertEqual(names, ["notes.txt"])
        self.assertEqual(problems, [])

    def test_unicode_space_and_quote_paths(self):
        self.stage("说明 空格/data.txt", ("sk-" + "x" * 24).encode())
        self.git("config", "core.quotepath", "true")
        names, problems = SCANNER.scan(self.repo)
        self.assertEqual(names, ["说明 空格/data.txt"])
        self.assertEqual(len(problems), 1)
        self.assertNotIn("x" * 24, problems[0])

    def test_reads_staged_version(self):
        path = self.stage("settings.txt", ("sk-" + "a" * 24).encode())
        path.write_bytes(b"working copy is now clean")
        self.assertEqual(len(SCANNER.scan(self.repo)[1]), 1)

    def test_large_single_line_is_scanned(self):
        self.stage("large.txt", b"a" * (2 * 1024 * 1024 + 10) + ("ghp_" + "b" * 36).encode())
        self.assertEqual(len(SCANNER.scan(self.repo)[1]), 1)

    def test_private_binary_names_are_blocked(self):
        for name in ["release.p12", "release.pfx", "lineage.bin", ".secrets/blob.bin", "test.key"]:
            self.stage(name, b"not a real key")
        self.assertEqual(len(SCANNER.scan(self.repo)[1]), 5)

    def test_utf16_and_fine_grained_token(self):
        self.stage("settings.txt", ("github_pat_" + "c" * 40).encode("utf-16"))
        self.assertEqual(len(SCANNER.scan(self.repo)[1]), 1)

    def test_tracked_scan_after_commit(self):
        self.stage("already.txt", ("sk-" + "d" * 24).encode())
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "core.hooksPath=disabled-hooks", "commit", "-qm", "fixture")
        self.assertEqual(SCANNER.scan(self.repo)[0], [])
        self.assertEqual(len(SCANNER.scan(self.repo, tracked=True)[1]), 1)

    def test_git_failure_is_not_success(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "app/build/tool-tests") as folder:
            with self.assertRaises(RuntimeError):
                SCANNER.scan(Path(folder) / "missing-directory")

    def test_empty_file_and_binary_asset(self):
        self.stage("empty.txt", b"")
        self.stage("picture.png", b"synthetic binary asset")
        self.assertEqual(SCANNER.scan(self.repo)[1], [])


if __name__ == "__main__":
    unittest.main()
