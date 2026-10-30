#Requires -Version 5.1
param([string[]]$TestClass = @(), [switch]$Compile, [switch]$Plan)
$ErrorActionPreference = 'Stop'
$checkArguments = @('-B', (Join-Path $PSScriptRoot 'local_checks.py'))
foreach ($selector in $TestClass) { $checkArguments += @('--test-class', $selector) }
if ($Compile) { $checkArguments += '--compile' }
if ($Plan) {
    & python @checkArguments --plan
    exit $LASTEXITCODE
}
if ($Compile -or $TestClass.Count -gt 0) { . (Join-Path $PSScriptRoot 'codex-env.ps1') }
& python @checkArguments
exit $LASTEXITCODE
