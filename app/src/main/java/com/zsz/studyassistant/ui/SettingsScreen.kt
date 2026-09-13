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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.AiLang
import com.zsz.studyassistant.data.ApiKeyStore
import com.zsz.studyassistant.data.ReminderScheduler
import com.zsz.studyassistant.data.systemLangName

/** 设置：主页面为入口列表，点进去到二级页面进行具体设置 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(vm: MainViewModel) {
    var page by remember { mutableStateOf("main") }
    BackHandler(enabled = page != "main") { page = "main" }
    val s = LocalStrings.current

    when (page) {
        "api" -> SettingsSubPage(s["settings.api"], { page = "main" }) { ApiSettings() }
        "lang" -> SettingsSubPage(s["lang.title"], { page = "main" }) { LanguageSettings(vm) }
        "theme" -> SettingsSubPage(s["settings.theme"], { page = "main" }) { ThemeSettings(vm) }
        "notify" -> SettingsSubPage(s["settings.notify"], { page = "main" }) { NotifySettings() }
        "background" -> SettingsSubPage(s["settings.background"], { page = "main" }) { BackgroundSettings() }
        "changelog" -> SettingsSubPage(s["settings.changelog"], { page = "main" }) { ChangelogSettings() }
        else -> SettingsMain { page = it }
    }
}

/** 📝 更新内容：显示各版本更新记录 */
@Composable
private fun ChangelogSettings() {
    Text(CHANGELOG, style = MaterialTheme.typography.bodySmall)
}

/** 二级页面容器：返回键 + 标题 + 可滚动内容 */
@Composable
private fun SettingsSubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
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
        Spacer(Modifier.height(16.dp))
        SettingEntry(s["settings.api"]) { onOpen("api") }
        SettingEntry(s["settings.lang"]) { onOpen("lang") }
        SettingEntry(s["settings.theme"]) { onOpen("theme") }
        SettingEntry(s["settings.notify"]) { onOpen("notify") }
        SettingEntry(s["settings.background"]) { onOpen("background") }
        SettingEntry(s["settings.changelog"]) { onOpen("changelog") }

        Spacer(Modifier.height(16.dp))

        // ℹ️ 关于：直接显示在主页，不做二级菜单
        Text(s["settings.about"], style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        AboutSettings()
    }
}

@Composable
private fun SettingEntry(title: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
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

/** 🔑 API Key 设置 */
@Composable
private fun ApiSettings() {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(ApiKeyStore.hasKey(context)) }

    if (hasKey) {
        Text("已配置（粘贴新的可覆盖）", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            "尚未配置，请粘贴你的 DeepSeek API Key（sk- 开头）",
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
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = {
            if (input.isNotBlank()) {
                ApiKeyStore.saveKey(context, input.trim())
                saved = true; hasKey = true; input = ""
            }
        },
        enabled = input.isNotBlank()
    ) { Text("保存") }
    if (saved) {
        Text("✅ 已保存（加密存储，仅本机）", color = MaterialTheme.colorScheme.primary)
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
    AiLang.FOLLOW_SYSTEM -> "${s["lang.followSystem"]}（${systemLangName()}）"
    AiLang.FOLLOW_QUESTION -> s["lang.followQuestion"]
    else -> nativeName(lang.id)
}

/** 🎨 应用主题 */
@Composable
private fun ThemeSettings(vm: MainViewModel) {
    val themes = listOf("跟随系统" to "system", "浅色" to "light", "深色" to "dark")
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
private fun NotifySettings() {
    val context = LocalContext.current
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
        Text("开启每日提醒")
        Switch(checked = notifyEnabled, onCheckedChange = { saveNotify(it) })
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("提醒时间")
        TextButton(onClick = { showTimePicker = true }) { Text("%02d:%02d".format(notifyHour, notifyMinute)) }
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
    Text("到点会提醒：今天还有 xx 道错题要复习！本周末前还有 xx 道！", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

/** 🔋 后台运行（保活引导） */
@Composable
private fun BackgroundSettings() {
    val context = LocalContext.current
    val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    val ignoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("允许后台运行（不漏提醒）", style = MaterialTheme.typography.bodySmall)
        if (ignoringBattery) {
            Text("✅ 已开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        } else {
            TextButton(onClick = {
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                } catch (_: Exception) { }
            }) { Text("去开启") }
        }
    }
    Text(
        "提示：国产手机（荣耀/华为等）还需在「手机管家 → 应用 → 自启动/后台运行」中允许本应用，才能在关闭后仍收到提醒、后台生成不中断。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("自启动/后台运行权限", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
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
        }) { Text("一键开启") }
    }
}

/** ℹ️ 关于 */
@Composable
private fun AboutSettings() {
    val context = LocalContext.current
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "0.0.0" }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Study Assistant", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text("版本 $appVersion", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "作者：zsz\n" +
                    "本应用由 DeepSeek-V4 辅助编写，用于拍照搜题、AI 解答与错题整理。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
