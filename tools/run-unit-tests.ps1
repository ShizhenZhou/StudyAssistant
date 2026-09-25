#Requires -Version 5.1
<#
.SYNOPSIS
    Run the project's JVM unit tests (app/src/test) on this machine.

.DESCRIPTION
    Why this script exists (2026-09-25, Windows + JDK 21 + non-ASCII project path):

    `gradlew testDebugUnitTest` CANNOT run here. Gradle launches the test worker with a
    classpath argument file (`@...\gradle-worker-classpath<digits>txt`, ~33 KB, written in
    UTF-8 because org.gradle.jvmargs sets -Dfile.encoding=UTF-8), but the java launcher
    reads that file with the **native** ANSI codepage (CP936 on this box). Our project path
    contains the non-ASCII segment "...\文档\...", so every classpath entry is mangled and the
    worker dies with:

        ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain

    (The 33 KB classpath also exceeds the 32 KB Windows command-line limit, so it cannot just
    be passed with -cp either.)

    Workaround used here: let Gradle COMPILE the tests (that works fine, it runs in-process),
    then run JUnitCore ourselves with a short, hand-built classpath. The classpath is passed on
    the command line (no argument file), and the few jars it needs are copied into an ASCII-only
    temp directory first.

    On CI (clean ASCII paths) the normal `./gradlew testDebugUnitTest` works - see
    .github/workflows/build.yml, which runs it on every push. This script is only for local use.

.EXAMPLE
    .\tools\run-unit-tests.ps1
#>
[CmdletBinding()]
param(
    # Leave empty to derive from the repo location (see below). Pass explicit values only if
    # your Gradle/Android home directories live somewhere else.
    # NOTE: keep this file ASCII-only - a .ps1 with non-ASCII bytes needs a UTF-8 BOM, and a
    # hardcoded non-ASCII default here would be read as mojibake by Windows PowerShell.
    [string]$GradleUserHome = '',
    [string]$AndroidUserHome = ''
)

$ErrorActionPreference = 'Stop'

function Write-Step($t) { Write-Host "-- $t" -ForegroundColor Cyan }
function Write-Ok($t)   { Write-Host "OK  $t" -ForegroundColor Green }
function Write-Err2($t) { Write-Host "XX  $t" -ForegroundColor Red }

$repo = (& git rev-parse --show-toplevel 2>$null | Select-Object -First 1)
if (-not $repo) { $repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
Set-Location $repo

# Default locations (computed at runtime, so this file stays pure ASCII):
#   <workspace>\.gradle-home  and  <workspace>\.android-home, where <workspace> is the repo's parent
$workspace = Split-Path $repo -Parent
if (-not $GradleUserHome)  { $GradleUserHome  = if ($env:GRADLE_USER_HOME)  { $env:GRADLE_USER_HOME }  else { Join-Path $workspace '.gradle-home' } }
if (-not $AndroidUserHome) { $AndroidUserHome = if ($env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME } else { Join-Path $workspace '.android-home' } }

# 1. Compile the unit tests through Gradle (in-process, unaffected by the argfile issue)
Write-Step 'compile debug unit tests (gradle)'
$env:GRADLE_USER_HOME = $GradleUserHome
$env:ANDROID_USER_HOME = $AndroidUserHome
& .\gradlew.bat compileDebugUnitTestKotlin --console=plain
if ($LASTEXITCODE -ne 0) { Write-Err2 'compile failed'; exit 1 }

$appBuild = Join-Path $repo 'app\build'
$classes = @(
    (Join-Path $appBuild 'tmp\kotlin-classes\debug'),
    (Join-Path $appBuild 'intermediates\javac\debug\compileDebugJavaWithJavac\classes'),
    (Join-Path $appBuild 'tmp\kotlin-classes\debugUnitTest')
)
foreach ($c in $classes) {
    if (-not (Test-Path $c)) { Write-Err2 "missing compiled classes: $c"; exit 1 }
}

# 2. Collect the few jars JUnitCore needs into an ASCII-only directory (the cache path is non-ASCII)
Write-Step 'collect jars into an ASCII temp dir'
$lib = Join-Path $env:TEMP 'dsh-junit-libs'
Remove-Item $lib -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $lib | Out-Null

$root = Join-Path $GradleUserHome 'caches'
$wanted = @(
    @{ Name = 'junit-4.13.2.jar';                    Pattern = 'junit-*.jar' },
    @{ Name = 'hamcrest-core-1.3.jar';               Pattern = 'hamcrest-core-*.jar' },
    @{ Name = 'kotlin-stdlib-2.2.20.jar';            Pattern = 'kotlin-stdlib-2*.jar' },
    @{ Name = 'kotlinx-serialization-json-jvm.jar';  Pattern = 'kotlinx-serialization-json-jvm-1.7*.jar' },
    @{ Name = 'kotlinx-serialization-core-jvm.jar';  Pattern = 'kotlinx-serialization-core-jvm-1.7*.jar' },
    @{ Name = 'okhttp-4.12.0.jar';                   Pattern = 'okhttp-4*.jar' },
    @{ Name = 'okio-jvm-3.6.0.jar';                  Pattern = 'okio-jvm-*.jar' }
)
foreach ($w in $wanted) {
    $hit = Get-ChildItem (Join-Path $root 'modules-2\files-2.1') -Recurse -Filter $w.Pattern -ErrorAction SilentlyContinue |
           Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $hit) { Write-Err2 "jar not found in cache: $($w.Pattern)"; exit 1 }
    Copy-Item $hit.FullName (Join-Path $lib $w.Name) -Force
}

# Compose runtime is packaged as an AAR: take the classes.jar produced by Gradle's transforms
# (the transform output lives under caches\<gradle-version>\transforms\..., hence the scan)
$compose = $null
$transformDirs = @(Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName 'transforms' } |
    Where-Object { Test-Path $_ })
foreach ($td in $transformDirs) {
    foreach ($cj in (Get-ChildItem $td -Recurse -Filter 'classes.jar' -ErrorAction SilentlyContinue |
                     Where-Object { $_.FullName -match '\\transformed\\runtime' })) {
        try {
            Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
            $zip = [System.IO.Compression.ZipFile]::OpenRead($cj.FullName)
            $has = $zip.Entries | Where-Object { $_.FullName -eq 'androidx/compose/runtime/CompositionLocalKt.class' }
            $zip.Dispose()
            if ($has) { $compose = $cj.FullName; break }
        } catch { }
    }
    if ($compose) { break }
}
if (-not $compose) { Write-Err2 'compose runtime classes.jar not found in transforms cache'; exit 1 }
Copy-Item $compose (Join-Path $lib 'compose-runtime.jar') -Force

# 3. Run JUnitCore
Write-Step 'run unit tests (JUnitCore)'
$cp = (Join-Path $lib '*') + ';' + ($classes -join ';')
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) {
    $java = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
}
if (-not $java) { Write-Err2 'java.exe not found'; exit 1 }

$testClasses = @(
    'com.zsz.studyassistant.data.VisionOutputParserTest',
    'com.zsz.studyassistant.data.UpdateCheckerVersionTest',
    'com.zsz.studyassistant.ui.L10nTableTest'
)
& $java '-Dfile.encoding=UTF-8' -cp $cp org.junit.runner.JUnitCore @testClasses
if ($LASTEXITCODE -ne 0) { Write-Err2 'unit tests FAILED'; exit 1 }
Write-Ok 'unit tests passed'
