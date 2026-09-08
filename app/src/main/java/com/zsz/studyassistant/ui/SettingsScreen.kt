package com.zsz.studyassistant.ui

import android.Manifest
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.zsz.studyassistant.data.ApiKeyStore
import com.zsz.studyassistant.data.ReminderScheduler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(vm: MainViewModel) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(ApiKeyStore.hasKey(context)) }

    // 通知设置
    val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var notifyEnabled by remember { mutableStateOf(prefs.getBoolean("notify_enabled", false)) }
    var notifyHour by remember { mutableStateOf(prefs.getInt("notify_hour", 20)) }
    var notifyMinute by remember { mutableStateOf(prefs.getInt("notify_minute", 0)) }
    val notifyPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun saveNotify(enabled: Boolean) {
        prefs.edit().putBoolean("notify_enabled", enabled).apply()
        notifyEnabled = enabled
        ReminderScheduler.applySchedule(context)
        if (enabled && android.os.Build.VERSION.SDK_INT >= 33) {
            notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        // 🔑 Key 设置
        Text("🔑 DeepSeek API Key", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (hasKey) {
            Text("已配置（粘贴新的可覆盖）", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("尚未配置，请粘贴你的 DeepSeek API Key（sk- 开头）",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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

        Spacer(Modifier.height(20.dp))

        // 🎨 应用主题
        Text("🎨 应用主题", style = MaterialTheme.typography.titleMedium)
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

        Spacer(Modifier.height(20.dp))

        // 🔔 复习提醒
        Text("🔔 复习提醒", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("开启每日提醒")
            Switch(checked = notifyEnabled, onCheckedChange = { saveNotify(it) })
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("提醒时间")
            TextButton(onClick = {
                TimePickerDialog(context, { _, h, m ->
                    prefs.edit().putInt("notify_hour", h).putInt("notify_minute", m).apply()
                    notifyHour = h; notifyMinute = m
                    ReminderScheduler.applySchedule(context)
                }, notifyHour, notifyMinute, true).show()
            }) { Text("%02d:%02d".format(notifyHour, notifyMinute)) }
        }
        Text("到点会提醒：今天还有 xx 道错题要复习！本周末前还有 xx 道！", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

        // 后台保活（国产机要允许自启动/后台运行，关掉 app 也能收到提醒）
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
            "提示：国产手机（荣耀/华为等）还需在「手机管家 → 应用 → 自启动/后台运行」中允许本应用，才能在关闭后仍收到提醒。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(Modifier.height(20.dp))

        // ℹ️ 关于
        // 读取真实安装版本号，保持与 App 实际版本一致
        val appVersion = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: Exception) { "0.0.0" }
        }
        Text("ℹ️ 关于", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
}
