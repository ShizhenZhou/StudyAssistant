package com.zsz.studyassistant.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.pow
import kotlin.math.ln
import kotlin.math.abs
import kotlin.math.roundToInt
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.AiLang
import com.zsz.studyassistant.data.ApiKeyStore
import com.zsz.studyassistant.data.ReminderScheduler

/** 设置：主页面为入口列表，点进去到二级页面进行具体设置 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(vm: MainViewModel) {
    var page by remember { mutableStateOf("main") }
    BackHandler(enabled = page != "main") { page = "main" }
    val s = LocalStrings.current

    when (page) {
        "api" -> SettingsSubPage(s["settings.api"], { page = "main" }) { ApiSettings(vm) }
        "aicrop" -> SettingsSubPage(s["settings.aiCrop"], { page = "main" }) { AiCropTimeoutSettings() }
        "lang" -> SettingsSubPage(s["lang.title"], { page = "main" }) { LanguageSettings(vm) }
        "theme" -> SettingsSubPage(s["settings.theme"], { page = "main" }) { ThemeSettings(vm) }
        "notify" -> SettingsSubPage(s["settings.group.notifyBackground"], { page = "main" }) { NotifyBackgroundSettings() }
        "data" -> SettingsSubPage(s["settings.data"], { page = "main" }) { DataSettings(vm) }
        "about" -> SettingsSubPage(s["settings.about"], { page = "main" }) { AboutPage() }
        else -> SettingsMain { page = it }
    }
}

/** ℹ️ 关于：上面应用信息，下面更新内容 */
@Composable
private fun AboutPage() {
    val s = LocalStrings.current
    Text(s["about.info"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    AboutSettings()
    Spacer(Modifier.height(20.dp))
    Text(s["about.changelog"], style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(changelogAnnotated(changelogText(LocalUiLang.current)), style = MaterialTheme.typography.bodySmall)
}

/**
 * 更新内容里的 `**加粗**` 标记 → 真正的粗体。
 * 这一页是纯 Text 渲染（不像答案走 WebView 的 Markdown），不处理就会把星号直接显示出来。
 */
private fun changelogAnnotated(text: String): AnnotatedString = buildAnnotatedString {
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

/** 二级页面容器：返回键 + 标题 + 可滚动内容 */
@Composable
private fun SettingsSubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(shape = smoothPill(), onClick = onBack) { Text("←") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
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

        // ── 通用 ──
        SettingGroupTitle(s["settings.group.general"])
        SettingEntry(s["settings.api"]) { onOpen("api") }
        SettingEntry(s["settings.aiCrop"]) { onOpen("aicrop") }
        // 通知与后台：已合并为一个二级菜单，归入「通用」大类
        SettingEntry(s["settings.group.notifyBackground"]) { onOpen("notify") }

        Spacer(Modifier.height(14.dp))
        // ── 个性化 ──
        SettingGroupTitle(s["settings.group.personalize"])
        SettingEntry(s["settings.theme"]) { onOpen("theme") }
        SettingEntry(s["settings.lang"]) { onOpen("lang") }

        Spacer(Modifier.height(26.dp))
        // ── 数据管理 / 关于（分立，不归入大类；上方多留一行） ──
        SettingEntry(s["settings.data"]) { onOpen("data") }
        SettingEntry(s["settings.about"]) { onOpen("about") }
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
 * ✂️ AI 框选时限：100ms ~ 5s 的**对数**滑条，500ms / 1s / 3s 三个磁吸点。
 * 含义：进框选页后等 AI 自动框选的上限；超时就用本地算法框选。
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
        Text(s["settings.aiCrop"], style = MaterialTheme.typography.titleMedium)
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
private fun SettingEntry(title: String, onClick: () -> Unit) {
    Card(shape = smoothShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 🔑 API 管理：上面是 Key 设置，下面是累计用量统计 */
@Composable
private fun ApiSettings(vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var input by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
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
        onValueChange = { input = it; saved = false },
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
                saved = true; hasKey = true; input = ""
            }
        },
        enabled = input.isNotBlank()
    ) { Text(s["common.save"]) }
    if (saved) {
        Text(s["settings.api.saved"], color = MaterialTheme.colorScheme.primary)
    }

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

/** 💾 数据管理：导出 / 导入 / 清空 */
@Composable
private fun DataSettings(vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var showClearConfirm by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refreshDataSummary() }

    val summary = vm.dataSummary
    Text(
        if (summary == null) s["data.summary"].replace("{q}", "…").replace("{c}", "…").replace("{t}", "…")
        else s.format("data.summary", "q" to "${summary.first}", "c" to "${summary.second}", "t" to "${summary.third}"),
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(16.dp))

    // 导出
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            vm.exportBackup(onReady = { json ->
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, e.message ?: "export failed", android.widget.Toast.LENGTH_LONG).show()
                }
            }, onError = { })
        }
    }
    Button(shape = smoothPill(), onClick = { exportLauncher.launch("study-assistant-backup.json") }, modifier = Modifier.fillMaxWidth()) {
        Text(s["data.export"])
    }
    Spacer(Modifier.height(4.dp))
    Text(s["data.export.desc"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

    Spacer(Modifier.height(16.dp))

    // 导入
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            } catch (e: Exception) { null }
            if (text != null) { pendingImport = text; showImportConfirm = true }
        }
    }
    OutlinedButton(shape = smoothPill(), onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
        Text(s["data.import"])
    }
    Spacer(Modifier.height(4.dp))
    Text(s["data.import.desc"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

    Spacer(Modifier.height(16.dp))

    // 清空
    OutlinedButton(shape = smoothPill(), 
        onClick = { showClearConfirm = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) { Text(s["data.clear"]) }
    Spacer(Modifier.height(4.dp))
    Text(s["data.clear.desc"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

    if (vm.dataBusy) {
        Spacer(Modifier.height(12.dp))
        Text(s["data.exporting"], style = MaterialTheme.typography.bodySmall)
    }
    vm.dataMessage?.let { msg ->
        Spacer(Modifier.height(12.dp))
        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false; pendingImport = null },
            title = { Text(s["data.import"]) },
            text = { Text(s["data.import.desc"]) },
            confirmButton = {
                TextButton(shape = smoothPill(), onClick = {
                    pendingImport?.let { vm.importBackup(it) }
                    showImportConfirm = false; pendingImport = null
                    vm.refreshDataSummary()
                }) { Text(s["common.ok"]) }
            },
            dismissButton = { TextButton(shape = smoothPill(), onClick = { showImportConfirm = false; pendingImport = null }) { Text(s["common.cancel"]) } }
        )
    }

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
private fun ThemeSettings(vm: MainViewModel) {
    val s = LocalStrings.current
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
        if (enabled && android.os.Build.VERSION.SDK_INT >= 33) {
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

/** ℹ️ 关于 */
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
            Text(
                s["about.author"] + "\n" + s["about.desc"],
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
