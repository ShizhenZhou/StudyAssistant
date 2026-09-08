package com.zsz.studyassistant.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.zsz.studyassistant.ChatItem
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.Category
import com.zsz.studyassistant.data.StudyAssistant
import com.zsz.studyassistant.data.Tag
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
    val tags by vm.tags.collectAsState()
    var categoryDialogFor by remember { mutableStateOf<String?>(null) } // null / "save" / "change"
    var editMode by remember { mutableStateOf(false) }
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showEditDelete by remember { mutableStateOf(false) }

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
                title = {
                    Text(
                        if (editMode) "已选 ${selectedIndices.size} 条" else "解题",
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (editMode) {
                        TextButton(onClick = { editMode = false; selectedIndices = emptySet() }) { Text("✕", fontSize = 18.sp) }
                    } else {
                        TextButton(onClick = {
                            if (vm.isFromNotebook) {
                                nav.popBackStack()
                            } else {
                                // 拍题流程：返回直接回拍题界面
                                vm.startNewQuestion()
                                nav.navigate("camera") { popUpTo("home") }
                            }
                        }) { Text("←") }
                    }
                },
                actions = {
                    if (vm.reviewMode) {
                        TextButton(onClick = { vm.exitReviewMode(); nav.popBackStack() }) { Text("✓ 完成") }
                    } else if (editMode) {
                        TextButton(onClick = { showEditDelete = true }, enabled = selectedIndices.isNotEmpty()) { Text("🗑 删除") }
                        TextButton(onClick = { editMode = false; selectedIndices = emptySet() }) { Text("✓ 完成") }
                    } else {
                        TextButton(onClick = { editMode = true }) { Text("✏️ 编辑") }
                        if (vm.isFromNotebook) {
                            if (vm.isDeleted) {
                                TextButton(onClick = { vm.restoreSavedQuestion() }) { Text("↩ 恢复") }
                            } else {
                                TextButton(onClick = { showDeleteConfirm = true }) { Text("🗑 删除") }
                            }
                            val curCat = categories.firstOrNull { it.id == vm.currentQuestionCategoryId }
                            TextButton(onClick = { categoryDialogFor = "change" }) {
                                val catName = curCat?.name ?: "暂不分类"
                                Text(
                                    if (curCat != null) "📁 ${catName}" else "📁 暂不分类",
                                    fontSize = when {
                                        catName.length <= 3 -> 14.sp
                                        catName.length <= 5 -> 12.sp
                                        else -> 11.sp
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
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
                                if (vm.savedToNotebook && saveEnabled) Text(label, color = Color(0xFF4CAF50)) else Text(label)
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
        // 编辑模式：删除选中消息确认
        if (showEditDelete) {
            AlertDialog(
                onDismissRequest = { showEditDelete = false },
                title = { Text("删除消息") },
                text = { Text("确定删除选中的 ${selectedIndices.size} 条消息吗？删除后 AI 会基于剩余消息继续对话。") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteMessages(selectedIndices.toList())
                        showEditDelete = false
                        editMode = false
                        selectedIndices = emptySet()
                    }) { Text("删除") }
                },
                dismissButton = { TextButton(onClick = { showEditDelete = false }) { Text("取消") } }
            )
        }
        // 分类 + 标签选择对话框（存题 / 详情页改）
        when (categoryDialogFor) {
            "save" -> {
                val defaultCat = vm.suggestedCategory
                val initCatId = categories.firstOrNull { it.name == defaultCat }?.id
                SaveDialog(
                    title = "选择分类与标签",
                    categories = categories,
                    tags = tags,
                    initialSelectedId = initCatId,
                    initialNewName = if (initCatId == null) defaultCat else null,
                    initialSelectedTagIds = emptyList(),
                    suggestedTagNames = vm.suggestedTags,
                    onConfirm = { name, cid, tagNames, tagIds ->
                        vm.saveToNotebook(name, cid, tagNames, tagIds)
                        categoryDialogFor = null
                    },
                    onDismiss = { categoryDialogFor = null }
                )
            }
            "change" -> {
                SaveDialog(
                    title = "修改分类与标签",
                    categories = categories,
                    tags = tags,
                    initialSelectedId = vm.currentQuestionCategoryId,
                    initialNewName = null,
                    initialSelectedTagIds = vm.currentQuestionTags,
                    suggestedTagNames = emptyList(),
                    onConfirm = { name, cid, tagNames, tagIds ->
                        vm.changeCurrentCategory(name, cid)
                        vm.setCurrentTags(tagIds, tagNames)
                        categoryDialogFor = null
                    },
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

            // 编辑模式：Compose 列表（可勾选删除），否则单 WebView 稳定滚动
            if (editMode) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(vm.chatItems, key = { _, it -> it.id }) { idx, item ->
                        EditMsgRow(
                            item = item,
                            selected = selectedIndices.contains(idx),
                            onClick = {
                                selectedIndices = if (selectedIndices.contains(idx)) selectedIndices - idx else selectedIndices + idx
                            }
                        )
                    }
                }
            } else if (vm.chatItems.isEmpty()) {
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

            // 复习模式：底部为 熟悉/模糊/忘记 三按钮
            if (vm.reviewMode) {
                val qid = vm.savedQuestionId
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { qid?.let { vm.reviewQuestion(it, 2) }; nav.popBackStack() },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) { Text("🌱 熟悉") }
                    Button(
                        onClick = { qid?.let { vm.reviewQuestion(it, 1) }; nav.popBackStack() },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300))
                    ) { Text("🌤 模糊") }
                    Button(
                        onClick = { qid?.let { vm.reviewQuestion(it, 0) }; nav.popBackStack() },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    ) { Text("🔥 忘记") }
                }
            } else if (vm.chatItems.isNotEmpty()) {
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
internal fun CategoryDialog(
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

/** 编辑（多选删除）模式下的一条消息 */
@Composable
private fun EditMsgRow(item: ChatItem, selected: Boolean, onClick: () -> Unit) {
    val isAssistant = item.role == "assistant"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isAssistant) Color(0xFFE9E9E9) else Color(0xFFD8F2D8))
            .then(if (selected) Modifier.border(2.dp, Color(0xFF4CAF50), RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (isAssistant) "🤖 " else "🧑 ") + item.content.replace('\n', ' '),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF111111)
            )
            if (!item.images.isNullOrEmpty()) {
                Text("[图]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Box(
            Modifier.size(20.dp).background(if (selected) Color(0xFF4CAF50) else Color(0x88000000), CircleShape),
            contentAlignment = Alignment.Center
        ) { if (selected) Text("✓", color = Color.White, fontSize = 12.sp) }
    }
}

/**
 * 存题 / 详情页修改用：分类（单选）+ 知识点标签（多选 ≤5）。
 * onConfirm(name, categoryId, tagNames, tagIds)：name/tagNames 非空 → 新建分类/标签；categoryId/tagIds 为空 → 暂不分类/无标签。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveDialog(
    title: String,
    categories: List<Category>,
    tags: List<Tag>,
    initialSelectedId: Long?,
    initialNewName: String?,
    initialSelectedTagIds: List<Long>,
    suggestedTagNames: List<String>,
    onConfirm: (name: String?, categoryId: Long?, tagNames: List<String>, tagIds: List<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    // 分类状态
    var selCatId by remember { mutableStateOf(initialSelectedId) }
    var newCatMode by remember { mutableStateOf(!initialNewName.isNullOrBlank()) }
    var newCatName by remember { mutableStateOf(initialNewName ?: "") }

    // 标签：统一用"标签名"集合表示选中（含已有 tag 名与 AI 建议/新建名），最多 5 个
    val existingNames = remember(tags) { tags.map { it.name }.toSet() }
    val initNames = remember(initialSelectedTagIds, suggestedTagNames, tags) {
        val set = LinkedHashSet<String>()
        for (id in initialSelectedTagIds) tags.firstOrNull { it.id == id }?.name?.let { set += it }
        for (s in suggestedTagNames) { if (set.size < 5) set += s }
        set
    }
    var selectedNames by remember { mutableStateOf(initNames) }
    var newTag by remember { mutableStateOf("") }

    fun toggleTagName(name: String) {
        if (selectedNames.contains(name)) {
            selectedNames = LinkedHashSet(selectedNames.apply { remove(name) })
        } else if (selectedNames.size < 5) {
            selectedNames = LinkedHashSet(selectedNames.apply { add(name) })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("分类", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !newCatMode && selCatId == null, onClick = { selCatId = null; newCatMode = false }, label = { Text("暂不分类") })
                }
                if (categories.isNotEmpty()) {
                    LazyRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories, key = { it.id }) { c ->
                            FilterChip(selected = !newCatMode && selCatId == c.id, onClick = { selCatId = c.id; newCatMode = false }, label = { Text(c.name) })
                        }
                    }
                }
                FilterChip(selected = newCatMode, onClick = { newCatMode = true }, label = { Text("➕ 新建分类") })
                if (newCatMode) {
                    OutlinedTextField(value = newCatName, onValueChange = { newCatName = it }, label = { Text("分类名") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }

                Spacer(Modifier.height(14.dp))
                Text("知识点标签（最多 5 个）", style = MaterialTheme.typography.labelMedium)
                // 已有标签（点击选中/取消）
                if (tags.isNotEmpty()) {
                    LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tags, key = { it.id }) { t ->
                            FilterChip(
                                selected = selectedNames.contains(t.name),
                                onClick = { toggleTagName(t.name) },
                                label = { Text(t.name) }
                            )
                        }
                    }
                }
                // 将新建的标签（AI 建议/手动添加，不在已有 tag 里），选中态
                val toCreate = selectedNames.filter { it !in existingNames && it.isNotBlank() }
                if (toCreate.isNotEmpty()) {
                    LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(toCreate, key = { it }) { name ->
                            FilterChip(
                                selected = true,
                                onClick = { toggleTagName(name) },
                                label = { Text(name) }
                            )
                        }
                    }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("新增标签") },
                        singleLine = true,
                        placeholder = { Text("如 分部积分") }
                    )
                    TextButton(onClick = {
                        val n = newTag.trim()
                        if (n.isNotEmpty() && n !in selectedNames && selectedNames.size < 5) { toggleTagName(n); newTag = "" }
                    }) { Text("加") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cname = if (newCatMode && newCatName.isNotBlank()) newCatName.trim() else null
                // 拆分为已有 id + 待新建名
                val tagIds = mutableListOf<Long>()
                val newNames = mutableListOf<String>()
                for (name in selectedNames) {
                    val t = tags.firstOrNull { it.name == name }
                    if (t != null) tagIds += t.id else newNames += name
                }
                onConfirm(cname, selCatId, newNames, tagIds)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

