#Requires -Version 5.1
<#
.SYNOPSIS
    一键「密钥扫描 → 提交 → 推送 → 核对」，为 DSH 沙箱环境做了适配。

.DESCRIPTION
    本仓库在 DSH 沙箱里有两个限制，这个脚本把它们都绕开了：
      1) 沙箱内 sh.exe 起不来（couldn't create signal pipe）→ .githooks/pre-commit（sh 脚本）
         无法运行，所以这里改为直接调用 tools/check-secrets.ps1 做提交前扫描；
      2) gh 的凭据 helper 是 shell 型（!gh ...）同样跑不了 → 这里改用 GIT_ASKPASS
         （原生 .cmd，不经 sh 调用），token 只存在于环境变量，绝不进入命令行/日志。
    传输：直连与本地代理二选一，**记住上次成功的方式**（存 .git/dsh-transport），
    下次优先用它，避免每次都白等直连超时；被记住的方式失败会自动换另一种。
    另外沙箱内系统 schannel 不可用，所有 git 调用都带 -c http.sslBackend=openssl。

.EXAMPLE
    .\tools\git-sync.ps1 -Message "feat(v0.5.3): 公式键盘支持自定义符号"

.EXAMPLE
    .\tools\git-sync.ps1 -Message "docs: 更新说明" -NoPush      # 只提交，不推送

.EXAMPLE
    .\tools\git-sync.ps1 -NoCommit                              # 工作区已提交，只推送

.NOTES
    退出码：0 = 成功；1 = 失败（扫描未通过 / 提交失败 / 推送失败）。
    输出只用 ASCII 标记（OK / !! / XX），以免旧版控制台把符号显示成乱码。
#>
[CmdletBinding()]
param(
    # 提交信息（-NoCommit 时可省略）
    [string]$Message,

    [string]$Remote = 'origin',
    [string]$Branch = 'main',

    # 直连失败时使用的本地代理端口（0 = 只用直连）
    [int]$ProxyPort = 12450,

    [switch]$NoCommit,
    [switch]$NoPush
)

$ErrorActionPreference = 'Continue'

function Write-Step($text)  { Write-Host "-- $text" -ForegroundColor Cyan }
function Write-Ok($text)    { Write-Host "OK  $text" -ForegroundColor Green }
function Write-Warn2($text) { Write-Host "!!  $text" -ForegroundColor Yellow }
function Write-Err2($text)  { Write-Host "XX  $text" -ForegroundColor Red }

$askpass = $null
$savedToken = $env:GIT_TOKEN
$savedAskpass = $env:GIT_ASKPASS
$savedPrompt = $env:GIT_TERMINAL_PROMPT

# 只保留字符串行，丢掉 PowerShell 混进 stderr 的 ErrorRecord
# （否则输出里会出现 "FullyQualifiedErrorId : NativeCommandError" 这类噪声）
function Invoke-Git {
    param([string[]]$GitArgs)
    $raw = & git @GitArgs 2>&1
    $code = $LASTEXITCODE
    $lines = @($raw | Where-Object { $_ -is [string] })
    return [pscustomobject]@{ Code = $code; Out = ($lines -join "`n").Trim() }
}

function Get-LastLine($text) {
    $l = @($text -split "`n" | Where-Object { $_.Trim() -ne '' })
    if ($l.Count -gt 0) { return $l[-1].Trim() }
    return ''
}

# 传输方式对应的 git 参数（direct 时显式清空 http.proxy，避免继承环境的代理设置）
function Get-TransportArgs([string]$mode, [int]$port) {
    if ($mode -eq 'proxy') { return @('-c', "http.proxy=http://127.0.0.1:$port") }
    return @('-c', 'http.proxy=')
}

try {
    # ── 0. 定位仓库 ─────────────────────────────────────────────
    $repoRoot = (& git rev-parse --show-toplevel 2>$null | Select-Object -First 1)
    if (-not $repoRoot) { Write-Err2 '当前目录不是 git 仓库'; exit 1 }
    Set-Location $repoRoot
    Write-Step "仓库：$repoRoot"

    if (-not $NoCommit -and [string]::IsNullOrWhiteSpace($Message)) {
        Write-Err2 '需要 -Message "<提交信息>"（或用 -NoCommit 只推送）'
        exit 1
    }

    # ── 1. 准备凭据（GIT_ASKPASS，不经 sh） ─────────────────────
    $token = (& gh auth token 2>$null | Select-Object -First 1)
    if (-not $token) { Write-Err2 '取不到 gh token（请先 gh auth login）'; exit 1 }
    $env:GIT_TOKEN = $token.Trim()
    $env:GIT_TERMINAL_PROMPT = '0'
    $askpass = Join-Path $env:TEMP ("dsh-askpass-{0}.cmd" -f $PID)
    Set-Content -Path $askpass -Value "@echo off`r`necho %GIT_TOKEN%" -Encoding ASCII
    $env:GIT_ASKPASS = $askpass

    $originUrl = (& git remote get-url $Remote 2>$null | Select-Object -First 1)
    if (-not $originUrl) { Write-Err2 "找不到远端 $Remote"; exit 1 }
    $owner = [regex]::Match($originUrl, 'github\.com[:/]([^/]+)/').Groups[1].Value
    if (-not $owner) { Write-Err2 "无法从远端地址解析 owner：$originUrl"; exit 1 }
    # 认证只写在「用户名」位置，密码由 askpass 提供 → token 不进命令行
    $authUrl = $originUrl -replace '^https://', "https://$owner@"
    $common = @('-c', 'http.sslBackend=openssl', '-c', 'credential.helper=', '-c', 'credential.https://github.com.helper=')

    # 传输顺序：上次成功的方式优先
    $stateFile = Join-Path $repoRoot '.git/dsh-transport'
    $lastMode = if (Test-Path $stateFile) { (Get-Content $stateFile -Raw).Trim() } else { 'direct' }
    $order = @()
    if ($lastMode -eq 'proxy' -and $ProxyPort -gt 0) { $order += 'proxy' }
    $order += 'direct'
    if ($ProxyPort -gt 0 -and $order -notcontains 'proxy') { $order += 'proxy' }

    # ── 2. 提交前密钥扫描（替代沙箱里跑不了的 sh 钩子） ──────────
    $scan = Join-Path $repoRoot 'tools/check-secrets.ps1'
    if (Test-Path $scan) {
        Write-Step '密钥扫描'
        & powershell -NoProfile -ExecutionPolicy Bypass -File $scan | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Err2 '密钥扫描未通过，已中止提交'; exit 1 }
        Write-Ok '密钥扫描通过'
    } else {
        Write-Warn2 '未找到 tools/check-secrets.ps1，跳过扫描'
    }

    # ── 3. 提交 ─────────────────────────────────────────────────
    if (-not $NoCommit) {
        Write-Step '暂存并提交'
        Invoke-Git @('add', '-A') | Out-Null
        $staged = (& git diff --cached --name-only 2>$null)
        if (-not $staged) {
            Write-Warn2 '没有需要提交的改动'
        } else {
            # core.hooksPath= 跳过 sh 钩子（扫描已在第 2 步做过）
            $c = Invoke-Git (@('-c', 'core.hooksPath=') + @('commit', '-m', $Message))
            if ($c.Code -ne 0) { Write-Err2 "提交失败：`n$($c.Out)"; exit 1 }
            Write-Ok (Get-LastLine $c.Out)
        }
    }

    $localSha = (& git rev-parse HEAD 2>$null | Select-Object -First 1)

    # ── 4. 推送（按记住的方式优先，失败换另一种） ────────────────
    $via = $null
    if (-not $NoPush) {
        Write-Step '推送'
        foreach ($mode in $order) {
            $t0 = Get-Date
            $p = Invoke-Git ($common + (Get-TransportArgs $mode $ProxyPort) + @('push', $authUrl, $Branch))
            if ($p.Code -eq 0) {
                $via = $mode
                Write-Ok ("推送成功（{0}，{1:N1}s）：{2}" -f $mode, ((Get-Date) - $t0).TotalSeconds, (Get-LastLine $p.Out))
                break
            }
            Write-Warn2 ("{0} 失败，换另一种传输方式" -f $mode)
        }
        if (-not $via) { Write-Err2 '推送失败（直连与代理都不通）'; exit 1 }
        Set-Content -Path $stateFile -Value $via -Encoding ASCII

        # 同步本地跟踪引用（否则 git status 会一直显示 ahead），用同一种传输方式
        $f = Invoke-Git ($common + (Get-TransportArgs $via $ProxyPort) + @('fetch', $authUrl, ("{0}:refs/remotes/{1}/{0}" -f $Branch, $Remote)))
        if ($f.Code -ne 0) { Write-Warn2 '跟踪引用同步失败（不影响推送结果）' }
    }

    # ── 5. 核对 ─────────────────────────────────────────────────
    Write-Step '核对'
    $lsMode = if ($via) { $via } else { $order[0] }
    $ls = Invoke-Git ($common + (Get-TransportArgs $lsMode $ProxyPort) + @('ls-remote', $authUrl, "refs/heads/$Branch"))
    $remoteSha = ($ls.Out -split '\s+' | Select-Object -First 1)
    Write-Host ("  本地 HEAD    : {0}" -f $localSha)
    Write-Host ("  远端 $Branch : {0}" -f $remoteSha)
    if ($localSha -and $remoteSha -and $localSha -eq $remoteSha) {
        Write-Ok '本地与远端一致'
    } elseif ($NoPush) {
        Write-Warn2 '未推送（-NoPush）'
    } else {
        Write-Warn2 '本地与远端不一致，请检查'
    }
    Write-Host ("  " + (& git status --short --branch | Select-Object -First 1))
    exit 0
}
finally {
    if ($askpass -and (Test-Path $askpass)) { Remove-Item $askpass -Force -ErrorAction SilentlyContinue }
    $env:GIT_TOKEN = $savedToken
    $env:GIT_ASKPASS = $savedAskpass
    $env:GIT_TERMINAL_PROMPT = $savedPrompt
}
