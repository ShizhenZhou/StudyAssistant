#Requires -Version 5.1
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string[]]$TestClass = @(),
    [switch]$All,
    [switch]$UseExistingApks,
    [switch]$Plan,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only a local emulator serial is accepted.' }
if ($All -and $TestClass.Count -gt 0) { throw 'Use either -TestClass or -All, not both.' }
if (-not $Plan -and -not $All -and $TestClass.Count -eq 0) { throw 'Select -TestClass or explicitly request -All before execution.' }
$androidArguments = @('-B', (Join-Path $PSScriptRoot 'run-android-integration.py'), '--serial', $Serial)
foreach ($selector in $TestClass) { $androidArguments += @('--test', $selector) }
if ($All) { $androidArguments += '--all' }
if ($Plan) { & python @androidArguments --list; exit $LASTEXITCODE }
. (Join-Path $PSScriptRoot 'codex-env.ps1')
$androidArguments += '--prepare'
if ($UseExistingApks) { $androidArguments += '--use-existing' }
if ($Force) { $androidArguments += '--force' }
& python @androidArguments
exit $LASTEXITCODE
