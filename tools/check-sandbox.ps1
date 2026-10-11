#Requires -Version 5.1
<#
.SYNOPSIS
    Check whether the current DSH sandbox lets this shell WRITE inside the project.

.DESCRIPTION
    Why this exists (2026-10-11, after the dsh/plugin upgrade):
    confined shells could then only write to the *workspace root itself* and to directories
    created during the same session. Every PRE-EXISTING subdirectory
    (StudyAssistant, tools, history_apk, .build, app\build, .gradle-home ...) was denied.
    That breaks:
        * `gradlew.bat`      -> wrapper lock under <workspace>\.gradle-home\wrapper\...
        * `git add/commit`   -> .git\index.lock
        * APK archiving      -> history_apk\
    while reads, `git push` and `gh` keep working.

    Run this FIRST in a new session:
        .\tools\check-sandbox.ps1
    * everything WRITABLE  -> the sandbox is provisioned recursively again: use the normal
                              workflow (gradlew.bat + tools\git-sync.ps1) as documented.
    * anything DENIED      -> use the MCP build tool for Gradle
                              (android_set_project_dir + android_build_app; unit tests via
                              variant=DebugUnitTest then tools\run-unit-tests.ps1 -SkipCompile)
                              and ask for a one-shot danger-full-access escalation when the
                              repository itself must be written. See DEV_ENV_NOTES.md
                              ("环境重大变化 2026-10-11").

.EXAMPLE
    .\tools\check-sandbox.ps1

.NOTES
    ASCII only on purpose: a .ps1 containing non-ASCII bytes needs a UTF-8 BOM to be parsed
    correctly by Windows PowerShell.
#>
[CmdletBinding()]
param(
    # Workspace root; defaults to the repository's parent directory.
    [string]$WorkspaceRoot
)

$ErrorActionPreference = 'Continue'

$repo = (& git rev-parse --show-toplevel 2>$null | Select-Object -First 1)
if ($repo) { $repo = $repo.Trim() } else { $repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
if (-not $WorkspaceRoot) { $WorkspaceRoot = Split-Path $repo -Parent }

function Test-DirWritable([string]$dir) {
    $probe = Join-Path $dir ("dsh-write-probe-{0}.tmp" -f (Get-Random))
    try {
        Set-Content -LiteralPath $probe -Value 'x' -Encoding ASCII -ErrorAction Stop
        Remove-Item -LiteralPath $probe -Force -ErrorAction SilentlyContinue
        return 'WRITABLE'
    } catch {
        return 'DENIED'
    }
}

$targets = @(
    $WorkspaceRoot,
    $repo,
    (Join-Path $repo 'tools'),
    (Join-Path $repo 'history_apk'),
    (Join-Path $repo 'app\build'),
    (Join-Path $repo '.git'),
    (Join-Path $WorkspaceRoot '.gradle-home'),
    $env:TEMP
)

Write-Host '--- DSH sandbox write check ---'
$denied = 0
foreach ($t in $targets) {
    if (-not (Test-Path -LiteralPath $t)) {
        Write-Host ("  {0,-8} {1}   (missing)" -f 'SKIP', $t)
        continue
    }
    $r = Test-DirWritable $t
    if ($r -ne 'WRITABLE') { $denied++ }
    Write-Host ("  {0,-8} {1}" -f $r, $t)
}

Write-Host '--- gradle wrapper smoke test ---'
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $WorkspaceRoot '.gradle-home' }
if ((Test-DirWritable $gradleHome) -eq 'WRITABLE') {
    $env:GRADLE_USER_HOME = $gradleHome
    if (-not $env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME = Join-Path $WorkspaceRoot '.android-home' }
    Push-Location $repo
    # NOTE: capture the output first and print it afterwards. Piping gradlew straight into
    # `Select-Object -First` closes the pipe early and leaves $LASTEXITCODE = -1, which would
    # look like a failure even though Gradle printed its version banner fine.
    $out = & .\gradlew.bat --version --console=plain 2>&1
    $code = $LASTEXITCODE
    Pop-Location
    $out | Where-Object { $_ -is [string] } | Select-Object -First 4 | ForEach-Object { Write-Host "  $_" }
    if ($code -eq 0) { Write-Host '  gradlew: OK' } else { Write-Host "  gradlew: FAILED (exit $code)" }
} else {
    Write-Host '  skipped: GRADLE_USER_HOME is not writable, so gradlew cannot run (use the MCP build tool)'
}

Write-Host '--- result ---'
if ($denied -eq 0) {
    Write-Host '  sandbox can write the project -> normal workflow (gradlew.bat + tools\git-sync.ps1) is fine'
} else {
    Write-Host ("  {0} path(s) not writable -> MCP build for Gradle + one-shot full-access escalation for git/archiving (see DEV_ENV_NOTES.md)" -f $denied)
}
exit 0
