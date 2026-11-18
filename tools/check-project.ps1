#Requires -Version 5.1
param([string[]]$TestClass = @(), [switch]$Compile, [switch]$Plan, [ValidateSet("main", "compat")][string]$Distribution = "main")
$ErrorActionPreference = 'Stop'
$checkArguments = @('-B', (Join-Path $PSScriptRoot 'local_checks.py'), '--distribution', $Distribution)
foreach ($selector in $TestClass) { $checkArguments += @('--test-class', $selector) }
if ($Compile) { $checkArguments += '--compile' }
if ($Plan) {
    & python @checkArguments --plan
    exit $LASTEXITCODE
}
if ($Compile -or $TestClass.Count -gt 0) { . (Join-Path $PSScriptRoot 'codex-env.ps1') }
& python @checkArguments
exit $LASTEXITCODE
