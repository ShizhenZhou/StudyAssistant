#Requires -Version 5.1
# Basic is the normal local entry. Full is explicit diagnosis, never automatic release signing.
param(
    [ValidateSet('Basic','Full')][string]$Mode = 'Basic',
    [string[]]$TestClass = @(),
    [string]$BaseRef = 'origin/main',
    [switch]$Release,
    [switch]$CompileAndroidTests
)
$ErrorActionPreference = 'Stop'
if ($Mode -ne 'Full' -and ($Release -or $CompileAndroidTests)) {
    throw 'Release and CompileAndroidTests require -Mode Full; normal releases build in CI.'
}
if ($BaseRef.StartsWith('-')) { throw 'Invalid base reference.' }
$checkRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$checkFolder = Join-Path $checkRoot ('app\build\checks\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
[IO.Directory]::CreateDirectory($checkFolder) | Out-Null
$checkReport = [ordered]@{ success = $false; mode = $Mode; credentialScope = 'Git index'; releaseBuild = [bool]$Release; compileAndroidTests = [bool]$CompileAndroidTests; steps = @() }

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
    Invoke-ProjectCheck 'diff' 'git' @('diff', '--check')
    Invoke-ProjectCheck 'diff-staged' 'git' @('diff', '--cached', '--check')
    Invoke-ProjectCheck 'credentials' 'python' @('-B', 'tools/check-secrets.py', '--tracked')
    Invoke-ProjectCheck 'tool-tests' 'python' @('-B', '-m', 'unittest', 'discover', '-s', 'tools/tests', '-p', 'test_*.py')
    $changed = @(& git diff --name-only; & git diff --cached --name-only; & git ls-files --others --exclude-standard)
    & git rev-parse --verify --quiet $BaseRef *> $null
    if ($LASTEXITCODE -eq 0) { $changed += @(& git diff --name-only ($BaseRef + '...HEAD')) }
    $changed = @($changed | Sort-Object -Unique)
    $checkReport.changedFiles = $changed
    $gradleTasks = @()
    if ($Mode -eq 'Full' -or $TestClass.Count -gt 0) { $gradleTasks += 'testDebugUnitTest' }
    elseif ($changed -match '^(app/|gradle/|build.gradle.kts$|settings.gradle.kts$|gradle.properties$)') { $gradleTasks += 'compileDebugKotlin' }
    if ($Release) { $gradleTasks += 'assembleRelease' }
    if ($CompileAndroidTests) { $gradleTasks += @('assembleIntegration', 'assembleIntegrationAndroidTest') }
    if ($gradleTasks.Count -gt 0) {
        . (Join-Path $PSScriptRoot 'codex-env.ps1')
        $gradleArguments = $gradleTasks + @('--console=plain', '-Pkotlin.compiler.execution.strategy=in-process')
        foreach ($testFilter in $TestClass) { $gradleArguments += @('--tests', $testFilter) }
        Invoke-ProjectCheck 'gradle' (Join-Path $checkRoot 'gradlew.bat') $gradleArguments
    }
    # A selected test suite may not emit the migration plan. Never validate stale outputs as a fresh full run.
    if ($Mode -eq 'Full' -and $TestClass.Count -eq 0) {
        Invoke-ProjectCheck 'sqlite' 'python' @('-B', 'tools/verify-identity-migration.py')
    }
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
Write-Host 'Checks passed. No signing, installation, push or publication performed.'
exit 0
