"""Exercise the production hash helper without opening signing configuration or signing an APK."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
ROOT = Path(__file__).resolve().parents[2]
PS = Path(os.environ.get("SystemRoot", "C:/Windows")) / "System32/WindowsPowerShell/v1.0/powershell.exe"
@unittest.skipUnless(os.name == "nt" and PS.is_file(), "Windows PowerShell helper")
class SigningHashTest(unittest.TestCase):
    def test_hash_survives_unavailable_get_file_hash(self):
        folder=ROOT/"app/build/tool-tests";folder.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            tmp=Path(tmp); data=tmp/"synthetic.bin";data.write_bytes(bytes(range(256))*4)
            q=lambda p:"'"+str(p).replace("'","''")+"'"
            script="""$ErrorActionPreference = 'Stop'
$ast = [System.Management.Automation.Language.Parser]::ParseFile(SIGN_PATH, [ref]$null, [ref]$null)
$fn = $ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-ReleaseFileHash'}, $true)
. ([scriptblock]::Create($fn.Extent.Text))
function Get-FileHash { throw 'Synthetic unavailable command' }
Get-ReleaseFileHash DATA_PATH
""".replace("SIGN_PATH",q(ROOT/"tools/sign-release.ps1")).replace("DATA_PATH",q(data))
            probe=tmp/"probe.ps1";probe.write_text(script,encoding="utf-8-sig")
            result=subprocess.run([str(PS),"-NoProfile","-ExecutionPolicy","Bypass","-File",str(probe)],capture_output=True,timeout=30)
            self.assertEqual(result.returncode,0,result.stderr.decode(errors="replace"))
            self.assertEqual(result.stdout.decode().strip(),hashlib.sha256(data.read_bytes()).hexdigest())
