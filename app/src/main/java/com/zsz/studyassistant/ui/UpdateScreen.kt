package com.zsz.studyassistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.zsz.studyassistant.data.UpdateChecker
import kotlinx.coroutines.launch
import java.io.File

/** 红点 / 链接配色：不跟主题走 —— 红点要显眼，仓库链接按要求用**蓝色** */
internal val BADGE_RED = Color(0xFFE53935)
internal val LINK_BLUE = Color(0xFF1A73E8)

/**
 * 红色消息气泡「①」。
 * ⚠️ 不要用 `Box(背景圆) + Text("1")` 那种写法：Text 的行高与字体留白（ascent/descent padding）
 * 会让数字**不在圆心**（用户明确反馈"数字 1 不在圆圈正中间"）。
 * 这里用 Canvas 画圆 + 按数字的**紧致外框（getBoundingBox）**精确居中，两个轴都对齐。
 */
@Composable
internal fun UpdateBadgeDot(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    fontSize: TextUnit = 11.sp
) {
    val measurer = rememberTextMeasurer()
    val layout = remember(measurer, fontSize) {
        measurer.measure(
            text = "1",
            style = TextStyle(color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold)
        )
    }
    Canvas(modifier.size(size)) {
        drawCircle(color = BADGE_RED)
        // 数字"1"的紧致外框（相对布局左上角），据此把它摆到圆心
        val ink = layout.getBoundingBox(0)
        val dx = (this.size.width - ink.width) / 2f - ink.left
        val dy = (this.size.height - ink.height) / 2f - ink.top
        drawText(layout, topLeft = Offset(dx, dy))
    }
}

/**
 * 「有新版本」全局标记。
 * 启动时静默检查写入（[silentUpdateCheck]）；设置页入口与底栏「设置」用它显示红色气泡①。
 */
object UpdateBadgeState {
    var availableVersion by mutableStateOf<String?>(null)
        private set

    fun set(v: String?) { availableVersion = v }
}

/**
 * 启动时的静默检查：**网络失败或没有新版本 → 什么都不做**（不弹提示）；
 * 查到更新 → 置上标记，让设置入口/底栏显示红点①。
 *
 * 注意：用户在更新页点过「跳过此版本」的版本**不再提示**（仍可手动检查/下载）；
 * 只有**成功**查到（含"已是最新"）才记录"上次检查时间"，失败不记。
 */
suspend fun silentUpdateCheck(context: Context) {
    val latest = UpdateChecker.fetchLatest() ?: return          // 失败：静默
    UpdateChecker.markChecked(context)
    // 版本更高，或同一版本但发布物是更晚的新构建（替换过同名附件）→ 都算有更新
    UpdateBadgeState.set(if (UpdateChecker.shouldNotify(context, latest)) latest.version else null)
}

/** 页面阶段 */
private enum class Phase { IDLE, CHECKING, UP_TO_DATE, FOUND, DOWNLOADING, VERIFYING, READY, FAILED }

/** 是否已允许「安装未知应用」（Android 8+ 需要；更低版本视为允许） */
private fun canInstallApk(context: Context): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls()
    else true
} catch (e: Exception) { true }

/** 「上次检查时间」的显示格式：MM-dd HH:mm（当年不显示年份，跨年补上）。数据管理页的「上次备份」也用它 */
internal fun formatCheckTime(ms: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val pattern = if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)) "MM-dd HH:mm" else "yyyy-MM-dd HH:mm"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ms))
}

/** ⬆️ 应用更新：当前版本 + 检查更新按钮 + 灰字提示 + 下载/校验/安装 + 更新历史 */
@Composable
fun UpdatePage() {
    val context = LocalContext.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    val installed = remember { UpdateChecker.installedVersion(context) }
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var latest by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var progress by remember { mutableIntStateOf(-1) }
    var apk by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var permNeeded by remember { mutableStateOf(!canInstallApk(context)) }
    var verifiedSha by remember { mutableStateOf<Boolean?>(null) }
    var sameVersion by remember { mutableStateOf(false) }   // true = 同一版本的新构建
    // 「跳过此版本」记在本地（UpdateChecker 里持久化）；skipped 只影响**提示**，不影响手动下载
    var skipped by remember { mutableStateOf(UpdateChecker.skippedVersion(context)) }
    var lastCheck by remember { mutableStateOf(UpdateChecker.lastCheckAt(context)) }

    fun goInstall() {
        val f = apk ?: return
        if (!canInstallApk(context)) { permNeeded = true; openUnknownSourcesSettings(context); return }
        permNeeded = false
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            message = e.message
            phase = Phase.FAILED
        }
    }

    fun startCheck() {
        phase = Phase.CHECKING
        message = null
        scope.launch {
            val rel = UpdateChecker.fetchLatest()
            if (rel == null) {
                phase = Phase.FAILED
                message = s["update.netError"]
                return@launch
            }
            // 成功查到（含"已是最新"）才记「上次检查时间」；失败不记
            UpdateChecker.markChecked(context)
            lastCheck = UpdateChecker.lastCheckAt(context)
            latest = rel
            skipped = UpdateChecker.skippedVersion(context)
            // ① 版本更高 或 ② 同一版本但发布物是更晚的新构建（替换过同名附件）
            if (UpdateChecker.hasUpdate(context, rel)) {
                sameVersion = !UpdateChecker.isNewer(rel.version, installed)
                // 被跳过的版本不再顶红点（页面上仍显示，可手动下载/取消跳过）
                UpdateBadgeState.set(if (UpdateChecker.isSkipped(context, rel)) null else rel.version)
                phase = Phase.FOUND
            } else {
                sameVersion = false
                UpdateBadgeState.set(null)
                phase = Phase.UP_TO_DATE
            }
        }
    }

    fun startDownload() {
        val rel = latest ?: return
        phase = Phase.DOWNLOADING
        progress = 0
        message = null
        UpdateChecker.clearDownloads(context)   // 先清掉上次残留，再下新的
        scope.launch {
            val dest = File(UpdateChecker.downloadDir(context), rel.assetName)
            val ok = UpdateChecker.download(rel.assetUrl, dest) { p -> progress = p }
            if (!ok) {
                phase = Phase.FAILED
                message = s["update.netError"]
                return@launch
            }
            phase = Phase.VERIFYING
            val res = UpdateChecker.verify(context, dest, rel.assetSha256)
            verifiedSha = res.shaOk && rel.assetSha256 != null
            if (!res.ok) {
                dest.delete()
                phase = Phase.FAILED
                message = s["update.verifyFailed"]
                return@launch
            }
            apk = dest
            phase = Phase.READY
        }
    }

    // ── 当前版本 ──
    Text("Study Assistant v$installed", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(s["update.current"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))

    // ── 检查更新：圆角大按钮 ──
    val busy = phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING
    Button(
        onClick = { startCheck() },
        shape = smoothPill(),
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Text(
            if (phase == Phase.CHECKING) s["update.checking"] else s["update.check"],
            fontSize = BTN_LABEL_BIG,
            fontWeight = FontWeight.SemiBold
        )
    }
    Spacer(Modifier.height(8.dp))
    // ── 灰色小字提示 + 上次检查时间 ──
    Text(s["update.hint"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(2.dp))
    Text(
        if (lastCheck > 0L) s.format("update.lastCheck", "t" to formatCheckTime(lastCheck)) else s["update.neverChecked"],
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Spacer(Modifier.height(16.dp))

    // ── 状态 / 下载 / 安装 ──
    Card(shape = smoothShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            when (phase) {
                Phase.IDLE -> Text(s["update.check"], style = MaterialTheme.typography.bodyMedium)
                Phase.CHECKING -> Text(s["update.checking"], style = MaterialTheme.typography.bodyMedium)
                Phase.UP_TO_DATE -> Text(s["update.upToDate"], style = MaterialTheme.typography.bodyMedium)
                Phase.FOUND -> {
                    Text(
                        if (sameVersion)
                            s.format("update.sameVersion", "name" to (latest?.assetName ?: ""))
                        else
                            s.format("update.found", "v" to ("v" + (latest?.version ?: ""))),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    latest?.let { rel ->
                        if (rel.assetSize > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                rel.assetName + "  " + String.format("%.1f MB", rel.assetSize / 1024.0 / 1024.0),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        // 新版本的更新说明（GitHub Release 说明，Markdown 已做最小化清洗）
                        val notes = UpdateChecker.prettyReleaseNotes(rel.notes)
                        if (notes.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(s["update.notes"], style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(notes, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (skipped != null && latest?.version == skipped) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            s.format("update.skipped", "v" to skipped!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { startDownload() }, shape = smoothPill(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(s["update.download"])
                    }
                    Spacer(Modifier.height(8.dp))
                    // 跳过此版本 / 取消跳过（跳过后启动静默检查不再顶红点）
                    val relVer = latest?.version
                    OutlinedButton(
                        shape = smoothPill(),
                        onClick = {
                            val v = relVer ?: return@OutlinedButton
                            val nowSkipped = skipped != v
                            if (nowSkipped) { UpdateChecker.skipVersion(context, v); skipped = v }
                            else { UpdateChecker.clearSkippedVersion(context); skipped = null }
                            UpdateBadgeState.set(if (nowSkipped) null else v)
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text(if (skipped != null && skipped == relVer) s["update.unskip"] else s["update.skip"])
                    }
                }
                Phase.DOWNLOADING -> {
                    Text(
                        if (progress >= 0) s.format("update.downloading", "p" to "$progress") else s["update.download"],
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    if (progress >= 0) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(smoothPill())
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(smoothPill()))
                    }
                }
                Phase.VERIFYING -> Text(s["update.verifying"], style = MaterialTheme.typography.bodyMedium)
                Phase.READY -> {
                    Text(
                        if (verifiedSha == true) s["update.verified"] else s["update.verifiedSigner"],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (permNeeded) {
                        Spacer(Modifier.height(8.dp))
                        Text(s["update.needPermission"], style = MaterialTheme.typography.bodySmall, color = BADGE_RED)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { goInstall() }, shape = smoothPill(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(s["update.install"])
                    }
                }
                Phase.FAILED -> {
                    Text(message ?: s["update.netError"], style = MaterialTheme.typography.bodyMedium, color = BADGE_RED)
                    Spacer(Modifier.height(12.dp))
                    // 检查就失败（latest 还是空）→ 重试「检查」；下载/校验失败 → 重新「下载」
                    if (latest == null) {
                        Button(onClick = { startCheck() }, shape = smoothPill(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(s["update.retry"])
                        }
                    } else {
                        Button(onClick = { startDownload() }, shape = smoothPill(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(s["update.redownload"])
                        }
                    }
                }
            }
        }
    }

    // 兜底：网络/校验都不行时，用浏览器打开发布页手动装。
    // 放在灰框**外面**，样式对齐主页底部两个按钮（OutlinedButton 描边、无填充）
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = { openUrl(context, UpdateChecker.RELEASES_URL) },
        shape = smoothPill(),
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Text(s["update.openBrowser"], fontSize = 16.sp)
    }

    Spacer(Modifier.height(24.dp))
    // ── 更新历史（原来「关于」页里的全部更新内容）──
    Text(s["about.changelog"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(changelogAnnotated(changelogText(LocalUiLang.current)), style = MaterialTheme.typography.bodySmall)
}

/** 跳系统「安装未知应用」授权页 */
private fun openUnknownSourcesSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) { /* 个别 ROM 没有该页面：忽略 */ }
}
