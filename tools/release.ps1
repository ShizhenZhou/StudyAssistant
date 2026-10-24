#Requires -Version 5.1
# Process-local environment; forwards structured arguments without embedding credentials.
$ErrorActionPreference = 'Stop'
$releaseArguments = @($args)
. (Join-Path $PSScriptRoot 'codex-env.ps1')
& python -B (Join-Path $PSScriptRoot 'release.py') @releaseArguments
exit $LASTEXITCODE
