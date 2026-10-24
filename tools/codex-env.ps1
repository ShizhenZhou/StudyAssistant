#Requires -Version 5.1
# Dot-source this script: . ./tools/codex-env.ps1
# Process-local settings only; no persistent system configuration changes.
$codexProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$codexJdk = 'C:\Program Files\Java\jdk-21.0.10'
$codexSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if (-not (Test-Path -LiteralPath (Join-Path $codexJdk 'bin\java.exe'))) {
    throw 'Expected JDK 21 installation is missing; update this project-local environment script.'
}
if (-not (Test-Path -LiteralPath (Join-Path $codexSdk 'platform-tools\adb.exe'))) {
    throw 'Expected Android SDK is missing; update this project-local environment script.'
}
$env:JAVA_HOME = $codexJdk
$env:ANDROID_HOME = $codexSdk
$env:ANDROID_SDK_ROOT = $codexSdk
$env:GRADLE_USER_HOME = Join-Path $codexProjectRoot '.gradle\user-home'
$env:ANDROID_USER_HOME = Join-Path $codexProjectRoot '.gradle\android-home'
$env:TEMP = Join-Path $codexProjectRoot 'app\build\identity-work\tmp'
$env:TMP = $env:TEMP
foreach ($codexDir in @($env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME, $env:TEMP)) {
    [IO.Directory]::CreateDirectory($codexDir) | Out-Null
}
$codexJavaBin = Join-Path $codexJdk 'bin'
if (($env:PATH -split ';') -notcontains $codexJavaBin) {
    $env:PATH = $codexJavaBin + ';' + $env:PATH
}
Write-Host ('Project environment ready: ' + $codexProjectRoot)
