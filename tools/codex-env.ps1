#Requires -Version 5.1
# Dot-source: . ./tools/codex-env.ps1 [-JavaHome <JDK25>] [-SdkRoot <SDK>]
# Process-local settings only; never changes system configuration.
param([string]$JavaHome = '', [string]$SdkRoot = '')

function Test-CodexJdk25([string]$Candidate) {
    if (-not $Candidate) { return $false }
    $releaseFile = Join-Path $Candidate 'release'
    return ((Test-Path -LiteralPath (Join-Path $Candidate 'bin\java.exe')) -and
        (Test-Path -LiteralPath $releaseFile) -and
        [bool](Select-String -LiteralPath $releaseFile -Pattern '^JAVA_VERSION="25[.+\-"]'))
}
function Test-CodexSdk([string]$Candidate) {
    return ($Candidate -and (Test-Path -LiteralPath (Join-Path $Candidate 'platform-tools\adb.exe')))
}

$codexProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$codexJdkCandidates = @($JavaHome)
if (-not $JavaHome) {
    $codexJdkCandidates = @($env:JAVA_HOME)
    $codexJavaFolder = Join-Path $env:ProgramFiles 'Java'
    if (Test-Path -LiteralPath $codexJavaFolder) {
        $codexJdkCandidates += @(Get-ChildItem -LiteralPath $codexJavaFolder -Directory |
            Sort-Object Name -Descending | ForEach-Object { $_.FullName })
    }
}
$codexJdk = $codexJdkCandidates | Where-Object { Test-CodexJdk25 $_ } | Select-Object -First 1
if (-not $codexJdk) { throw 'JDK 25 not found. Pass -JavaHome with a valid JDK 25 installation.' }
$codexSdkCandidates = @($SdkRoot)
if (-not $SdkRoot) {
    $codexSdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
}
$codexSdk = $codexSdkCandidates | Where-Object { Test-CodexSdk $_ } | Select-Object -First 1
if (-not $codexSdk) { throw 'Android SDK not found. Pass -SdkRoot with a valid SDK installation.' }

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
$env:PATH = (@($codexJavaBin) + @($env:PATH -split ';' |
    Where-Object { $_ -and $_.TrimEnd('\') -ne $codexJavaBin.TrimEnd('\') })) -join ';'
Write-Host ('Project environment ready: ' + $codexProjectRoot)
