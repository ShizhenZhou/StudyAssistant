#Requires -Version 5.1
# Explicit emulator serial required. Never installs anything to a physical phone.
param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
function Invoke-AndroidTestTool([string]$Executable, [string[]]$Arguments) {
    Get-Command $Executable -ErrorAction Stop | Out-Null
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() }
        $toolExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($toolExit -ne 0) { throw ('Android test tool failed with exit code ' + $toolExit) }
    return $output
}
$androidTestRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $androidTestRoot
try {
    . (Join-Path $PSScriptRoot 'codex-env.ps1')
    if ($Serial -notmatch '^emulator-\d+$') { throw 'Only a local emulator serial is accepted.' }
    $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    $emulator = Invoke-AndroidTestTool $adb @('-s', $Serial, 'shell', 'getprop', 'ro.kernel.qemu')
    if (($emulator -join '').Trim() -ne '1') {
        throw 'The selected device is not a running emulator. No installation performed.'
    }
    Invoke-AndroidTestTool (Join-Path $androidTestRoot 'gradlew.bat') @('assembleIntegration', 'assembleIntegrationAndroidTest', '--console=plain', '-Pkotlin.compiler.execution.strategy=in-process') | ForEach-Object { Write-Host $_ }
    $targetApk = Join-Path $androidTestRoot 'app\build\outputs\apk\integration\app-integration.apk'
    $testApk = Join-Path $androidTestRoot 'app\build\outputs\apk\androidTest\integration\app-integration-androidTest.apk'
    $aapt = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\aapt.exe'
    foreach ($entry in @(@($targetApk, 'com.zsz.studyassistant.integration'),
                         @($testApk, 'com.zsz.studyassistant.integration.test'))) {
        $badging = Invoke-AndroidTestTool $aapt @('dump', 'badging', $entry[0])
        if (-not ($badging -match ([regex]::Escape("package: name='" + $entry[1] + "'")))) {
            throw 'Unexpected APK identity. No installation performed.'
        }
    }
    $manifest = Invoke-AndroidTestTool $aapt @('dump', 'xmltree', $testApk, 'AndroidManifest.xml')
    if (-not ($manifest -match 'android:targetPackage.*"com\.zsz\.studyassistant\.integration"')) {
        throw 'Unexpected instrumentation target. No installation performed.'
    }
    foreach ($apk in @($targetApk, $testApk)) {
        Invoke-AndroidTestTool $adb @('-s', $Serial, 'install', '-r', $apk) | ForEach-Object { Write-Host $_ }
    }
    $result = Invoke-AndroidTestTool $adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', 'com.zsz.studyassistant.integration.test/androidx.test.runner.AndroidJUnitRunner')
    $folder = Join-Path $androidTestRoot 'app\build\android-integration'
    [IO.Directory]::CreateDirectory($folder) | Out-Null
    $result | Out-File -LiteralPath (Join-Path $folder 'instrumentation.log') -Encoding UTF8
    $text = $result -join "`n"
    if ($text -notmatch 'OK \([1-9][0-9]* tests?\)' -or $text -match 'FAILURES!!!|INSTRUMENTATION_FAILED') {
        throw 'Android integration checks failed or returned no passing test summary. See app/build/android-integration/instrumentation.log.'
    }
    Write-Host 'Android integration checks passed on the selected emulator only.'
} finally { Pop-Location }
