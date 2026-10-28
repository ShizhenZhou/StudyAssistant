#Requires -Version 5.1
# Plan is read-only; actual checks share evidence with release.start.
param(
    [ValidateSet('Basic','Full')][string]$Mode = 'Basic',
    [string[]]$TestClass = @(),
    [string]$BaseRef = 'origin/main',
    [switch]$Release,
    [switch]$CompileAndroidTests,
    [switch]$Plan,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$checkArguments = @('-B', (Join-Path $PSScriptRoot 'local_checks.py'), '--mode', $Mode, '--base', $BaseRef)
foreach ($selector in $TestClass) { $checkArguments += @('--test-class', $selector) }
if ($Release) { $checkArguments += '--release' }
if ($CompileAndroidTests) { $checkArguments += '--android' }
if ($Force) { $checkArguments += '--force' }
if ($Plan) {
    & python @checkArguments --plan
    exit $LASTEXITCODE
}
$checkPlan = & python @checkArguments --plan
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if (($checkPlan | ConvertFrom-Json).tasks.Count -gt 0) { . (Join-Path $PSScriptRoot 'codex-env.ps1') }
& python @checkArguments
exit $LASTEXITCODE
