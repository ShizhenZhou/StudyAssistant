"""Exercise actual PowerShell entry functions; isolated local processes only."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
POWERSHELL = Path(os.environ.get("SystemRoot", "C:/Windows")) / "System32/WindowsPowerShell/v1.0/powershell.exe"


def quote(path):
    return "'" + str(path).replace("'", "''") + "'"


@unittest.skipUnless(os.name == "nt" and POWERSHELL.is_file(), "Windows PowerShell integration")
class ProjectEntryTest(unittest.TestCase):
    def invoke(self, script):
        folder = ROOT / "app/build/tool-tests"
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as temp:
            path = Path(temp) / "probe.ps1"
            path.write_text(script, encoding="utf-8-sig")
            return subprocess.run([str(POWERSHELL), "-NoProfile", "-ExecutionPolicy", "Bypass",
                                   "-File", str(path)], capture_output=True, timeout=30)

    def test_stderr_and_nonzero_exit_are_distinct(self):
        # Load the real function by AST, without executing builds or duplicating its body.
        script = r"""
$ErrorActionPreference = 'Stop'
$ast = [System.Management.Automation.Language.Parser]::ParseFile(SCRIPT_PATH, [ref]$null, [ref]$null)
$function = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Invoke-ProjectCheck'
}, $true)
. ([scriptblock]::Create($function.Extent.Text))
$checkFolder = $PSScriptRoot
$checkReport = [ordered]@{ steps = @() }
Invoke-ProjectCheck 'stderr-success' 'python' @('-c', 'import sys; print(123, file=sys.stderr)')
if ($checkReport.steps[0].exitCode -ne 0) { throw 'stderr was misclassified' }
$caught = $false
try { Invoke-ProjectCheck 'actual-failure' 'python' @('-c', 'import sys; sys.exit(7)') }
catch { $caught = $true }
if (-not $caught -or $checkReport.steps[1].exitCode -ne 7) { throw 'failure was ignored' }
exit 0
""".replace("SCRIPT_PATH", quote(ROOT / "tools/check-project.ps1"))
        result = self.invoke(script)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))

    def test_android_entry_rejects_physical_serial_before_adb(self):
        result = subprocess.run([str(POWERSHELL), "-NoProfile", "-ExecutionPolicy", "Bypass",
                                 "-File", str(ROOT / "tools/run-android-tests.ps1"),
                                 "-Serial", "physical-fixture"], capture_output=True, timeout=30)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"Only a local emulator serial", result.stderr)

    def test_invalid_explicit_jdk_is_rejected(self):
        script = "try { . " + quote(ROOT / "tools/codex-env.ps1") + " -JavaHome (Join-Path $PSScriptRoot 'missing-jdk'); exit 9 } catch { if ($_.Exception.Message -like 'JDK 21 not found*') { exit 0 }; exit 8 }"
        self.assertEqual(self.invoke(script).returncode, 0)

    def test_invalid_explicit_sdk_is_rejected(self):
        # Supply a valid discovery fixture so a missing JDK cannot masquerade as SDK rejection.
        script = r"""
$fixtureJdk = Join-Path $PSScriptRoot 'jdk'
New-Item -ItemType Directory -Path (Join-Path $fixtureJdk 'bin') -Force | Out-Null
Set-Content -LiteralPath (Join-Path $fixtureJdk 'bin/java.exe') -Value ''
Set-Content -LiteralPath (Join-Path $fixtureJdk 'release') -Value 'JAVA_VERSION="21.0.1"'
try { . SCRIPT_PATH -JavaHome $fixtureJdk -SdkRoot (Join-Path $PSScriptRoot 'missing-sdk'); exit 9 }
catch { if ($_.Exception.Message -like 'Android SDK not found*') { exit 0 }; exit 8 }
""".replace("SCRIPT_PATH", quote(ROOT / "tools/codex-env.ps1"))
        self.assertEqual(self.invoke(script).returncode, 0)
