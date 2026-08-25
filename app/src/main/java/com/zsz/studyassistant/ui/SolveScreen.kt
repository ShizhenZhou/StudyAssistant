package com.zsz.studyassistant.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import com.zsz.studyassistant.data.Category
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
    val categories by vm.categories.collectAsState()
    var categoryDialogFor by remember { mutableStateOf<String?>(null) } // null / "save" / "change"

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
                        // 分类（已保存时可改）
                        val curCat = categories.firstOrNull { it.id == vm.currentQuestionCategoryId }
                        TextButton(onClick = { categoryDialogFor = "change" }) {
                            Text(if (curCat != null) "📁 ${curCat.name}" else "📁 暂不分类")
                        }
                    } else {
                        // 拍题解题：重新生成 + 存错题本
                        val saveEnabled = vm.chatItems.isNotEmpty() && !vm.busy
                        TextButton(onClick = { vm.regenerate() }, enabled = vm.chatItems.isNotEmpty() && !vm.busy) {
                            Text("🔄 重新生成")
                        }
                        TextButton(
                            onClick = {
                                if (vm.savedToNotebook) {
                                    vm.unsaveFromNotebook()
                                } else {
                                    categoryDialogFor = "save"
                                }
                            },
                            enabled = saveEnabled
                        ) {
                            val label = if (vm.savedToNotebook) "📚 已存错题" else "📚 存错题本"
                            if (vm.savedToNotebook && saveEnabled) {
                                // 已存且可用：灰色区分“已存”
                                Text(label, color = MaterialTheme.colorScheme.outline)
                            } else {
                                // 其它交给 M3：busy 时自动变暗，与「重新生成」一致
                                Text(label)
                            }
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
        // 分类选择对话框（存题 / 改分类）
        when (categoryDialogFor) {
            "save" -> {
                val default = vm.suggestedCategory
                val initId = categories.firstOrNull { it.name == default }?.id
                CategoryDialog(
                    title = "选择分类",
                    categories = categories,
                    initialSelectedId = initId,
                    initialNewName = if (initId == null) default else null,
                    onConfirm = { name, cid -> vm.saveToNotebook(name, cid); categoryDialogFor = null },
                    onDismiss = { categoryDialogFor = null }
                )
            }
            "change" -> {
                CategoryDialog(
                    title = "修改分类",
                    categories = categories,
                    initialSelectedId = vm.currentQuestionCategoryId,
                    initialNewName = null,
                    onConfirm = { name, cid -> vm.changeCurrentCategory(name, cid); categoryDialogFor = null },
                    onDismiss = { categoryDialogFor = null }
                )
            }
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

/**
 * 分类选择对话框：暂不分类 / 已有分类 / 新建分类。
 * initialNewName 非空 → 进入新建模式；否则按 initialSelectedId（null=暂不分类）。
 * onConfirm(name, categoryId)：name 非空表示新建分类，categoryId 为 null 表示暂不分类。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDialog(
    title: String,
    categories: List<Category>,
    initialSelectedId: Long?,
    initialNewName: String?,
    onConfirm: (name: String?, categoryId: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var selId by remember { mutableStateOf(initialSelectedId) }
    var newMode by remember { mutableStateOf(!initialNewName.isNullOrBlank()) }
    var newName by remember { mutableStateOf(initialNewName ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("请选择分类，或新建一个", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                FilterChip(
                    selected = !newMode && selId == null,
                    onClick = { selId = null; newMode = false },
                    label = { Text("暂不分类") }
                )
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories, key = { it.id }) { c ->
                        FilterChip(
                            selected = !newMode && selId == c.id,
                            onClick = { selId = c.id; newMode = false },
                            label = { Text(c.name) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                FilterChip(
                    selected = newMode,
                    onClick = { newMode = true },
                    label = { Text("➕ 新建分类") }
                )
                if (newMode) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("分类名") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newMode && newName.isNotBlank()) {
                    onConfirm(newName.trim(), null)
                } else {
                    onConfirm(null, selId)
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
