#Requires -Version 5.1
# Compatibility entry point. The shared Python scanner also runs in CI.
$ErrorActionPreference = 'Stop'
try {
    & python (Join-Path $PSScriptRoot 'check-secrets.py') @args
    exit $LASTEXITCODE
} catch {
    Write-Error 'Credential scan could not run. Commit/check must stop.'
    exit 1
}
