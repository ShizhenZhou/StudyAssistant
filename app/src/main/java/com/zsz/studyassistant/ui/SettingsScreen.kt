package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(vm: MainViewModel) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(ApiKeyStore.hasKey(context)) }

    Column(
        Modifier
            .fillMaxSize()
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
