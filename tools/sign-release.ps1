#Requires -Version 5.1
<#
.SYNOPSIS
    用「自有证书 + key rotation lineage」给 release APK 重签。

.DESCRIPTION
    背景（2026-09-25）：本项目签名从 Android debug 证书**轮换**到自有证书。
    轮换靠 APK Signature Scheme v3 的 SigningCertificateLineage（lineage.bin）实现：
    由**旧（debug）私钥**签名授权新证书 → 已经装了旧证书版本的设备可以**直接覆盖升级，不必卸载**。

    为什么需要这个脚本：AGP 的 signingConfig **不支持 lineage**，只能在构建后用 apksigner 补签。

    ⚠️ 三条铁律：
      1. 以后**每一版**都必须带同一条 lineage 签名（不带的话，还停在旧证书版本的设备/朋友的手机就装不上）
      2. `lineage.bin` 与 keystore **必须永久备份**（丢了 lineage = 以后只能卸载重装）
      3. AGP 先用 debug 证书签出来的那个 APK 会被本脚本**替换掉签名**，所以发给别人的必须是本脚本的产物

    apksigner 在轮换模式下要求**同时提供新旧两个签名者**（新在前，旧用 --next-signer 跟在后面）——
    它要为"低于轮换阈值"的平台准备旧签名者的签名块；v1(JAR) 对 minSdk 28 无用，显式关闭。

    配置从 secrets.properties（已 gitignore）读取，需要的键见下方 $need 列表。

.EXAMPLE
    .\tools\sign-release.ps1                          # 签 app-release.apk，产出 StudyAssistant-<版本>-<时间>.apk
    .\tools\sign-release.ps1 -InApk <某个 apk 路径>    # 指定输入
    .\tools\sign-release.ps1 -RotationMinSdk 28        # 默认就是 28（= 本项目 minSdk）

.NOTES
    退出码：0 = 成功；1 = 失败。
#>
[CmdletBinding()]
param(
    # 输入 APK；默认取 AGP 产出的 app\build\outputs\apk\release\app-release.apk
    [string]$InApk,
    # 输出目录；默认与输入同目录
    [string]$OutDir,
    # lineage 的 rotation-min-sdk-version（本项目 minSdk=28，不要调高到超过它）
    [string]$RotationMinSdk = '28'
)

$ErrorActionPreference = 'Stop'

function Write-Step($t) { Write-Host "-- $t" -ForegroundColor Cyan }
function Write-Ok($t)   { Write-Host "OK  $t" -ForegroundColor Green }
function Write-Err2($t) { Write-Host "XX  $t" -ForegroundColor Red }

$repoRoot = (& git rev-parse --show-toplevel 2>$null | Select-Object -First 1)
if (-not $repoRoot) { Write-Err2 '当前目录不是 git 仓库'; exit 1 }
$repoRoot = $repoRoot.Trim()

# ── 读取 secrets.properties（key=value，# 为注释） ──────────────────────────
$secFile = Join-Path $repoRoot 'secrets.properties'
if (-not (Test-Path $secFile)) { Write-Err2 "找不到 secrets.properties：$secFile"; exit 1 }
$cfg = @{}
foreach ($line in (Get-Content -LiteralPath $secFile -Encoding UTF8)) {
    $s = $line.Trim()
    if ($s -eq '' -or $s.StartsWith('#')) { continue }
    $i = $s.IndexOf('=')
    if ($i -gt 0) { $cfg[$s.Substring(0, $i).Trim()] = $s.Substring($i + 1).Trim() }
}

$need = @(
    'RELEASE_STORE_FILE', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS',
    'RELEASE_LINEAGE_FILE',
    'RELEASE_OLD_STORE_FILE', 'RELEASE_OLD_STORE_PASSWORD', 'RELEASE_OLD_KEY_ALIAS'
)
$missing = @($need | Where-Object { -not $cfg.ContainsKey($_) -or $cfg[$_] -eq '' })
if ($missing.Count -gt 0) {
    Write-Err2 ('secrets.properties 缺少：' + ($missing -join ', '))
    Write-Host '   需要的键（值请勿写进仓库）：'
    $need | ForEach-Object { Write-Host "     $_=" }
    exit 1
}

$ksFile     = $cfg['RELEASE_STORE_FILE']
$lineage    = $cfg['RELEASE_LINEAGE_FILE']
$oldKs      = $cfg['RELEASE_OLD_STORE_FILE']
foreach ($f in @($ksFile, $lineage, $oldKs)) {
    if (-not (Test-Path $f)) { Write-Err2 "文件不存在：$f"; exit 1 }
}

# ── 定位 apksigner（取 build-tools 里版本号最大的那个） ──────────────────────
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $lp = Join-Path $repoRoot 'local.properties'
    if (Test-Path $lp) {
        $m = Select-String -Path $lp -Pattern '^sdk\.dir\s*=\s*(.+)$'
        if ($m) { $sdk = $m.Matches[0].Groups[1].Value.Trim() -replace '\\\\', '\' }
    }
}
if (-not $sdk -or -not (Test-Path $sdk)) { Write-Err2 '找不到 Android SDK（设 ANDROID_HOME 或在 local.properties 写 sdk.dir）'; exit 1 }
$bt = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
      Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending | Select-Object -First 1
$apksigner = Join-Path $bt.FullName 'apksigner.bat'
if (-not (Test-Path $apksigner)) { Write-Err2 "找不到 apksigner：$apksigner"; exit 1 }

# ── 输入/输出 ───────────────────────────────────────────────────────────────
if (-not $InApk) { $InApk = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk' }
if (-not (Test-Path $InApk)) { Write-Err2 "找不到输入 APK（先跑 assembleRelease）：$InApk"; exit 1 }
if (-not $OutDir) { $OutDir = Split-Path -Parent $InApk }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }

$verLine = Select-String -Path (Join-Path $repoRoot 'app\build.gradle.kts') -Pattern 'val appVersionName\s*=\s*"([^"]+)"'
$ver = if ($verLine) { $verLine.Matches[0].Groups[1].Value } else { 'unknown' }
$ts = (Get-Date).ToString('yyyyMMddHHmm')
$out = Join-Path $OutDir ("StudyAssistant-$ver-$ts.apk")

Write-Step "输入：$InApk"
Write-Step "输出：$out"
Write-Step "新证书：$ksFile（alias=$($cfg['RELEASE_KEY_ALIAS'])）"
Write-Step "lineage：$lineage（rotation-min-sdk=$RotationMinSdk）"

# ── 签名（口令走环境变量，不进命令行） ──────────────────────────────────────
$savedNew = $env:RELEASE_PW; $savedOld = $env:RELEASE_OLD_PW
try {
    $env:RELEASE_PW     = $cfg['RELEASE_STORE_PASSWORD']
    $env:RELEASE_OLD_PW = $cfg['RELEASE_OLD_STORE_PASSWORD']
    $args = @(
        'sign',
        '--ks', $ksFile, '--ks-key-alias', $cfg['RELEASE_KEY_ALIAS'],
        '--ks-pass', 'env:RELEASE_PW', '--key-pass', 'env:RELEASE_PW',
        '--next-signer',
        '--ks', $oldKs, '--ks-key-alias', $cfg['RELEASE_OLD_KEY_ALIAS'],
        '--ks-pass', 'env:RELEASE_OLD_PW', '--key-pass', 'env:RELEASE_OLD_PW',
        '--lineage', $lineage,
        '--rotation-min-sdk-version', $RotationMinSdk,
        '--min-sdk-version', $RotationMinSdk,
        '--v1-signing-enabled', 'false',
        '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'true',
        '--out', $out, $InApk
    )
    $raw = & $apksigner @args 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        Write-Err2 "签名失败（exit=$code）"
        $raw | Select-Object -First 12 | ForEach-Object { Write-Host "    $_" }
        exit 1
    }
} finally {
    $env:RELEASE_PW = $savedNew
    $env:RELEASE_OLD_PW = $savedOld
}

# ── 校验：证书必须是新证书 + v3 方案 + 能过 minSdk ──────────────────────────
Write-Step '校验'
$ver = & $apksigner verify --print-certs --min-sdk-version $RotationMinSdk $out 2>&1
$vcode = $LASTEXITCODE
$ver | Select-String -Pattern 'certificate DN|SHA-256 digest' | Select-Object -First 2 | ForEach-Object { Write-Host "    $($_.Line.Trim())" }
$scheme = & $apksigner verify -v --min-sdk-version $RotationMinSdk $out 2>&1
$v3 = ($scheme | Select-String -Pattern 'Verified using v3 scheme.*true').Count -gt 0
if ($vcode -ne 0 -or -not $v3) {
    Write-Err2 "校验未通过（exit=$vcode, v3=$v3）"
    $scheme | Select-Object -First 12 | ForEach-Object { Write-Host "    $_" }
    exit 1
}
Write-Ok 'v3 方案 + lineage 校验通过'

$h = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
$len = (Get-Item $out).Length
Write-Host ("    文件：{0}" -f (Split-Path -Leaf $out))
Write-Host ("    大小：{0} bytes" -f $len)
Write-Host ("    SHA-256：{0}" -f $h)
Write-Ok "完成：$out"
exit 0
