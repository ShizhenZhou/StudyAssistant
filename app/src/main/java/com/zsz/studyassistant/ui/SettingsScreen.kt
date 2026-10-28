package com.zsz.studyassistant.ui

import android.Manifest
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.pow
import kotlin.math.ln
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.AiLang
import com.zsz.studyassistant.data.ApiKeyStore
import com.zsz.studyassistant.data.AutoBackup
import com.zsz.studyassistant.data.CrashLogger
import com.zsz.studyassistant.data.ReminderScheduler
import com.zsz.studyassistant.data.UpdateChecker

/** 设置：主页面为入口列表，点进去到二级页面进行具体设置 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(vm: MainViewModel) {
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(AppNav.SettingsPages.MAIN) }
    // ★ 设置页的"上一级"由页面树统一算（ui/AppNav.kt → SettingsPages.parent）：
    //   ai.* → AI 管理，data.* → 数据管理；返回箭头和系统返回都使用同一个父节点。
    BackHandler(enabled = page != AppNav.SettingsPages.MAIN) {
        page = AppNav.SettingsPages.parent(page)
    }
    // ★ 2026-10-16：从"数据格式需要升级"提示 / "旧格式数据"警示**直达**「数据升级 / 修复」子页
    LaunchedEffect(vm.openDataUpgradePage) {
        if (vm.openDataUpgradePage) {
            page = AppNav.SettingsPages.DATA_UPGRADE
            vm.openDataUpgradePage = false
        }
    }
    LaunchedEffect(vm.openBackupManagementPage) {
        if (vm.openBackupManagementPage) {
            page = AppNav.SettingsPages.DATA_BACKUP
            vm.openBackupManagementPage = false
        }
    }
    LaunchedEffect(vm.openUpdatePage) {
        if (vm.openUpdatePage) {
            page = AppNav.SettingsPages.UPDATE
            vm.openUpdatePage = false
        }
    }
    val s = LocalStrings.current

    when (page) {
        AppNav.SettingsPages.AI -> SettingsSubPage(s["settings.aiManage"], { page = AppNav.SettingsPages.parent(page) }) {
            SettingEntry(s["settings.api"]) { page = AppNav.SettingsPages.AI_API }
            SettingEntry(s["settings.aiCropPage"]) { page = AppNav.SettingsPages.AI_CROP }
            SettingEntry(s["settings.solveSettings"]) { page = AppNav.SettingsPages.AI_SOLVE }
            SettingEntry(s["settings.customPrompt"]) { page = AppNav.SettingsPages.AI_PROMPT }
        }
        AppNav.SettingsPages.AI_API -> SettingsSubPage(s["settings.api"], { page = AppNav.SettingsPages.parent(page) }) {
            ApiSettings(vm)
        }
        AppNav.SettingsPages.AI_CROP -> SettingsSubPage(s["settings.aiCropPage"], { page = AppNav.SettingsPages.parent(page) }) {
            val ctx = LocalContext.current
            var aiCropOn by remember { mutableStateOf(com.zsz.studyassistant.data.CapturePrefs.aiCropEnabled(ctx)) }
            AiCropSwitchSettings(checked = aiCropOn, onChecked = {
                aiCropOn = it
                com.zsz.studyassistant.data.CapturePrefs.setAiCropEnabled(ctx, it)
            })
            if (aiCropOn) {
                Spacer(Modifier.height(20.dp))
                AiCropTimeoutSettings()
            }
        }
        AppNav.SettingsPages.AI_SOLVE -> SettingsSubPage(s["settings.solveSettings"], { page = AppNav.SettingsPages.parent(page) }) {
            AiSolveModelSettings()
        }
        AppNav.SettingsPages.AI_PROMPT -> {
            val ctx = LocalContext.current
            // ★ 2026-10-13（用户要求 · 第 2 版）：自定义要求的文本 + "是否已修改"状态提到页面这一层，
            //   把「保存」做成**跟着键盘出现**的按钮：
            //     · 键盘**收起**时 → 保存按钮就在输入框下面（与改动前完全一样，页面底部不出现任何浮动条）
            //     · 键盘**弹出**时 → 保存按钮挪到键盘正上方（不用先收键盘就能点）
            //   ⚠️ 首版做成了"常驻底栏"，用户实测反馈"没弹键盘时它也在、还跟着乱跑" → 改为按 IME 可见性切换。
            var cpText by remember { mutableStateOf(com.zsz.studyassistant.data.CapturePrefs.customPrompt(ctx)) }
            var cpDirty by remember { mutableStateOf(false) }
            // 键盘（IME）当前是否可见：可见才给底栏
            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val savePrompt = {
                com.zsz.studyassistant.data.CapturePrefs.setCustomPrompt(ctx, cpText)
                cpDirty = false
            }
            // 键盘弹出时贴在键盘上方的那颗「保存」（没改动时置灰）
            val promptSaveBar: @Composable () -> Unit = {
                Button(onClick = savePrompt, enabled = cpDirty) { Text(s["common.save"]) }
            }
            SettingsSubPage(
                title = s["settings.customPrompt"],
                onBack = { page = AppNav.SettingsPages.parent(page) },
                // ★ 关键：`takeIf` —— 键盘收起时传 null，底栏**整条都不渲染**（页面回到原来的样子）
                bottomBar = promptSaveBar.takeIf { imeVisible }
            ) {
                AiCustomPromptSettings(cpText, onText = { cpText = it; cpDirty = true })
                // 键盘收起时：保存按钮留在输入框下面（原来的位置）—— 与浮动底栏二选一，绝不同时出现
                if (!imeVisible) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = savePrompt, enabled = cpDirty) { Text(s["common.save"]) }
                    }
                }
            }
        }
        AppNav.SettingsPages.LANG -> SettingsSubPage(s["lang.title"], { page = AppNav.SettingsPages.parent(page) }) { LanguageSettings(vm) }
        AppNav.SettingsPages.THEME -> SettingsSubPage(s["settings.theme"], { page = AppNav.SettingsPages.parent(page) }) { ThemeSettings(vm) { page = it } }
        AppNav.SettingsPages.NOTIFY -> SettingsSubPage(s["settings.group.notifyBackground"], { page = AppNav.SettingsPages.parent(page) }) { NotifyBackgroundSettings() }
        // 数据管理：备份管理（含自动备份）/ 崩溃日志 / 数据升级·修复 / 标签整理
        AppNav.SettingsPages.DATA -> SettingsSubPage(s["settings.data"], { page = AppNav.SettingsPages.parent(page) }) { DataMenu(vm) { page = it } }
        AppNav.SettingsPages.DATA_BACKUP -> SettingsSubPage(s["data.backup"], { page = AppNav.SettingsPages.parent(page) }) { DataSettings(vm) }
        AppNav.SettingsPages.DATA_CRASH -> SettingsSubPage(s["data.crash"], { page = AppNav.SettingsPages.parent(page) }) { DataSettings(vm, "crash") }
        AppNav.SettingsPages.DATA_UPGRADE -> SettingsSubPage(s["data.upgradePage"], { page = AppNav.SettingsPages.parent(page) }) { DataUpgradePage(vm) }
        AppNav.SettingsPages.DATA_TAG_MERGE -> SettingsSubPage(s["tagMerge.title"], { page = AppNav.SettingsPages.parent(page) }) { TagMergePage(vm) }
        AppNav.SettingsPages.UPDATE -> SettingsSubPage(s["settings.update"], { page = AppNav.SettingsPages.parent(page) }) { UpdatePage() }
        AppNav.SettingsPages.ABOUT -> SettingsSubPage(s["settings.about"], { page = AppNav.SettingsPages.parent(page) }) { AboutPage() }
        else -> SettingsMain { page = it }
    }
}

/** ℹ️ 关于：上面「应用信息」，下面「功能」说明（更新历史移到「应用更新」页） */
@Composable
private fun AboutPage() {
    val s = LocalStrings.current
    Text(s["about.info"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    AboutSettings()
    Spacer(Modifier.height(20.dp))
    Text(s["about.tech"], style = MaterialTheme.typography.titleMedium)
    val aboutContext = LocalContext.current
    Spacer(Modifier.height(8.dp))
    Card(shape = smoothShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(s.format("about.tech.body", "api" to "${aboutContext.applicationInfo.minSdkVersion}",
            "db" to "${com.zsz.studyassistant.data.AppDatabase.SCHEMA_VERSION}",
            "bak" to "${com.zsz.studyassistant.data.BackupArchive.VERSION}"),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
    Spacer(Modifier.height(20.dp))
    Text(s["about.features"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Card(shape = smoothShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            // ★ 2026-10-14：这一页是纯 Text 渲染，文案里的 `**加粗**` 必须过一遍 changelogAnnotated，
            //   否则会把星号原样显示出来（同一个坑在「更新内容」页踩过）。
            changelogAnnotated(s["about.features.body"]),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
    // ★ 2026-10-14：新增「数据与隐私」小节 —— 与 README 顶部那两句保持一致
    //   （用户要求：README 的内容更新同步到这里；数据只存本机是本项目对外最重要的一条）
    Spacer(Modifier.height(20.dp))
    Text(s["about.privacy"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Card(shape = smoothShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            changelogAnnotated(s["about.privacy.body"]),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/**
 * 更新内容里的 `**加粗**` 标记 → 真正的粗体。
 * 这一页是纯 Text 渲染（不像答案走 WebView 的 Markdown），不处理就会把星号直接显示出来。
 */
internal fun changelogAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val start = text.indexOf("**", i)
        if (start < 0) { append(text.substring(i)); return@buildAnnotatedString }
        val end = text.indexOf("**", start + 2)
        if (end < 0) { append(text.substring(i)); return@buildAnnotatedString }
        append(text.substring(i, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text.substring(start + 2, end))
        }
        i = end + 2
    }
}

/**
 * 外层壳子（`MainActivity`）底部 `NavigationBar` 的高度：Material3 默认 **80dp**。
 * 二级页是在它的**上方**渲染的，所以按"键盘高度"做 IME 避让时必须先扣掉它，
 * 否则就会多缩一截 → 键盘上方出现空白带（用户 2026-10-13 反馈的那个现象）。
 */
private val SHELL_BOTTOM_BAR_HEIGHT = 80.dp

/**
 * 二级页面容器：返回键 + 标题 + 可滚动内容（+ 可选底部操作栏）。
 *
 * ★ 2026-10-13（用户要求）：新增 [bottomBar] 槽位。容器已有 `imePadding()`，所以**键盘弹出时底部栏
 *   会自动贴在键盘上方** —— 「自定义要求」的保存按钮放进去后，不用先收起键盘就能点到。
 */
@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    // ★ 2026-10-13（用户要求）：头部行**不再吃外层 16dp 内边距**（原来箭头因此又小又靠右），
    //   并统一改用 SubPageBackArrow（21sp 的「←」）→ 与解题页/错题本等子页面完全一致。
    // ★ 2026-10-13 修复（用户报「自定义 prompt 输入到底部时被键盘挡住」）：
    //   二级页容器补上 IME 避让 —— 键盘弹出时内容收窄，不再被输入法遮住。
    //   （Manifest 是 adjustResize，且应用是 edge-to-edge，所以必须自己消费 IME inset。）
    // ★★ 2026-10-13 二修（用户报「键盘和输入框之间有一大块空白」）：
    //   本页在外层 `Column { Box(weight 1f){ NavHost }; NavigationBar }` 的 **Box 里** ——
    //   也就是说页面底边**已经**比屏幕底边高了「底部导航栏(80dp) + 系统导航条 inset」那么多。
    //   若仍按**整个键盘高度**做 imePadding，就会**多缩**这么一截 → 键盘上方出现一条空白带。
    //   这里改成只补「键盘高度 − 那一截」，内容底边正好落在键盘顶边上。
    val density = LocalDensity.current
    val imePx = WindowInsets.ime.getBottom(density)
    val shellBottomPx = with(density) { SHELL_BOTTOM_BAR_HEIGHT.toPx() } +
        WindowInsets.navigationBars.getBottom(density)
    val imeExtraBottom = with(density) { (imePx - shellBottomPx).coerceAtLeast(0f).toDp() }
    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .padding(bottom = if (imePx > 0) imeExtraBottom else 0.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubPageBackArrow(onClick = onBack)
            // 二级页标题：**与解题页顶栏标题完全同款** —— 2026-10-18 统一了字号（19sp），
            // 2026-10-20 又发现"看起来还是更大"：原来这里用 `titleLarge.copy(fontSize=…)`，
            // titleLarge 自带更大的行高与字重（Medium/SemiBold）→ 同字号也显大。
            // 解题页用的是**默认文本样式 + 覆盖 fontSize**，这里改成同一写法，字号/字重/行高全部一致。
            Text(title, fontSize = SUBPAGE_TITLE, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        // weight(1f)：把剩余高度都给内容区，底部操作栏因此始终贴在容器底部（键盘弹出时=键盘上方）
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
        ) { content() }
        if (bottomBar != null) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterEnd
                ) { bottomBar() }
            }
        }
    }
}

/** 设置主页面：入口列表 */
@Composable
private fun SettingsMain(onOpen: (String) -> Unit) {
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text(s["settings.title"], style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        BackupHealthEntry(onOpen)

        // ── 通用 ──
        SettingGroupTitle(s["settings.group.general"])
        // API 管理已并入「AI 管理」二级菜单（放在该页最顶端）
        SettingEntry(s["settings.aiManage"]) { onOpen(AppNav.SettingsPages.AI) }
        // 通知与后台：已合并为一个二级菜单，归入「通用」大类
        SettingEntry(s["settings.group.notifyBackground"]) { onOpen(AppNav.SettingsPages.NOTIFY) }

        Spacer(Modifier.height(14.dp))
        // ── 个性化 ──
        SettingGroupTitle(s["settings.group.personalize"])
        SettingEntry(s["settings.theme"]) { onOpen(AppNav.SettingsPages.THEME) }
        SettingEntry(s["settings.lang"]) { onOpen(AppNav.SettingsPages.LANG) }

        Spacer(Modifier.height(26.dp))
        // ── 数据管理 / 应用更新 / 关于（分立，不归入大类；上方多留一行） ──
        SettingEntry(s["settings.data"]) { onOpen(AppNav.SettingsPages.DATA) }
        // 有可用更新时显示红色气泡「1」（启动时静默检查写入 UpdateBadgeState）
        SettingEntry(s["settings.update"], badge = UpdateBadgeState.availableVersion != null) { onOpen(AppNav.SettingsPages.UPDATE) }
        SettingEntry(s["settings.about"]) { onOpen(AppNav.SettingsPages.ABOUT) }
    }
}

/** Surface failed automatic backups on settings home, without a startup dialog. */
@Composable
private fun BackupHealthEntry(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var failure by remember { mutableStateOf(AutoBackup.lastFailure(context)) }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("auto_backup_") == true) failure = AutoBackup.lastFailure(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    failure?.takeIf { AutoBackup.treeUri(context) != null }?.let {
        Text(s.format("data.autoBackup.healthWarning", "t" to formatCheckTime(it.at), "reason" to s[it.key]),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { onOpen(AppNav.SettingsPages.DATA_BACKUP) }) { Text(s["data.backup"]) }
        Spacer(Modifier.height(8.dp))
    }
}

/** 分组标题（小号主色文字） */
@Composable
private fun SettingGroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
    )
}

/**
 * ✂️ **AI 切图总开关**（用户 2026-09-17 提出，2026-10-07 实现）。
 * 开：进框选页时本地算法**与 AI 视觉并行**，AI 先回来就用 AI 的框（见 `ui/CropScreen.kt`）。
 * 关：**完全不跑 AI**，只用本地投影算法框选，进度快也更省额度；下面的「AI 框选时限」也随之隐藏。
 */
@Composable
private fun AiCropSwitchSettings(checked: Boolean, onChecked: (Boolean) -> Unit) {
    val s = LocalStrings.current
    Row(
        // 整行可点（点文字也能切换）——Switch 自己的命中区域只有它那一小块
        Modifier.fillMaxWidth().clickable { onChecked(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(s["settings.aiCrop.enable"], style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                s["settings.aiCrop.enable.desc"],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked
        )
    }
}

/**
 * ✂️ AI 框选时限：100ms ~ 5s 的**对数**滑条，500ms / 1s / 3s 三个磁吸点。
 * 含义：进框选页后等 AI 自动框选的上限；超时就用本地算法框选。
 * 只在「AI 切图」开着时显示（关掉 AI 就没人用这个时限了）。
 */
@Composable
private fun AiCropTimeoutSettings() {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    var ms by remember {
        mutableFloatStateOf(com.zsz.studyassistant.data.CapturePrefs.aiCropTimeoutMs(ctx).toFloat())
    }

    val minMs = 100f
    val maxMs = 5000f
    val ratio = maxMs / minMs
    fun tToMs(t: Float): Float = minMs * ratio.pow(t)
    fun msToT(m: Float): Float =
        (ln((m / minMs).coerceAtLeast(1.0001f)) / ln(ratio)).coerceIn(0f, 1f)

    fun fmt(v: Float): String =
        if (v < 1000f) "${v.roundToInt()} ms" else String.format("%.2f s", v / 1000f)

    val snapPoints = listOf(100f, 500f, 1000f, 3000f, 5000f)

    Column(Modifier.fillMaxWidth()) {
        Text(s["settings.aiCrop.timeout"], style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            s["settings.aiCrop.desc"],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(16.dp))
        Text(
            fmt(ms),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        // 自绘滑条：轨道 / 滑块 / 刻度文字都在同一个 Canvas 坐标系里画，
        // 这样刻度一定严格对准它代表的位置（不再受 Material Slider 内部内边距影响）
        AiCropSlider(
            ms = ms,
            onMs = { v ->
                var m = v
                snapPoints.forEach { sp -> if (abs(m - sp) / sp < 0.08f) m = sp }
                ms = m
            },
            onCommit = {
                com.zsz.studyassistant.data.CapturePrefs.setAiCropTimeoutMs(ctx, ms.toLong())
            },
            fmt = ::fmt,
            msToT = ::msToT,
            tToMs = ::tToMs
        )
    }
}

/** ---- 解题模式（快 / 深度思考）：只是 thinking 开关，模型没变 ---- */
@Composable
private fun AiSolveModelSettings() {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    Spacer(Modifier.height(22.dp))
    Text(s["settings.solveModel"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(s["settings.solveModel.desc"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(8.dp))
    var model by remember { mutableStateOf(com.zsz.studyassistant.data.CapturePrefs.solveModel(ctx)) }
    listOf("flash" to s["settings.solveModel.flash"], "reasoner" to s["settings.solveModel.reasoner"]).forEach { (v, label) ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = model == v, onClick = {
                model = v
                com.zsz.studyassistant.data.CapturePrefs.setSolveModel(ctx, v)
            }).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = model == v, onClick = {
                model = v
                com.zsz.studyassistant.data.CapturePrefs.setSolveModel(ctx, v)
            })
            Text(label, Modifier.padding(start = 8.dp))
        }
    }
}

/** ---- 自定义要求（Prompt）：会附加到每次 AI 请求 ---- */
@Composable
private fun AiCustomPromptSettings(text: String, onText: (String) -> Unit) {
    val s = LocalStrings.current
    Text(s["settings.customPrompt.desc"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        // ★ 2026-10-13 修复「输入到底部被键盘挡住」：
        //   给高度上限 → 内容超出时**输入框内部滚动**，光标始终留在可见区（原来只有 minLines=6、
        //   字一多输入框就无限长高，光标会被顶到键盘下面）；配合 SettingsSubPage 的 imePadding() 整页避让。
        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 260.dp),
        minLines = 6,
        maxLines = 14,
    )
    // 保存按钮已**移到二级页的底部操作栏**（`SettingsSubPage(bottomBar = …)`）——
    // 键盘弹出时它贴在键盘上方，不用先收键盘就能点（用户 2026-10-13 要求）。
    // 这里留一点底部间距，免得输入框贴着底栏。
    Spacer(Modifier.height(12.dp))
}

/** 自绘的对数滑条（含刻度标注，严格对齐） */
@Composable
private fun AiCropSlider(
    ms: Float,
    onMs: (Float) -> Unit,
    onCommit: () -> Unit,
    fmt: (Float) -> String,
    msToT: (Float) -> Float,
    tToMs: (Float) -> Float
) {
    val tickVals = listOf(100f, 500f, 1000f, 3000f, 5000f)
    // 刻度用紧凑写法：整秒不带小数（3.00 s → 3 s），否则末尾两个标签会挤在一起
    fun fmtTick(v: Float): String =
        if (v < 1000f) "${v.roundToInt()} ms"
        else {
            val sec = v / 1000f
            if (sec % 1f == 0f) "${sec.roundToInt()} s" else String.format("%.1f s", sec)
        }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.outline
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    var widthPx by remember { mutableFloatStateOf(1f) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { onCommit() }
                ) { change, _ ->
                    val t = (change.position.x / widthPx).coerceIn(0f, 1f)
                    onMs(tToMs(t))
                    change.consume()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val t = (pos.x / widthPx).coerceIn(0f, 1f)
                    onMs(tToMs(t))
                    onCommit()
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            widthPx = w
            val trackY = 18.dp.toPx()
            val trackH = 8.dp.toPx()
            val radius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f)
            // 轨道
            drawRoundRect(
                color = inactive,
                topLeft = Offset(0f, trackY - trackH / 2f),
                size = Size(w, trackH),
                cornerRadius = radius
            )
            val t = msToT(ms).coerceIn(0f, 1f)
            // 已选部分
            drawRoundRect(
                color = active,
                topLeft = Offset(0f, trackY - trackH / 2f),
                size = Size((w * t).coerceAtLeast(trackH), trackH),
                cornerRadius = radius
            )
            // 滑块（细竖条，中心正好在 w*t）
            val thumbW = 4.dp.toPx()
            val thumbH = 26.dp.toPx()
            drawRoundRect(
                color = active,
                topLeft = Offset(w * t - thumbW / 2f, trackY - thumbH / 2f),
                size = Size(thumbW, thumbH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbW / 2f)
            )
            // 刻度文字：中心严格对齐各自的对数位置（两端做边缘夹紧）
            tickVals.forEach { v ->
                val layout = measurer.measure(
                    text = androidx.compose.ui.text.AnnotatedString(fmtTick(v)),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 11.sp,
                        color = labelColor
                    )
                )
                val cx = (w * msToT(v)).coerceIn(
                    layout.size.width / 2f,
                    (w - layout.size.width / 2f).coerceAtLeast(layout.size.width / 2f)
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(cx - layout.size.width / 2f, trackY + 16.dp.toPx())
                )
            }
        }
    }
}

/**
 * 刻度标注：按**对数位置**把文字严格居中摆到对应的滑条位置上
 * （用 SpaceBetween 平均分布是不对的：对数刻度下 500ms/1s/3s 并不等距）
 */
@Composable
private fun SliderTicks(fmt: (Float) -> String, msToT: (Float) -> Float, values: List<Float>) {
    val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    Layout(
        content = {
            values.forEach { v ->
                Text(
                    fmt(v),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val full = constraints.maxWidth
        val usable = (full - insetPx * 2).coerceAtLeast(1f)
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(full, h) {
            placeables.forEachIndexed { i, p ->
                val t = msToT(values[i]).coerceIn(0f, 1f)
                val center = insetPx + usable * t
                val x = (center - p.width / 2f).roundToInt()
                    .coerceIn(0, (full - p.width).coerceAtLeast(0))
                p.placeRelative(x, 0)
            }
        }
    }
}

@Composable
private fun SettingEntry(title: String, badge: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Card(shape = smoothShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge) {
                    // 红色消息气泡「1」：有可用更新时显示（Canvas 精确居中，见 UpdateBadgeDot）
                    UpdateBadgeDot()
                    Spacer(Modifier.size(6.dp))
                }
                if (!danger) Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** 🔑 API 管理：上面是 Key 设置，下面是累计用量统计 */
@Composable
private fun ApiSettings(vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var input by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(ApiKeyStore.hasKey(context)) }

    if (hasKey) {
        Text(s["settings.api.configured"], style = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            s["settings.api.notConfigured"],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("API Key") },
        placeholder = { Text("sk-...") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true
    )
    Spacer(Modifier.height(6.dp))
    Text(s["api.key.note"], style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
    Spacer(Modifier.height(8.dp))
    TextButton(shape = smoothPill(), 
        onClick = {
            if (input.isNotBlank()) {
                ApiKeyStore.saveKey(context, input.trim())
                hasKey = true; input = ""
            }
        },
        enabled = input.isNotBlank()
    ) { Text(s["common.save"]) }

    Spacer(Modifier.height(24.dp))
    LaunchedEffect(Unit) { vm.refreshUsage() }
    Text(s["api.usage"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    if (vm.usageCalls == 0) {
        Text(s["api.usage.empty"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    } else {
        Text(s.format("api.usage.calls", "n" to "${vm.usageCalls}"), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            s.format("api.usage.tokens", "i" to "${vm.usagePromptTokens}", "o" to "${vm.usageCompletionTokens}"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        TextButton(shape = smoothPill(), onClick = { vm.resetUsage() }) { Text(s["api.usage.reset"]) }
    }
}

/** 💾 数据管理入口：备份管理（含自动备份）/ 崩溃日志 / 数据升级·修复 / 清空所有数据 */
@Composable
private fun DataMenu(vm: MainViewModel, go: (String) -> Unit) {
    val s = LocalStrings.current
    val feedback = LocalAppFeedback.current
    var showClearConfirm by remember { mutableStateOf(false) }
    // 一级页只显示自己的结果（清空）；导出/导入、升级/修复的文案分别归各自子页（不再串页显示）
    LaunchedEffect(Unit) { vm.refreshDataSummary(); vm.clearMenuMessage() }
    val summary = vm.dataSummary
    Text(
        if (summary == null) s["data.summary"].replace("{q}", "…").replace("{c}", "…").replace("{t}", "…")
        else s.format("data.summary", "q" to "${summary.first}", "c" to "${summary.second}", "t" to "${summary.third}"),
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(8.dp))
    // ⚠️ 标题文案本身已带 emoji（「🗂 备份管理」「🛠 数据升级 / 修复」），这里**不要再传 emoji**，否则会画两个
    if (vm.pendingSaveCount > 0 || vm.saveJournalIssue) {
        SettingEntry(s["feedback.unsaved"], danger = true) {
            feedback.saveRecoveryRequested = true
        }
    }
    if (vm.draftSummaries.isNotEmpty() || vm.draftIssue) {
        SettingEntry(s["draft.title"]) {
            feedback.draftOrigin=AppNav.Origin.SETTINGS
            feedback.draftRecoveryRequested=true
        }
    }
    SettingEntry(s["data.backup"]) { go(AppNav.SettingsPages.DATA_BACKUP) }
    SettingEntry(s["data.crash"]) { go(AppNav.SettingsPages.DATA_CRASH) }
    SettingEntry(s["data.upgradePage"]) { go(AppNav.SettingsPages.DATA_UPGRADE) }
    SettingEntry(s["tagMerge.title"]) { go(AppNav.SettingsPages.DATA_TAG_MERGE) }

    // ★ 2026-10-17（用户要求）：清空所有数据**从「备份管理」挪到这一级菜单**上
    Spacer(Modifier.height(10.dp))
    SettingEntry("🗑 " + s["data.clear"], danger = true) { showClearConfirm = true }

    FeedbackMessage(vm.menuMessage, vm.menuMessageIsError) { vm.clearMenuMessage() }

    if (showClearConfirm) {
        val n = summary?.first ?: 0
        val phrase = s["data.clear.phrase"]
        var typed by remember { mutableStateOf("") }
        val matched = typed.trim() == phrase
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(s["data.clear.title"]) },
            text = {
                Column {
                    Text(s.format("data.clear.text", "n" to "$n"))
                    Spacer(Modifier.height(12.dp))
                    Text(s["data.clear.inputLabel"], style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(phrase, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(phrase, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            },
            confirmButton = {
                // 只有输入完全一致才可点，且用红色按钮
                TextButton(shape = smoothPill(),
                    onClick = {
                        typed = ""
                        vm.clearAllData()
                        showClearConfirm = false
                        vm.refreshDataSummary()
                    },
                    enabled = matched,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.outline
                    )
                ) { Text(s["common.ok"]) }
            },
            dismissButton = { TextButton(shape = smoothPill(), onClick = { showClearConfirm = false }) { Text(s["common.cancel"]) } }
        )
    }
}


/**
 * 🛠 数据升级 / 修复；展示应用支持版本与实际数据库结构版本。
 * 两个按钮都**幂等、可重复执行**，只做加法（补图/整理），**绝不删已有数据**。
 */
@Composable
private fun DataUpgradePage(vm: MainViewModel) {
    val s = LocalStrings.current
    // 本页只显示升级 / 修复自己的结果（与备份页的导出/导入文案分开）
    LaunchedEffect(Unit) { vm.clearUpgradeMessage(); vm.refreshDatabaseVersion() }
    Text(s["data.upgradeDesc"], style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { vm.upgradeData() },
        enabled = !vm.dataBusy,
        modifier = Modifier.fillMaxWidth()
    ) { Text(s["data.upgradeBtn"], fontSize = BTN_LABEL) }

    Spacer(Modifier.height(22.dp))
    Text(s["data.repairDesc"], style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { vm.repairData() },
        enabled = !vm.dataBusy,
        modifier = Modifier.fillMaxWidth()
    ) { Text(s["data.repairBtn"], fontSize = BTN_LABEL) }

    FeedbackMessage(vm.upgradeMessage, vm.upgradeMessageIsError) { vm.clearUpgradeMessage() }
    Spacer(Modifier.height(24.dp))
    Text(
        s.format("data.supportedVersions",
            "db" to "${com.zsz.studyassistant.data.AppDatabase.SCHEMA_VERSION}",
            "bak" to "${com.zsz.studyassistant.data.BackupArchive.VERSION}"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Text(
        when {
            vm.databaseVersionReadFailed -> s["data.databaseVersionFailed"]
            vm.currentDatabaseVersion == null -> s["data.databaseVersionLoading"]
            else -> s.format("data.actualDatabaseVersion", "db" to "${vm.currentDatabaseVersion}")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    if (vm.hasLegacyData) {
        Text(
            s["data.legacyDataPresent"],
            style = MaterialTheme.typography.bodySmall,
            color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFFFC107) else Color(0xFF9C6500)
        )
    }
}

/** 💾 Backup management contains both manual export/import and automatic backup settings. */
@Composable
private fun DataSettings(vm: MainViewModel, section: String = "backup") {
    if (section == "crash") {
        CrashLogSettings(vm)
    } else {
        val s = LocalStrings.current
        Text(s["data.manualBackup"], style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        BackupSettings(vm)
        androidx.compose.material3.HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text(s["data.autoBackup"], style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        AutoBackupSettings(vm)
    }
}

/** Backup management uses a private, validated tree before any picker or database write. */
@Composable
private fun BackupSettings(vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var showParts by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshDataSummary(); vm.clearDataMessage() }
    // ViewModel owns prepared files across system pickers and page/activity recreation.
    val summary = vm.dataSummary
    Text(
        if (summary == null) s["data.summary"].replace("{q}", "…").replace("{c}", "…").replace("{t}", "…")
        else s.format("data.summary", "q" to "${summary.first}", "c" to "${summary.second}", "t" to "${summary.third}"),
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(16.dp))
    val exportLauncher = rememberLauncherForActivityResult(remember { BackupExportDocument() }) { uri ->
        if (uri == null) vm.discardPreparedExport() else vm.writePreparedBackup(uri)
    }
    val exportTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) vm.discardPreparedExport() else vm.writePreparedBackup(uri, tree = true)
    }
    val idle = !vm.dataBusy && vm.preparedBackup == null
    fun savePrepared(set: com.zsz.studyassistant.data.BackupArchive.ArchiveSet) {
        showParts = set.files.size != 1
        if (set.files.size == 1) exportLauncher.launch(com.zsz.studyassistant.data.BackupNames.fileName(set.snapshotAt, automatic = false))
    }
    Button(shape = smoothPill(), onClick = {
        val ready = vm.preparedExport
        if (ready != null) savePrepared(ready) else vm.prepareBackupExport { savePrepared(it) }
    }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
        Text(s[if (vm.preparedExport == null) "data.export" else "data.backupContinue"])
    }
    if (vm.preparedExport != null && !vm.dataBusy) {
        TextButton(shape = smoothPill(), onClick = { showParts = false; vm.discardPreparedExport() }) {
            Text(s["data.backupCancel"])
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(s["data.export.desc"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.prepareBackupImport(uris)
    }
    OutlinedButton(shape = smoothPill(), onClick = {
        importLauncher.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "*/*"))
    }, enabled = idle && vm.preparedExport == null, modifier = Modifier.fillMaxWidth()) { Text(s["data.import"]) }
    Spacer(Modifier.height(4.dp))
    Text(s["data.import.desc"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))
    if (vm.dataBusy) {
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(s[vm.dataProgressKey ?: "data.exporting"], style = MaterialTheme.typography.bodySmall)
        TextButton(shape = smoothPill(), onClick = { vm.cancelBackupOperation() }) { Text(s["data.backupCancel"]) }
    }
    FeedbackMessage(vm.dataMessage, vm.dataMessageIsError) { vm.clearDataMessage() }
    if (showParts && vm.preparedExport != null) {
        AlertDialog(
            onDismissRequest = { showParts = false; vm.discardPreparedExport() },
            title = { Text(s["data.export"]) },
            text = { Text(s.format("data.backupParts", "n" to "${vm.preparedExport!!.files.size}")) },
            confirmButton = { TextButton(shape = smoothPill(), onClick = {
                showParts = false; exportTreeLauncher.launch(AutoBackup.treeUri(context))
            }) { Text(s["common.ok"]) } },
            dismissButton = { TextButton(shape = smoothPill(), onClick = {
                showParts = false; vm.discardPreparedExport()
            }) { Text(s["common.cancel"]) } }
        )
    }
    vm.preparedBackup?.let { prepared ->
        AlertDialog(
            onDismissRequest = { if (!vm.dataBusy) vm.discardPreparedBackup() },
            title = { Text(s["data.import"]) },
            text = { Text(s.format("data.backupPreview", "q" to "${prepared.stats.questions}",
                "c" to "${prepared.stats.categories}", "t" to "${prepared.stats.tags}")) },
            confirmButton = { TextButton(shape = smoothPill(), onClick = { vm.importBackup() }, enabled = !vm.dataBusy) { Text(s["common.ok"]) } },
            dismissButton = { TextButton(shape = smoothPill(), onClick = { vm.discardPreparedBackup() }, enabled = !vm.dataBusy) { Text(s["common.cancel"]) } }
        )
    }
}

/** One-time work is queued by an app-open event or an explicit user request. */
@Composable
private fun AutoBackupSettings(vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val feedback = LocalAppFeedback.current
    var autoFolder by remember { mutableStateOf(AutoBackup.treeUri(context)?.lastPathSegment) }
    var showBackupInfo by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var autoEnabled by remember { mutableStateOf(AutoBackup.isEnabled(context)) }
    var folderSummary by remember { mutableStateOf<com.zsz.studyassistant.data.BackupRetention.FolderSummary?>(null) }
    var folderHealth by remember { mutableStateOf<com.zsz.studyassistant.data.BackupFolderAccess.State?>(null) }
    val folderUsable = folderHealth == com.zsz.studyassistant.data.BackupFolderAccess.State.AVAILABLE
    var folderReadFailed by remember { mutableStateOf(false) }
    var autoLast by remember { mutableStateOf(AutoBackup.lastBackupAt(context)) }
    var autoFailure by remember { mutableStateOf(AutoBackup.lastFailure(context)) }
    var status by remember { mutableStateOf(com.zsz.studyassistant.data.BackupJobs.status(context)) }
    var refresh by remember { mutableStateOf(0) }
    val folderLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(folderLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++
        }
        folderLifecycle.addObserver(observer)
        onDispose { folderLifecycle.removeObserver(observer) }
    }
    var requesting by remember { mutableStateOf(false) }
    var canNotify by remember { mutableStateOf(com.zsz.studyassistant.data.BackupNotifications.canNotify(context)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canNotify = com.zsz.studyassistant.data.BackupNotifications.canNotify(context)
    }
    val backupLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(backupLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                canNotify = com.zsz.studyassistant.data.BackupNotifications.canNotify(context)
                refresh++
            }
        }
        backupLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { backupLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun requestFailureNotifications() {
        com.zsz.studyassistant.data.BackupNotifications.ensureChannel(context)
        val preferences = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val needsRuntimePermission = com.zsz.studyassistant.data.BackupNotifications.needsPermission(context)
        val activity = context as? android.app.Activity
        val mayRequestAgain = !preferences.getBoolean("backup_failure_permission_asked", false) ||
            (activity != null && androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity,
                android.Manifest.permission.POST_NOTIFICATIONS))
        if (needsRuntimePermission && mayRequestAgain) {
            preferences.edit().putBoolean("backup_failure_permission_asked", true).apply()
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val action = if (needsRuntimePermission || !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS else android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            context.startActivity(Intent(action)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, com.zsz.studyassistant.data.BackupNotifications.CHANNEL_ID))
        }
    }
    val autoWriting = requesting || !status.canRequestNow
    val internalStorage = s["data.autoBackup.internalStorage"]
    LaunchedEffect(context, refresh, vm.dataBusy, vm.preparedExport == null, internalStorage) {
        if (vm.dataBusy || vm.preparedExport != null) return@LaunchedEffect
        val tree = AutoBackup.treeUri(context)
        val operation = kotlinx.coroutines.currentCoroutineContext()
        val documentId = tree?.let { runCatching { android.provider.DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
        // The displayed path follows the actual selection even when the inventory is unreadable.
        autoFolder = backupFolderLabel(documentId, null, internalStorage)
        folderHealth = null
        val observed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.zsz.studyassistant.data.BackupFolderAccess.refresh(context, scheduleIfAvailable = true)
        }
        if (observed == null || !com.zsz.studyassistant.data.BackupFolderAccess.isCurrent(context, observed)) {
            refresh++
            return@LaunchedEffect
        }
        folderHealth = observed.health.state
        autoEnabled = AutoBackup.isEnabled(context)
        folderSummary = null
        autoLast = 0L
        folderReadFailed = false
        try {
            val label = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                backupFolderLabel(documentId, AutoBackup.folderName(context), internalStorage)
            }
            if (AutoBackup.treeUri(context) == tree) autoFolder = label
            val summary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (tree == null) com.zsz.studyassistant.data.BackupRetention.FolderSummary()
                else com.zsz.studyassistant.data.BackupRetention.folderSummary(context, tree) { operation.ensureActive() }
            }
            if (AutoBackup.treeUri(context) == tree) {
                folderSummary = summary
                autoLast = summary.latestAt; folderReadFailed = false
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) {
            if (AutoBackup.treeUri(context) == tree) { folderSummary = null; folderReadFailed = true }
        }
    }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("auto_backup_") == true || key?.startsWith("backup_job_") == true) {
                scope.launch {
                    autoFailure = AutoBackup.lastFailure(context)
                    autoEnabled = AutoBackup.isEnabled(context)
                    status = com.zsz.studyassistant.data.BackupJobs.status(context)
                    if (key == "auto_backup_tree" || key == "auto_backup_last_at" ||
                        (key == "backup_job_phase" && status.phaseKey == "data.backupJob.success")) refresh++
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun backupNow() {
        if (autoWriting || vm.dataBusy || vm.preparedBackup != null || vm.preparedExport != null) return
        requesting = true
        scope.launch {
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.zsz.studyassistant.data.BackupJobs.requestNow(context) } }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { feedback.show(s["data.autoBackup.storageFailed"], isError = true, source = "backup") }
            finally { requesting = false }
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (!com.zsz.studyassistant.data.BackupFolderAccess.select(context, uri))
                            throw java.io.IOException("Backup directory is unavailable")
                    }
                    refresh++
                    if (AutoBackup.isEnabled(context) && com.zsz.studyassistant.data.BackupNotifications.needsPermission(context) &&
                        !context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean("backup_failure_permission_asked", false)) {
                        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
                            .putBoolean("backup_failure_permission_asked", true).apply()
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { feedback.show(s["data.autoBackup.storageFailed"], isError = true, source = "backup") }
            }
        }
    }
    if (autoFolder == null) Text(s["data.autoBackup.noFolder"],
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    else BackupFolderPath(autoFolder!!, s["data.autoBackup.pathLabel"]) {
        if (!openBackupFolder(context, AutoBackup.treeUri(context))) {
            feedback.show(s["data.autoBackup.openFolderFailed"], isError = true)
        }
    }
    if (folderHealth != null && !folderUsable) {
        Text(s[if (folderHealth == com.zsz.studyassistant.data.BackupFolderAccess.State.TEMPORARILY_UNAVAILABLE)
            "data.autoBackup.temporarilyUnavailable" else "data.autoBackup.invalidFolder"],
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(6.dp))
    Text((if (folderReadFailed) s["data.autoBackup.countFailed"] else s.format("data.autoBackup.count",
        "manual" to (folderSummary?.manual?.toString() ?: "…"),
        "automatic" to (folderSummary?.automatic?.toString() ?: "…"))) + if (folderReadFailed) "" else "\n" +
        if (autoLast > 0L) s.format("data.autoBackup.last", "t" to formatCheckTime(autoLast)) else s["data.autoBackup.never"],
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    if (status.phaseKey.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(s[status.phaseKey] + if (status.active && status.progressBytes > 0) " · " + android.text.format.Formatter.formatShortFileSize(context, status.progressBytes) else "",
            style = MaterialTheme.typography.bodySmall, color = if (status.errorKey == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error)
    }
    status.errorKey?.let { key ->
        Text(s[key], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    autoFailure?.takeIf { AutoBackup.treeUri(context) != null && status.errorKey == null }?.let { failure ->
        Spacer(Modifier.height(6.dp))
        Text(s.format("data.autoBackup.healthWarning", "t" to formatCheckTime(failure.at), "reason" to s[failure.key]),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (status.active) {
        TextButton(shape = smoothPill(), onClick = { scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.zsz.studyassistant.data.BackupJobs.cancel(context) }
        } }) { Text(s["data.backupCancel"]) }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(shape = smoothPill(), onClick = { folderLauncher.launch(null) },
        enabled = !requesting && !vm.dataBusy && vm.preparedBackup == null && vm.preparedExport == null,
        modifier = Modifier.fillMaxWidth().height(44.dp)) {
        Text(if (autoFolder == null) s["data.autoBackup.pick"] else s["data.autoBackup.change"])
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(s["data.autoBackup.daily"], modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = autoEnabled && folderHealth != com.zsz.studyassistant.data.BackupFolderAccess.State.INVALID, enabled = folderUsable, onCheckedChange = { on ->
            autoEnabled = on
            scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AutoBackup.setEnabled(context, on) } }
        })
    }
    Spacer(Modifier.height(8.dp))
    if (!canNotify && autoEnabled && autoFolder != null) {
        Text(s["data.backupNotifyBlocked"], style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { requestFailureNotifications() }) { Text(s["data.backupNotifyEnable"]) }
    }
    Button(shape = smoothPill(), enabled = folderUsable && !vm.dataBusy && !autoWriting && vm.preparedBackup == null && vm.preparedExport == null,
        onClick = { backupNow() }, modifier = Modifier.fillMaxWidth()) {
        Text(s[if (status.errorKey != null && !status.active) "data.backupRetry" else "data.autoBackup.now"])
    }
    Spacer(Modifier.height(4.dp))
    TextButton(shape = smoothPill(), onClick = { showBackupInfo = !showBackupInfo },
        modifier = Modifier.fillMaxWidth().then(Modifier.semantics {
            stateDescription = s[if (showBackupInfo) "data.autoBackup.expanded" else "data.autoBackup.collapsed"]
        })) { Text(s["data.autoBackup.info"], style = MaterialTheme.typography.bodySmall) }
    if (showBackupInfo) {
        Text(s.format("data.autoBackup.desc", "n" to "${AutoBackup.KEEP}"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    Spacer(Modifier.height(16.dp))
}
/** 🐞 崩溃日志子页：导出 / 清空（本机记录，不上传） */
@Composable
private fun CrashLogSettings(vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val feedback = LocalAppFeedback.current

    // ── 崩溃日志：崩溃时自动记到本机（data/CrashLogger.kt），这里只做「导出 / 清空」 ──
    // 设计取舍：不做自动上传（隐私 + 自用无服务端），导出走系统「保存到文件」，用户自己决定发给谁。
    var crashCount by remember { mutableStateOf(CrashLogger.count(context)) }
    var showCrashClearConfirm by remember { mutableStateOf(false) }

    Text(s["data.crashLogDesc"], style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(10.dp))
    // 子页标题由 SettingsSubPage 的顶栏提供，页面内不再重复画一遍「🐞 崩溃日志」
    Text(
        if (crashCount == 0) s["data.crash.none"] else s.format("data.crash.count", "n" to "$crashCount"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Spacer(Modifier.height(8.dp))

    val crashExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                val text = CrashLogger.dumpText(context)
                checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(text.toByteArray()) }
                feedback.show(s["data.crash.exportDone"])
            } catch (_: Exception) {
                feedback.show(s.format("data.crash.failed", "msg" to s["feedback.writeFailed"]), isError = true)
            }
        }
    }
    Button(
        shape = smoothPill(),
        enabled = crashCount > 0,
        onClick = { crashExportLauncher.launch(CrashLogger.exportFileName()) },
        modifier = Modifier.fillMaxWidth()
    ) { Text(s["data.crash.export"]) }
    Spacer(Modifier.height(4.dp))
    Text(s["data.crash.export.desc"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

    Spacer(Modifier.height(12.dp))

    OutlinedButton(
        shape = smoothPill(),
        enabled = crashCount > 0,
        onClick = { showCrashClearConfirm = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) { Text(s["data.crash.clear"]) }


    if (showCrashClearConfirm) {
        AlertDialog(
            onDismissRequest = { showCrashClearConfirm = false },
            title = { Text(s["data.crash.clear.title"]) },
            text = { Text(s["data.crash.clear.text"]) },
            confirmButton = {
                TextButton(shape = smoothPill(), onClick = {
                    CrashLogger.clear(context)
                    crashCount = 0
                    feedback.show(s["data.crash.clearDone"])
                    showCrashClearConfirm = false
                }) { Text(s["common.ok"]) }
            },
            dismissButton = { TextButton(shape = smoothPill(), onClick = { showCrashClearConfirm = false }) { Text(s["common.cancel"]) } }
        )
    }
}

/** 🌐 语言设置：应用语言（界面文案） + AI 生成语言（回答语言） */
@Composable
private fun LanguageSettings(vm: MainViewModel) {
    val s = LocalStrings.current

    Text(s["lang.ui"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(s["lang.ui.desc"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(8.dp))
    UiLang.values().forEach { lang ->
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = vm.uiLang == lang, onClick = { vm.updateUiLang(lang) })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = vm.uiLang == lang, onClick = { vm.updateUiLang(lang) })
            Text(lang.displayLabel(s), Modifier.padding(start = 8.dp))
        }
    }

    Spacer(Modifier.height(20.dp))

    Text(s["lang.ai"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(s["lang.ai.desc"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(8.dp))
    AiLang.values().forEach { lang ->
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = vm.aiLang == lang, onClick = { vm.updateAiLang(lang) })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = vm.aiLang == lang, onClick = { vm.updateAiLang(lang) })
            Text(aiLangLabel(lang, s), Modifier.padding(start = 8.dp))
        }
    }
}

/** AI 语言在设置页的显示名（跟随模式本地化，固定语言用自称） */
private fun aiLangLabel(lang: AiLang, s: Strings): String = when (lang) {
    AiLang.FOLLOW_SYSTEM -> "${s["lang.followSystem"]}（${systemLangLabel()}）"
    AiLang.FOLLOW_QUESTION -> s["lang.followQuestion"]
    else -> nativeName(lang.id)
}

/** 系统语言名（用该语言自己的写法；覆盖 de/fr/es/ru，因为 AI 语言含这几种） */
private fun systemLangLabel(): String {
    val l = java.util.Locale.getDefault()
    val id = when {
        l.language == "zh" && (l.country == "TW" || l.country == "HK" || l.country == "MO") -> "zh-TW"
        l.language == "zh" -> "zh-CN"
        l.language == "en" || l.language == "ja" || l.language == "ko" ||
            l.language == "de" || l.language == "fr" || l.language == "es" || l.language == "ru" -> l.language
        else -> return l.displayName.ifBlank { l.language }
    }
    return nativeName(id)
}

/** 🎨 应用主题 */
@Composable
private fun ThemeSettings(vm: MainViewModel, onOpen: (String) -> Unit) {
    val s = LocalStrings.current
    var showPicker by remember { mutableStateOf(false) }   // 自定义取色盘
    // ① 主题模式
    Text(s["settings.theme.mode"], style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    val themes = listOf(s["theme.option.system"] to "system", s["theme.option.light"] to "light", s["theme.option.dark"] to "dark")
    themes.forEach { (label, value) ->
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = vm.theme == value, onClick = { vm.updateTheme(value) })
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = vm.theme == value, onClick = { vm.updateTheme(value) })
            Text(label, Modifier.padding(start = 8.dp))
        }
    }
    // ② 主题配色（预设色板 + 自定义色，一键切换）
    Spacer(Modifier.height(14.dp))
    Text(s["settings.theme.color"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 预设色板（自定义色单独用一个圆点表示，取色后它会跟着变）
        val dots = AppThemeColor.presets + AppThemeColor.CUSTOM
        dots.forEach { c ->
            val selected = vm.themeColor == c
            val face = if (c == AppThemeColor.CUSTOM) Color(vm.customColor) else c.swatch
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(face)
                    .clickable { if (c == AppThemeColor.CUSTOM) showPicker = true else vm.updateThemeColor(c) }
                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                if (c == AppThemeColor.CUSTOM && !selected) {
                    // 自定义色未选中时，圆点里画一支小画笔提示"可自选"
                    Text("🎨", fontSize = 16.sp)
                } else if (selected) {
                    Text("✓", color = Color.White, fontSize = 20.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    // 自定义颜色按钮 → 打开取色盘
    SettingEntry(s["settings.theme.custom"]) { showPicker = true }

    // ③ 字号（C14）：只缩放 App 内文字（界面 + 对话区），不动系统设置
    Spacer(Modifier.height(18.dp))
    Text(s["settings.fontSize"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FontScale.menuOrder.forEach { f ->
            FilterChip(
                selected = vm.fontScale == f,
                onClick = { vm.updateFontScale(f) },
                label = { Text(s["settings.fontSize.${f.id}"]) }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(s["settings.fontSize.desc"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

    if (showPicker) {
        ThemeColorPickerDialog(
            initial = vm.customColor,
            onDismiss = { showPicker = false },
            onConfirm = { argb -> vm.updateCustomColor(argb); showPicker = false }
        )
    }
}

/** 取色盘：色相 / 饱和度 / 明度 三条渐变滑杆 + 实时预览（无需第三方库） */
@Composable
private fun ThemeColorPickerDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val s = LocalStrings.current
    val initHsv = remember(initial) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initial, hsv)
        hsv
    }
    var hue by remember { mutableStateOf(initHsv[0]) }
    var sat by remember { mutableStateOf(initHsv[1]) }
    var value by remember { mutableStateOf(initHsv[2].coerceAtLeast(0.35f)) }
    val argb = remember(hue, sat, value) { android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s["settings.theme.custom"]) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 预览
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(Color(argb))
                )
                Spacer(Modifier.height(12.dp))
                ChannelSlider(
                    label = s["settings.theme.hue"],
                    value = hue / 360f,
                    colors = listOf(
                        Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                    )
                ) { hue = it * 360f }
                ChannelSlider(
                    label = s["settings.theme.sat"],
                    value = sat,
                    colors = listOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0f, value))),
                        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, value))))
                ) { sat = it }
                ChannelSlider(
                    label = s["settings.theme.value"],
                    value = value,
                    colors = listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f))))
                ) { value = it.coerceIn(0.05f, 1f) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(argb) }) { Text(s["common.ok"]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s["common.cancel"]) } }
    )
}

/** 单条渐变滑杆（0~1） */
@Composable
private fun ChannelSlider(
    label: String,
    value: Float,
    colors: List<Color>,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 🔔 复习提醒 */
@Composable
private fun NotifyBackgroundSettings() {
    val context = LocalContext.current
    val s = LocalStrings.current
    val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var notifyEnabled by remember { mutableStateOf(prefs.getBoolean("notify_enabled", false)) }
    var notifyHour by remember { mutableStateOf(prefs.getInt("notify_hour", 20)) }
    var notifyMinute by remember { mutableStateOf(prefs.getInt("notify_minute", 0)) }
    var showTimePicker by remember { mutableStateOf(false) }
    val notifyPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun saveNotify(enabled: Boolean) {
        prefs.edit().putBoolean("notify_enabled", enabled).apply()
        notifyEnabled = enabled
        ReminderScheduler.applySchedule(context)
        if (enabled) {
            notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(s["notify.enable"])
        Switch(checked = notifyEnabled, onCheckedChange = { saveNotify(it) })
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(s["notify.time"])
        TextButton(shape = smoothPill(), onClick = { showTimePicker = true }) { Text("%02d:%02d".format(notifyHour, notifyMinute)) }
    }
    if (showTimePicker) {
        WheelTimePickerDialog(
            initialHour = notifyHour,
            initialMinute = notifyMinute,
            onConfirm = { h, m ->
                prefs.edit().putInt("notify_hour", h).putInt("notify_minute", m).apply()
                notifyHour = h; notifyMinute = m
                ReminderScheduler.applySchedule(context)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false }
        )
    }
    Text(s["notify.hint"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(20.dp))
    // ── 后台运行（与提醒强相关：后台权限不到位就收不到提醒） ──
    val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    val ignoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
    Text(s["background.allow"], style = MaterialTheme.typography.bodySmall)
    if (ignoringBattery) {
    Text(s["background.on"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    } else {
    TextButton(shape = smoothPill(), onClick = {
    try {
    context.startActivity(
    android.content.Intent(
    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    android.net.Uri.parse("package:${context.packageName}")
    )
    )
    } catch (_: Exception) { }
    }) { Text(s["background.goEnable"]) }
    }
    }
    Text(
    s["background.tip"],
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.outline
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
    Text(s["background.autostart"], style = MaterialTheme.typography.bodySmall)
    TextButton(shape = smoothPill(), onClick = {
    fun tryPkg(pkg: String): Boolean = try { context.startActivity(android.content.Intent(pkg)); true } catch (_: Exception) { false }
    var ok = false
    for (pkg in listOf(
    "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
    "com.honor.appmarket/.hms.startupmgr.ui.StartupNormalAppListActivity",
    "com.coloros.safecenter/.startupapp.StartupAppListActivity",
    "com.miui.securitycenter/.ui.AutoStartManagementActivity",
    "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
    "com.oplus.battery/.ui.StartupAppListActivity"
    )) { if (tryPkg(pkg)) { ok = true; break } }
    if (!ok) {
    try {
    context.startActivity(
    android.content.Intent(
    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    android.net.Uri.parse("package:${context.packageName}")
    )
    )
    } catch (_: Exception) { }
    }
    }) { Text(s["background.oneTap"]) }
    }
}
/** 🔋 后台运行（保活引导） */
@Composable
private fun BackgroundSettings() {
    val context = LocalContext.current
    val s = LocalStrings.current
    val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    val ignoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(s["background.allow"], style = MaterialTheme.typography.bodySmall)
        if (ignoringBattery) {
            Text(s["background.on"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        } else {
            TextButton(shape = smoothPill(), onClick = {
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                } catch (_: Exception) { }
            }) { Text(s["background.goEnable"]) }
        }
    }
    Text(
        s["background.tip"],
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(s["background.autostart"], style = MaterialTheme.typography.bodySmall)
        TextButton(shape = smoothPill(), onClick = {
            fun tryPkg(pkg: String): Boolean = try { context.startActivity(android.content.Intent(pkg)); true } catch (_: Exception) { false }
            var ok = false
            for (pkg in listOf(
                "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
                "com.honor.appmarket/.hms.startupmgr.ui.StartupNormalAppListActivity",
                "com.coloros.safecenter/.startupapp.StartupAppListActivity",
                "com.miui.securitycenter/.ui.AutoStartManagementActivity",
                "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
                "com.oplus.battery/.ui.StartupAppListActivity"
            )) { if (tryPkg(pkg)) { ok = true; break } }
            if (!ok) {
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                } catch (_: Exception) { }
            }
        }) { Text(s["background.oneTap"]) }
    }
}

/** ℹ️ 关于 → 应用信息卡片：应用名/版本 → 说明 → 仓库网址（蓝色超链接） → Copyright */
@Composable
private fun AboutSettings() {
    val context = LocalContext.current
    val s = LocalStrings.current
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "0.0.0" }
    }
    Card(shape = smoothShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Study Assistant", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(s.format("about.version", "v" to "$appVersion"), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            // 说明
            Text(
                s["about.author"] + "\n" + s["about.desc"],
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            // 仓库网址：蓝色可点（用系统浏览器打开）
            Text(
                s["about.repoLabel"] + UpdateChecker.REPO_URL,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = LINK_BLUE,
                    textDecoration = TextDecoration.Underline
                ),
                modifier = Modifier.clickable { openUrl(context, UpdateChecker.REPO_URL) }
            )
            Spacer(Modifier.height(6.dp))
            // 许可证（灰色小字，不做链接）
            Text(
                "MIT License",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(6.dp))
            // 版权
            Text(
                "Copyright © 2026 ShizhenZhou",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 用系统浏览器打开链接（失败静默忽略，页面上另有「在浏览器打开发布页」兜底） */
internal fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) { /* 没有浏览器等情况：忽略 */ }
}
