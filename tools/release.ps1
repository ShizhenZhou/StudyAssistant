#Requires -Version 5.1
# Process-local environment; forwards structured arguments without embedding credentials.
$ErrorActionPreference = 'Stop'
$releaseArguments = @($args)
$probe = 'import sys; print(1) if sys.version_info >= (3,11) else sys.exit(1)'
$releasePython = $null
foreach ($candidate in @(Get-Command python.exe -CommandType Application -All -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty Source -Unique)) {
    try {
        $confirmed = & $candidate -I -B -c $probe 2>$null
        if ($LASTEXITCODE -eq 0 -and $confirmed -eq '1') {
            $releasePython = $candidate
            break
        }
    } catch {
        # A Windows app alias can exist on PATH without a usable interpreter.
    }
}
if (-not $releasePython) { throw 'No working Python 3.11+ found on PATH.' }
. (Join-Path $PSScriptRoot 'codex-env.ps1')
& $releasePython -E -s -B (Join-Path $PSScriptRoot 'release.py') @releaseArguments
exit $LASTEXITCODE
