package com.zsz.studyassistant.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.StudyAssistant
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolveScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current
    var followUp by remember { mutableStateOf("") }
    var selectedImages by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val hasKey = vm.hasApiKey()

    // 从相册选 1~3 张图，附到追问消息里
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(3)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newOnes = uris.mapNotNull { uri ->
                try {
                    val file = File.createTempFile("fup", ".jpg", context.cacheDir)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    StudyAssistant.compressImage(file)
                } catch (e: Exception) {
                    null
                }
            }
            // 最多保留 3 张
            selectedImages = (selectedImages + newOnes).take(3)
        }
    }

    // 退出本页时：若已加入错题本，把当前完整对话更新保存
    DisposableEffect(Unit) {
        onDispose { vm.saveSessionOnExit() }
    }

    // 对话消息：照片模式排除文字题目(用上方原图显示)；文字模式题目作为 user 气泡
    val messages = vm.chatItems
        .filter { it.role != "question" || vm.imageBytes == null }
        .map { c ->
            ChatMsg(
                role = if (c.role == "assistant") "assistant" else "user",
                content = c.content,
                images = c.images ?: emptyList()
            )
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("解题") },
                navigationIcon = {
                    TextButton(onClick = {
                        if (vm.isFromNotebook) {
                            nav.popBackStack()
                        } else {
                            // 拍题流程：返回直接回拍题界面
                            vm.startNewQuestion()
                            nav.navigate("camera") { popUpTo("home") }
                        }
                    }) { Text("←") }
                },
                actions = {
                    if (vm.isFromNotebook) {
                        // 错题本回顾：已软删除 → 恢复；否则 → 删除（带确认）
                        if (vm.isDeleted) {
                            TextButton(onClick = { vm.restoreSavedQuestion() }) { Text("↩ 恢复") }
                        } else {
                            TextButton(onClick = { showDeleteConfirm = true }) { Text("🗑 删除") }
                        }
                    } else {
                        // 拍题解题：重新生成 + 存错题本
                        val saveEnabled = vm.chatItems.isNotEmpty() && !vm.busy
                        TextButton(onClick = { vm.regenerate() }, enabled = vm.chatItems.isNotEmpty() && !vm.busy) {
                            Text("🔄 重新生成")
                        }
                        TextButton(
                            onClick = { vm.toggleSaveNotebook() },
                            enabled = saveEnabled
                        ) {
                            Text(
                                if (vm.savedToNotebook) "📚 已存错题" else "📚 存错题本",
                                color = if (!saveEnabled || vm.savedToNotebook)
                                    MaterialTheme.colorScheme.outline
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                        }
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
        Column(Modifier.fillMaxSize().imePadding().padding(padding)) {
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

            // 网络意外断开 → 蓝色下划线"继续生成"，点击后用最后提问内容重新生成
            if (vm.networkError) {
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("网络连接似乎中断了", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.retry() }) {
                            Text(
                                "继续生成",
                                color = Color(0xFF2196F3),
                                textDecoration = TextDecoration.Underline
                            )
                        }
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

            // 底部：已选附图预览 + 图库选图 + 追问输入 + 发送
            if (vm.chatItems.isNotEmpty()) {
                // 已选 1~3 张附图缩略图（可删除）
                if (selectedImages.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedImages.forEach { img ->
                            val bmp = remember(img) {
                                try { BitmapFactory.decodeByteArray(img, 0, img.size) } catch (e: Exception) { null }
                            }
                            if (bmp != null) {
                                Box(Modifier.size(60.dp)) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "附图",
                                        modifier = Modifier.size(60.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                    // 右上角删除
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .size(22.dp)
                                            .clickable { selectedImages = selectedImages - img }
                                            .background(Color(0xCC000000), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) { Text("✕", color = Color.White, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 图库选图按钮（选 1~3 张附在追问里）
                    Surface(
                        onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !vm.busy,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("🖼", fontSize = 18.sp) }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = followUp,
                        onValueChange = { followUp = it },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        placeholder = { Text("继续追问（可带图）…", fontSize = 14.sp) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        maxLines = 3,
                        shape = RoundedCornerShape(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            vm.sendFollowUp(followUp.trim(), selectedImages)
                            followUp = ""
                            selectedImages = emptyList()
                        },
                        enabled = followUp.isNotBlank() && !vm.busy,
                        shape = RoundedCornerShape(22.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) { Text("发送", fontSize = 14.sp) }
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
