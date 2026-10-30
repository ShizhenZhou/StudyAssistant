package com.zsz.studyassistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.zsz.studyassistant.data.UpdateDownloads
import com.zsz.studyassistant.data.UpdateDownloadMonitor
import com.zsz.studyassistant.data.UpdateDownloadNotifications
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val releases = UpdateChecker.fetchAvailable() ?: return     // 失败：静默
    UpdateChecker.markChecked(context)
    // 版本更高，或同一版本但发布物是更晚的新构建（替换过同名附件）→ 都算有更新
    UpdateBadgeState.set(releases.notification(context)?.version)
}

/** 页面阶段 */
private enum class Phase { RESTORING, IDLE, CHECKING, UP_TO_DATE, FOUND, DOWNLOADING, WAITING, VERIFYING, READY, FAILED }

/** 是否已允许「安装未知应用」 */
internal fun canInstallApk(context: Context): Boolean = try {
    context.packageManager.canRequestPackageInstalls()
} catch (e: Exception) { false }

/** 「上次检查时间」的显示格式：MM-dd HH:mm（当年不显示年份，跨年补上）。数据管理页的「上次备份」也用它 */
internal fun formatCheckTime(ms: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val pattern = if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)) "MM-dd HH:mm" else "yyyy-MM-dd HH:mm"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ms))
}

/** ⬆️ 应用更新：当前版本 + 检查更新按钮 + 灰字提示 + 下载/校验/安装 + 更新历史 */
@Composable
internal fun UpdatePage(taskStore: UpdateDownloads? = null,
    fetchReleases: suspend () -> UpdateChecker.AvailableReleases? = { UpdateChecker.fetchAvailable() }) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    val downloads = remember(context.applicationContext, taskStore) { taskStore ?: UpdateDownloads(context.applicationContext) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var observingDownload by remember { mutableStateOf(true) }
    val installed = remember { UpdateChecker.installedVersion(context) }
    var phase by remember { mutableStateOf(Phase.RESTORING) }
    var operationGeneration by remember { mutableIntStateOf(0) }
    var downloadActionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var latest by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var offers by remember { mutableStateOf<List<UpdateChecker.ReleaseInfo>>(emptyList()) }
    var progress by remember { mutableIntStateOf(-1) }
    var message by remember { mutableStateOf<String?>(null) }
    var permNeeded by remember { mutableStateOf(!canInstallApk(context)) }
    var notificationsAllowed by remember { mutableStateOf(UpdateDownloadNotifications.canPost(context)) }
    var displayedTaskToken by remember { mutableStateOf<String?>(null) }
    var verifiedSha by remember { mutableStateOf<Boolean?>(null) }
    // 「跳过此版本」记在本地（UpdateChecker 里持久化）；跳过只影响**提示**，不影响手动下载
    var skipRevision by remember { mutableIntStateOf(0) }
    var lastCheck by remember { mutableStateOf(UpdateChecker.lastCheckAt(context)) }

    fun compatibilityMessage(rel: UpdateChecker.ReleaseInfo): String? = UpdateChecker.compatibilityError(rel)?.let {
        s.format(it, "api" to (rel.minSdk?.toString() ?: ""))
    }

    fun showDownload(task: UpdateDownloads.Snapshot) {
        displayedTaskToken = task.token
        latest = task.release
        progress = task.progress
        verifiedSha = task.phase == UpdateDownloads.Phase.READY && task.release.assetSha256 != null
        message = task.errorKey?.let { s.format(it, "api" to (task.release.minSdk?.toString() ?: "")) }
        phase = when (task.phase) {
            UpdateDownloads.Phase.DOWNLOADING -> Phase.DOWNLOADING
            UpdateDownloads.Phase.WAITING -> Phase.WAITING
            UpdateDownloads.Phase.READY -> Phase.READY
            UpdateDownloads.Phase.FAILED -> Phase.FAILED
        }
    }
    // Observing the task belongs to the page; the actual transfer belongs to Android.
    LaunchedEffect(downloads, lifecycleOwner, s["update.background"]) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                notificationsAllowed = UpdateDownloadNotifications.canPost(context)
                if (observingDownload) {
                    val generation = operationGeneration
                    val task = downloads.snapshot()
                    if (observingDownload && generation == operationGeneration) {
                        if (task == null) {
                            if (phase == Phase.RESTORING) phase = Phase.IDLE
                        } else {
                            // Upgrading can turn a legacy installed task into FAILED because its
                            // record predates compatibility metadata. Retire obsolete tasks in any phase.
                            val alreadyInstalled = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                !UpdateChecker.hasUpdate(context, task.release)
                            }
                            if (observingDownload && generation == operationGeneration) {
                                if (alreadyInstalled) {
                                    downloads.cancel(task.token)
                                    if (observingDownload && generation == operationGeneration) {
                                        // A stale downloaded package says nothing about the latest release online.
                                        latest = null; phase = Phase.IDLE
                                    }
                                } else {
                                    showDownload(task)
                                    UpdateDownloadMonitor.ensureForRestored(context, task)
                                }
                            }
                        }
                        permNeeded = !canInstallApk(context)
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    fun goInstall() {
        // A click acknowledges the notice even if permission or subsequent APK verification fails.
        displayedTaskToken?.let { UpdateDownloadNotifications.acknowledgeCompletion(context, it) }
        val generation = ++operationGeneration
        observingDownload = false
        phase = Phase.VERIFYING
        scope.launch {
            try {
                val task = downloads.forInstall()
                if (generation != operationGeneration) return@launch
                if (task == null) { phase = Phase.FAILED; message = s["update.downloadMissing"]; return@launch }
                showDownload(task)
                observingDownload = true
                val file = task.file ?: return@launch
                if (task.phase != UpdateDownloads.Phase.READY) return@launch
                if (!canInstallApk(context)) { permNeeded = true; openUnknownSourcesSettings(context); return@launch }
                permNeeded = false
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { if (generation == operationGeneration) { observingDownload = false; phase = Phase.READY; message = s["update.installFailed"] } }
        }
    }

    fun startCheck() {
        val generation = ++operationGeneration
        observingDownload = false
        phase = Phase.CHECKING
        message = null
        scope.launch {
            val releases = fetchReleases()
            if (generation != operationGeneration) return@launch
            if (releases == null) {
                phase = Phase.FAILED
                message = s["update.netError"]
                return@launch
            }
            // 成功查到（含"已是最新"）才记「上次检查时间」；失败不记
            UpdateChecker.markChecked(context)
            lastCheck = UpdateChecker.lastCheckAt(context)
            offers = releases.updates { UpdateChecker.hasUpdate(context, it) }
            val rel = offers.firstOrNull()
            latest = rel
            // ① 版本更高 或 ② 同一版本但发布物是更晚的新构建（替换过同名附件）
            if (rel != null) {
                // 被跳过的版本不再顶红点（页面上仍显示，可手动下载/取消跳过）
                UpdateBadgeState.set(releases.notification(context)?.version)
                message = compatibilityMessage(rel)
                phase = Phase.FOUND
            } else {
                UpdateBadgeState.set(null)
                phase = Phase.UP_TO_DATE
            }
        }
    }

    fun startDownload(target: UpdateChecker.ReleaseInfo? = latest) {
        // Ignore a second offer's stale click before Compose removes the old buttons.
        if (phase != Phase.FOUND && phase != Phase.FAILED) return
        val rel = target ?: return
        latest = rel
        compatibilityMessage(rel)?.let { message = it; phase = Phase.FAILED; return }
        val generation = ++operationGeneration
        phase = Phase.DOWNLOADING; progress = 0; message = null
        observingDownload = false
        val notification = UpdateDownloadMonitor.request(context)
        val handoff = downloads.enqueue(rel, notification = notification)
        downloadActionJob = handoff
        scope.launch {
            try {
                val task = handoff.await()
                if (generation == operationGeneration) { showDownload(task); observingDownload = true }
            }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { if (generation == operationGeneration) { phase = Phase.FAILED; message = s["update.downloadUnavailable"] } }
        }
    }
    fun cancelDownload() {
        val generation = ++operationGeneration
        downloadActionJob?.cancel()
        observingDownload = false
        scope.launch {
            try {
                downloads.cancel()
                if (generation == operationGeneration) { phase = Phase.FOUND; progress = -1; message = null }
            }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { if (generation == operationGeneration) { phase = Phase.FAILED; message = s["update.downloadUnavailable"] } }
        }
    }

    // ── 当前版本 ──
    Text("Study Assistant v$installed", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(s["update.current"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))

    // ── 检查更新：圆角大按钮 ──
    val busy = phase == Phase.RESTORING || phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.WAITING || phase == Phase.VERIFYING
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
            if (phase != Phase.FOUND && latest?.channel == UpdateChecker.Channel.BETA) {
                Text(s["update.channel.beta"], fontWeight = FontWeight.SemiBold)
                Text(s["update.betaWarning"], style = MaterialTheme.typography.bodySmall, color = BADGE_RED)
            }
            when (phase) {
                Phase.RESTORING -> Text(s["update.restoring"], style = MaterialTheme.typography.bodyMedium)
                Phase.IDLE -> Text(s["update.check"], style = MaterialTheme.typography.bodyMedium)
                Phase.CHECKING -> Text(s["update.checking"], style = MaterialTheme.typography.bodyMedium)
                Phase.UP_TO_DATE -> Text(s["update.upToDate"], style = MaterialTheme.typography.bodyMedium)
                Phase.FOUND -> {
                    (offers.ifEmpty { listOfNotNull(latest) }).forEachIndexed { index, rel ->
                        if (index > 0) {
                            Spacer(Modifier.height(18.dp))
                            androidx.compose.material3.HorizontalDivider()
                            Spacer(Modifier.height(14.dp))
                        }
                        val beta = rel.channel == UpdateChecker.Channel.BETA
                        val channelKey = if (beta) "update.channel.beta" else "update.channel.stable"
                        Text(s[channelKey] + " v" + rel.version, fontWeight = FontWeight.SemiBold)
                        if (beta) Text(s["update.betaWarning"], style = MaterialTheme.typography.bodySmall, color = BADGE_RED)
                        if (UpdateChecker.normalizeVersion(rel.version) == UpdateChecker.normalizeVersion(installed)) {
                            Text(s.format("update.sameVersion", "name" to rel.assetName), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(rel.assetName + "  " + String.format("%.1f MB", rel.assetSize / 1024.0 / 1024.0),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        val notes = UpdateChecker.prettyReleaseNotes(rel.notes)
                        if (notes.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(s["update.notes"], style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text(notes, style = MaterialTheme.typography.bodySmall)
                        }
                        val error = compatibilityMessage(rel)
                        error?.let { Text(it, color = BADGE_RED, style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { startDownload(rel) }, enabled = error == null,
                            shape = smoothPill(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(s[if (beta) "update.downloadBeta" else "update.downloadStable"])
                        }
                        Spacer(Modifier.height(12.dp))
                        val skipped = remember(skipRevision, rel) { UpdateChecker.isSkipped(context, rel) }
                        if (skipped) Text(s.format("update.skipped", "v" to rel.version), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            if (skipped) UpdateChecker.clearSkippedVersion(context, rel.channel)
                            else UpdateChecker.skipVersion(context, rel.version, rel.channel)
                            skipRevision++
                            UpdateBadgeState.set(offers.sortedBy { it.channel == UpdateChecker.Channel.BETA }
                                .firstOrNull { UpdateChecker.shouldNotify(context, it) }?.version)
                        }, shape = smoothPill(), modifier = Modifier.fillMaxWidth().height(44.dp)) {
                            Text(s[if (skipped) "update.unskip" else "update.skip"])
                        }
                    }
                }
                Phase.DOWNLOADING, Phase.WAITING -> {
                    Text(
                        if (phase == Phase.WAITING) s["update.waiting"]
                        else if (progress >= 0) s.format("update.downloading", "p" to "$progress") else s["update.download"],
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
                    Spacer(Modifier.height(8.dp))
                    Text(s["update.background"], style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                    androidx.compose.material3.TextButton(onClick = { cancelDownload() }) { Text(s["update.cancelDownload"]) }
                }
                Phase.VERIFYING -> Text(s["update.verifying"], style = MaterialTheme.typography.bodyMedium)
                Phase.READY -> {
                    message?.let { Text(it, color = BADGE_RED, style = MaterialTheme.typography.bodySmall) }
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
                    if (latest == null || latest?.let { UpdateChecker.compatibilityError(it) != null } == true) {
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

    Spacer(Modifier.height(12.dp))
    if (!notificationsAllowed) {
        androidx.compose.material3.TextButton(onClick = { UpdateDownloadNotifications.openSettings(context) }) {
            Text(s["update.notif.settings"])
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
