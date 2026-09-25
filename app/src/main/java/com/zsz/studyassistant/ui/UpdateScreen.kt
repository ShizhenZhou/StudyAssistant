package com.zsz.studyassistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
 */
suspend fun silentUpdateCheck(context: Context) {
    val latest = UpdateChecker.fetchLatest() ?: return          // 失败：静默
    // 版本更高，或同一版本但发布物是更晚的新构建（替换过同名附件）→ 都算有更新
    UpdateBadgeState.set(if (UpdateChecker.hasUpdate(context, latest)) latest.version else null)
}

/** 页面阶段 */
private enum class Phase { IDLE, CHECKING, UP_TO_DATE, FOUND, DOWNLOADING, VERIFYING, READY, FAILED }

/** 是否已允许「安装未知应用」（Android 8+ 需要；更低版本视为允许） */
private fun canInstallApk(context: Context): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls()
    else true
} catch (e: Exception) { true }

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
            latest = rel
            // ① 版本更高 或 ② 同一版本但发布物是更晚的新构建（替换过同名附件）
            if (UpdateChecker.hasUpdate(context, rel)) {
                sameVersion = !UpdateChecker.isNewer(rel.version, installed)
                UpdateBadgeState.set(rel.version)
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
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    Spacer(Modifier.height(8.dp))
    // ── 灰色小字提示 ──
    Text(s["update.hint"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { startDownload() }, shape = smoothPill(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(s["update.download"])
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
                    Button(onClick = { startDownload() }, shape = smoothPill(), enabled = latest != null, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(s["update.download"])
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
