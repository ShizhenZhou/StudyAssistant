# 提交前密钥扫描（本地兜底）
#
# 背景：本仓库是 GitHub 私有仓库，secret scanning / push protection 属付费的
# GitHub Secret Protection，免费版无法开启（REST API 返回 422），故用本地钩子兜底。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-secrets.ps1
# 退出码：0 = 通过，1 = 发现疑似密钥（提交应被拦住）
#
# 也可以一次性启用为 git 钩子（见 README「密钥与安全约定」）：
#   git config core.hooksPath .githooks

$ErrorActionPreference = "Stop"

# 内容特征（命中即失败）
$contentPatterns = @(
    'sk-[A-Za-z0-9_\-]{20,}',               # DeepSeek / OpenAI 风格
    'AKID[A-Za-z0-9]{16,}',                 # 腾讯云 SecretId
    'LTAI[A-Za-z0-9]{12,}',                 # 阿里云 AccessKeyId
    'AIza[0-9A-Za-z_\-]{30,}',              # Google API Key
    'gh[pousr]_[A-Za-z0-9]{30,}',           # GitHub Token
    'xox[baprs]-[A-Za-z0-9\-]{10,}',        # Slack Token
    '-----BEGIN [A-Z ]*PRIVATE KEY-----'    # 任意私钥文件内容
)

# 文件名特征（命中即失败）
$namePattern = 'adbkey(\.pub)?$|\.jks$|\.keystore$|\.pem$|(^|/)secrets\.properties$|(^|/)local\.properties$|(^|/)LOCAL_SECRETS\.txt$|(^|/)\.env$|(^|/)\.adb-keys/'

# 这些扩展名不做内容扫描（二进制/体积大）
$skipExt = @('.png', '.jpg', '.jpeg', '.webp', '.gif', '.ico', '.apk', '.aab',
             '.jar', '.aar', '.so', '.woff', '.woff2', '.ttf', '.otf', '.zip')

$root = (git rev-parse --show-toplevel).Trim()
Push-Location $root
try {
    # 暂存区里新增/修改/改名的文件（ACMR）
    $staged = @(git diff --cached --name-only --diff-filter=ACMR)
    if ($staged.Count -eq 0) { Write-Host "check-secrets: 暂存区为空，跳过"; exit 0 }

    $problems = @()

    foreach ($f in $staged) {
        $norm = $f -replace '\\', '/'

        if ($norm -match $namePattern) {
            $problems += "文件名命中密钥规则: $norm"
            continue
        }

        $ext = [System.IO.Path]::GetExtension($norm).ToLower()
        if ($skipExt -contains $ext) { continue }

        # 从暂存区（而不是工作区）取内容，确保扫的就是即将提交的东西
        $content = git show ":$norm" 2>$null
        if (-not $content) { continue }
        if ($content.Length -gt 2MB) { Write-Host "check-secrets: 跳过超大文件 $norm"; continue }

        $text = ($content -join "`n")
        foreach ($p in $contentPatterns) {
            $m = [regex]::Match($text, $p)
            if ($m.Success) {
                $s = $m.Value
                $masked = if ($s.Length -gt 8) { $s.Substring(0, 6) + '***(' + $s.Length + ' 字符)' } else { '***' }
                $problems += "内容命中密钥模式: $norm  →  $masked"
            }
        }
    }

    if ($problems.Count -gt 0) {
        Write-Host ""
        Write-Host "❌ check-secrets: 发现 $($problems.Count) 处疑似密钥，已拦下本次提交" -ForegroundColor Red
        $problems | ForEach-Object { Write-Host "   - $_" }
        Write-Host ""
        Write-Host "  确认不是密钥：git commit --no-verify"
        Write-Host "  确实误加了密钥：git rm --cached 该文件，并把该密钥在服务端作废重发"
        exit 1
    }

    Write-Host "✅ check-secrets: 已扫描 $($staged.Count) 个暂存文件，未发现密钥"
    exit 0
}
finally {
    Pop-Location
}
