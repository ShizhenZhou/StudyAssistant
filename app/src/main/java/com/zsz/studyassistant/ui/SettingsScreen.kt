package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.data.ApiKeyStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(ApiKeyStore.hasKey(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("🔑 DeepSeek API Key", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (hasKey) {
                Text("已配置（如需更换请粘贴新的覆盖）", style = MaterialTheme.typography.bodySmall)
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
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        ApiKeyStore.saveKey(context, input.trim())
                        saved = true
                        hasKey = true
                        input = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = input.isNotBlank()
            ) { Text("保存") }
            if (saved) {
                Spacer(Modifier.height(8.dp))
                Text("✅ 已保存（加密存储，仅存在本机，不会上传）", color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("说明", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "• Key 用 Android Keystore 加密保存，仅存放在本机\n" +
                            "• 把 APK 分享给朋友时，让朋友自己填写自己的 Key\n" +
                            "• 未配置或 Key 无效时会提示去这里检查",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
