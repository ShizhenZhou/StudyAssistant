#Requires -Version 5.1
# Run from any directory. Builds/tests only; never re-signs for release, pushes, installs or publishes.
param([switch]$Release)
$ErrorActionPreference = 'Stop'
$checkRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$checkFolder = Join-Path $checkRoot ('app\build\checks\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
[IO.Directory]::CreateDirectory($checkFolder) | Out-Null
$checkReport = [ordered]@{ success = $false; releaseBuild = [bool]$Release; steps = @() }

function Invoke-ProjectCheck([string]$Name, [string]$Executable, [string[]]$Arguments) {
    $stepLog = Join-Path $checkFolder ($Name + '.log')
    Write-Host ('Checking: ' + $Name)
    Get-Command -Name $Executable -ErrorAction Stop | Out-Null
    $savedPreference = $ErrorActionPreference
    try {
        # Windows PowerShell wraps native stderr as ErrorRecord even on exit 0.
        # Capture it as log text; the actual process exit code determines failure.
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() } |
            Out-File -LiteralPath $stepLog -Encoding UTF8
        $stepExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedPreference
    }
    $checkReport.steps += [ordered]@{ name = $Name; exitCode = $stepExit; log = $stepLog }
    if ($stepExit -ne 0) { throw ('Check failed: ' + $Name + '. See ' + $stepLog) }
}

Push-Location $checkRoot
try {
    . (Join-Path $PSScriptRoot 'codex-env.ps1')
    Invoke-ProjectCheck 'diff' 'git' @('diff', '--check')
    Invoke-ProjectCheck 'diff-staged' 'git' @('diff', '--cached', '--check')
    Invoke-ProjectCheck 'credentials' 'python' @('-B', 'tools/check-secrets.py', '--tracked')
    Invoke-ProjectCheck 'tool-tests' 'python' @('-B', '-m', 'unittest', 'discover', '-s', 'tools/tests', '-p', 'test_*.py')
    $gradleTasks = @('testDebugUnitTest')
    if ($Release) { $gradleTasks += 'assembleRelease' }
    $gradleArguments = $gradleTasks + @('--console=plain', '-Pkotlin.compiler.execution.strategy=in-process')
    Invoke-ProjectCheck 'gradle' (Join-Path $checkRoot 'gradlew.bat') $gradleArguments
    Invoke-ProjectCheck 'sqlite' 'python' @('-B', 'tools/verify-identity-migration.py')
    $checkReport.success = $true
} catch {
    $checkReport.error = $_.Exception.Message
    Write-Host $checkReport.error -ForegroundColor Red
} finally {
    $checkReport | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $checkFolder 'summary.json') -Encoding UTF8
    Pop-Location
}
Write-Host ('Report: ' + (Join-Path $checkFolder 'summary.json'))
if (-not $checkReport.success) { exit 1 }
Write-Host 'All project checks passed. No release re-signing, installation or publication performed.'
exit 0
