package com.zsz.studyassistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolveScreen(nav: NavHostController, vm: MainViewModel) {
    var followUp by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val hasKey = vm.hasApiKey()

    // 退出本页时：若已加入错题本，把当前完整对话更新保存
    DisposableEffect(Unit) {
        onDispose { vm.saveSessionOnExit() }
    }

    // 对话消息：照片模式排除文字题目(用上方原图显示)；文字模式题目作为 user 气泡
    val messages = vm.chatItems
        .filter { it.role != "question" || vm.imageBytes == null }
        .map { c -> ChatMsg(role = if (c.role == "assistant") "assistant" else "user", content = c.content) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("解题") },
                navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text("←") } },
                actions = {
                    TextButton(onClick = { vm.regenerate() }, enabled = vm.chatItems.isNotEmpty() && !vm.busy) {
                        Text("🔄 重新生成")
                    }
                    TextButton(
                        onClick = { vm.toggleSaveNotebook() },
                        enabled = vm.chatItems.isNotEmpty() && !vm.busy
                    ) {
                        Text(
                            if (vm.savedToNotebook) "📚 已存错题" else "📚 存错题本",
                            color = if (vm.savedToNotebook) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                    }
                    if (vm.savedToNotebook) {
                        TextButton(onClick = { showDeleteConfirm = true }) { Text("🗑 删除") }
                    }
                }
            )
        }
    ) { padding ->
        // 删除确认
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("确认删除") },
                text = { Text("确定要将这道错题从错题本中删除吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteSavedQuestion()
                        showDeleteConfirm = false
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                }
            )
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!hasKey) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("⚠️ 尚未配置 DeepSeek API Key", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { nav.navigate("settings") }) { Text("去设置") }
                    }
                }
            }

            vm.error?.let { err ->
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(err, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.clearError() }) { Text("知道了") }
                    }
                }
            }

            // 题目（照片模式：折叠原图）
            if (vm.imageBytes != null) {
                CollapsibleQuestionImage(vm.imageBytes)
            }

            // 对话正文：固定区域 + WebView 内部滚动（滚动条常驻，聊天气泡）
            if (vm.chatItems.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (vm.busy) "答案生成中……" else "正在等待题目…\n（拍照后会出现题目与解答）",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                ConversationWebView(
                    messages = messages,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
                if (vm.busy) {
                    Text(
                        "答案生成中……",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 底部：拍下一题 + 追问输入 + 发送
            if (vm.chatItems.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Button(
                        onClick = {
                            vm.startNewQuestion()
                            nav.navigate("camera")
                        },
                        modifier = Modifier.size(56.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        enabled = !vm.busy
                    ) { Text("📷", fontSize = 22.sp) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = followUp,
                        onValueChange = { followUp = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("继续追问…") },
                        maxLines = 3
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            vm.sendFollowUp(followUp.trim())
                            followUp = ""
                        },
                        enabled = followUp.isNotBlank() && !vm.busy
                    ) { Text("发送") }
                }
            }
        }
    }
}

/** 折叠的原题图片：默认一小条，点击展开当初框选的图 */
@Composable
private fun CollapsibleQuestionImage(imageBytes: ByteArray?) {
    if (imageBytes == null) return
    var expanded by remember { mutableStateOf(false) }
    val bitmap = remember(imageBytes) {
        try { BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) } catch (e: Exception) { null }
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Column(Modifier.padding(8.dp)) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (expanded) "▴ 收起原题图片" else "▾ 点击查看原题图片")
            }
            if (expanded && bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "原题图片",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
