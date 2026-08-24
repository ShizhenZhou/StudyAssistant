package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.ApiKeyStore

@Composable
fun HomeScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current
    var keyOk by remember { mutableStateOf(vm.hasApiKey()) }
    var showDialog by remember { mutableStateOf(false) }
    // 首次打开且未配 Key → 自动弹填 Key 对话框
    LaunchedEffect(keyOk) { if (!keyOk) showDialog = true }

    Box(Modifier.fillMaxSize()) {
        TextButton(
            onClick = { nav.navigate("settings") },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 16.dp)
        ) { Text(if (keyOk) "⚙️" else "⚙️ 填Key") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Study Assistant", fontSize = 32.sp, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("拍照搜题 · AI 解答 · 错题整理", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = { nav.navigate("camera") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text("📷  拍照搜题", fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { nav.navigate("notebook") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text("📚  错题本", fontSize = 20.sp)
            }
        }
    }

    // 填 Key 对话框
    if (showDialog) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("配置 DeepSeek API Key") },
            text = {
                Column {
                    Text("请输入你的 DeepSeek API Key（sk- 开头，加密保存在本机）", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("sk-...") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (input.isNotBlank()) {
                        ApiKeyStore.saveKey(context, input.trim())
                        keyOk = true
                        showDialog = false
                    }
                }, enabled = input.isNotBlank()) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("稍后") }
            }
        )
    }
}
