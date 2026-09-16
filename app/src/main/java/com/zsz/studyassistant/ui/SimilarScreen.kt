package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel

/** 练同类题：AI 出一道同类题，先只显示题目，可互动；顶部「查看答案」再显示答案 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarScreen(nav: NavHostController, vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = LocalStrings.current
    var input by remember { mutableStateOf("") }
    // 多选（长按进入）：与原消息界面就地操作
    var editMode by remember { mutableStateOf(false) }
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    // 追问附图（与解题页一致，最多 3 张）
    var selectedImages by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    val categories by vm.categories.collectAsState()
    val tags by vm.tags.collectAsState()
    // 系统相册（有序选择）→ 追加附图
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(3)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newOnes = uris.take(3).mapNotNull { uriToCompressedBytes(context, it) }
            selectedImages = (selectedImages + newOnes).take(3)
        }
    }
    var scrollTopTick by remember { mutableIntStateOf(0) }
    var scrollBottomTick by remember { mutableIntStateOf(0) }
    var atTop by remember { mutableStateOf(true) }
    // 会话内容是否超过一屏：没超过就不显示 ↑/↓ 按钮
    var scrollable by remember { mutableStateOf(false) }
    val question = vm.similarQuestion
    val answer = vm.similarAnswer
    val revealed = vm.similarRevealed
    val messages = remember(vm.similarQuestion, vm.similarMessages, revealed, vm.similarStreamingText) {
        buildList {
            // 出题中：流式文本就是题目本身（只含题目部分，答案不外显）；出题完成后用正式题目
            val qText = vm.similarQuestion ?: vm.similarStreamingText
            if (qText != null) add(ChatMsg("assistant", qText))
            // 出题首字未到（思考中）：也要有一条**气泡**，而不是空白页
            if (qText == null && vm.similarBusy) add(ChatMsg("assistant", s["solve.thinking"]))
            for (m in vm.similarMessages) add(ChatMsg(if (m.role == "assistant") "assistant" else "user", m.content))
            // 追问中：把流式回复作为最后一条实时气泡；首字未到时显示「思考中…」
            if (vm.similarQuestion != null && vm.similarStreamingText != null) {
                val live = vm.similarStreamingText!!
                add(ChatMsg("assistant", if (live.isEmpty()) s["solve.thinking"] else live + "\n\n▍"))
            }
            if (revealed && vm.similarAnswer != null) add(ChatMsg("assistant", s.format("similar.answerPrefix", "body" to vm.similarAnswer!!)))
        }
    }

    // 多选时禁止删除：AI 出的题目（第一条 assistant 消息）
    val lockedIndices = remember(messages) {
        buildSet {
            messages.indexOfFirst { it.role == "assistant" }.takeIf { it >= 0 }?.let { add(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (editMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val selectable = messages.indices.filter { it !in lockedIndices }
                            val allSelected = selectable.isNotEmpty() && selectedIndices.size == selectable.size
                            TextButton(
                                onClick = { selectedIndices = if (allSelected) emptySet() else messages.indices.filter { it !in lockedIndices }.toSet() },
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Text(if (allSelected) s["solve.deselectAll"] else s["solve.selectAll"], fontSize = 13.sp)
                            }
                            Text(
                                s.format("solve.selectedCount", "n" to "${selectedIndices.size}"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else {
                        Text(s["similar.title"], maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (editMode) {
                        TextButton(onClick = { editMode = false; selectedIndices = emptySet() }) { Text("✕") }
                    } else {
                        TextButton(onClick = { nav.popBackStack() }) { Text("←") }
                    }
                },
                actions = {
                    if (editMode) {
                        TextButton(onClick = { showDeleteConfirm = true }, enabled = selectedIndices.isNotEmpty()) { Text(s["solve.delete"], fontSize = 13.sp) }
                        TextButton(onClick = { editMode = false; selectedIndices = emptySet() }) { Text(s["solve.done"], fontSize = 13.sp) }
                    } else {
                        // 「查看答案」：题目已出现就显示；**答案还没生成好时置灰不可用**
                        val questionShown = vm.similarQuestion != null || vm.similarStreamingText != null
                        if (questionShown && !revealed) {
                            TextButton(
                                onClick = { vm.revealSimilarAnswer() },
                                enabled = answer != null
                            ) { Text(s["similar.showAnswer"]) }
                        }
                        // 生成中：⏸ 中止生成；空闲：🔄 重新生成（清空会话并重出一道题）
                        if (vm.similarBusy) {
                            TextButton(onClick = { vm.abortSimilar() }) {
                                Text(s["solve.abort"], fontSize = 13.sp)
                            }
                        } else if (questionShown) {
                            TextButton(onClick = { vm.startSimilar() }) {
                                Text(s["solve.regen"], fontSize = 13.sp)
                            }
                        }
                        // 存错题本（逻辑同解题页：选分类/标签后保存；已存再点 = 取消保存）
                        val saved = vm.similarSavedQuestionId != null
                        TextButton(
                            onClick = {
                                if (saved) vm.unsaveSimilarFromNotebook() else showSaveDialog = true
                            },
                            enabled = questionShown
                        ) {
                            val label = if (saved) s["solve.savedToNotebook"] else s["solve.saveToNotebook"]
                            if (saved) Text(label, color = Color(0xFF4CAF50), fontSize = 13.sp)
                            else Text(label, fontSize = 13.sp)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().imePadding().padding(padding)) {
            // 出题/追问生成中也走 WebView：首字未到时由「思考中…」气泡占位（不再用居中文字）
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(s["solve.thinking"], style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ConversationWebView(
                        messages = messages,
                        scrollTopSignal = scrollTopTick,
                        scrollBottomSignal = scrollBottomTick,
                        onAtTopChange = { atTop = it },
                        onScrollableChange = { scrollable = it },
                        // 长按气泡 → 进入多选并选中该条（多选态下长按无效：只允许单次点击选择）
                        onLongPressMessage = { idx ->
                            if (!editMode && !vm.similarBusy && idx in messages.indices) {
                                if (idx in lockedIndices) {
                                    editMode = true
                                    selectedIndices = emptySet()
                                } else if (editMode) {
                                    selectedIndices = if (selectedIndices.contains(idx)) selectedIndices - idx else selectedIndices + idx
                                } else {
                                    editMode = true
                                    selectedIndices = setOf(idx)
                                }
                            }
                        },
                        selectionMode = editMode,
                        selectedIndices = selectedIndices,
                        lockedIndices = lockedIndices,
                        onToggleSelect = { idx ->
                            if (idx in messages.indices && idx !in lockedIndices) {
                                selectedIndices = if (selectedIndices.contains(idx)) selectedIndices - idx else selectedIndices + idx
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)
                    )
                    // 快速跳转：仅在**内容超过一屏**时出现；顶部时显示 ↓（滚到最底），否则 ↑（回到顶部）
                    if (!editMode && scrollable) {
                        Surface(
                            onClick = { if (atTop) scrollBottomTick++ else scrollTopTick++ },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
                            shadowElevation = 3.dp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 12.dp)
                                .size(44.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(if (atTop) "↓" else "↑", fontSize = 19.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }
            // 底部：输入栏（与解题页同一组件：附图 + 公式键盘 + 图库 + 发送）
            if (vm.similarQuestion != null || vm.similarStreamingText != null) {
                ConversationInputBar(
                    value = input,
                    onValueChange = { input = it },
                    images = selectedImages,
                    onRemoveImage = { selectedImages = selectedImages - it },
                    onPickImages = { imagePicker.launch(imagePickRequest(maxItems = 3)) },
                    onSend = {
                        vm.sendSimilar(input, selectedImages)
                        input = ""
                        selectedImages = emptyList()
                    },
                    busy = vm.similarBusy,
                    hint = s["similar.hint"],
                    sendLabel = s["solve.send"],
                    imageDesc = s["solve.imageDesc"],
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        }

        // 存错题本：选分类/标签（与解题页同一对话框，保存的是这道同类题）
        if (showSaveDialog) {
            SaveDialog(
                title = s["solve.catPicker.select"],
                categories = categories,
                tags = tags,
                initialSelectedId = null,
                initialNewName = null,
                initialSelectedTagIds = emptyList(),
                suggestedTagNames = emptyList(),
                onConfirm = { name, cid, tagNames, tagIds ->
                    vm.saveSimilarToNotebook(name, cid, tagNames, tagIds)
                    showSaveDialog = false
                },
                onDismiss = { showSaveDialog = false }
            )
        }
        // 删除确认（与解题页一致）
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(s["solve.deleteMessages.title"]) },
                text = { Text(s.format("solve.deleteMessages.text", "n" to "${selectedIndices.size}")) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteSimilarItems(selectedIndices.filter { it !in lockedIndices }.sorted())
                        showDeleteConfirm = false
                        editMode = false
                        selectedIndices = emptySet()
                    }) { Text(s["common.ok"]) }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(s["common.cancel"]) } }
            )
        }
    }
}
