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
import androidx.compose.runtime.LaunchedEffect
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
    // 「收起答案」时立刻回顶
    var resetScrollTick by remember { mutableIntStateOf(0) }
    // 「查看答案」时只关闭跟随、不动画面；「收起答案」时回顶
    var noFollowTick by remember { mutableIntStateOf(0) }
    var scrollBottomTick by remember { mutableIntStateOf(0) }
    var atTop by remember { mutableStateOf(true) }
    // 会话内容是否超过一屏：没超过就不显示 ↑/↓ 按钮
    var scrollable by remember { mutableStateOf(false) }
    val question = vm.similarQuestion
    val answer = vm.similarAnswer
    val revealed = vm.similarRevealed
    // ⚠️ remember 的键必须包含「出题被中止」与「忙碌」状态，否则中止后不会重算 → 蓝色「继续生成」永远不出现
    val messages = remember(
        vm.similarQuestion,
        vm.similarMessages,
        revealed,
        vm.similarStreamingText,
        vm.similarBusy,
        vm.similarInterrupted
    ) {
        buildList {
            // 出题中/被中止：流式文本就是题目本身（只含题目部分，答案不外显）；出题完成后用正式题目
            val qText = vm.similarQuestion ?: vm.similarStreamingText
            val qBase = qText ?: if (vm.similarBusy || vm.similarInterrupted) s["solve.thinking"] else null
            if (qBase != null) {
                // 出题被中止：气泡末尾补蓝色「继续生成」（点它 = 重新出题），否则用户无从继续
                val content = if (vm.similarInterrupted) {
                    qBase + "\n\n[[CONTINUE|" + s["solve.continue"] + "]]"
                } else {
                    qBase
                }
                add(ChatMsg("assistant", content))
            }
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
                                Text(if (allSelected) s["solve.deselectAll"] else s["solve.selectAll"], fontSize = BTN_LABEL)
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
                        Text(s["similar.title"], fontSize = SUBPAGE_TITLE, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    // 内边距收紧 → 标题离 ← 更近（内部页面统一处理）
                    val pad = PaddingValues(horizontal = 4.dp)
                    if (editMode) {
                        TextButton(onClick = { editMode = false; selectedIndices = emptySet() }, contentPadding = pad) { Text("✕") }
                    } else {
                        TextButton(onClick = { nav.popBackStack() }, contentPadding = pad) { Text("←", fontSize = SUBPAGE_TITLE) }
                    }
                },
                actions = {
                    // 紧凑右对齐：只放「重新生成 / 中止生成」+「存错题本」（查看答案已移到左下角椭圆按钮）
                    val smallPad = PaddingValues(horizontal = 4.dp)
                    if (editMode) {
                        TextButton(onClick = { showDeleteConfirm = true }, enabled = selectedIndices.isNotEmpty(), contentPadding = smallPad) { Text(s["solve.delete"], fontSize = BTN_LABEL) }
                        TextButton(onClick = { editMode = false; selectedIndices = emptySet() }, contentPadding = smallPad) { Text(s["solve.done"], fontSize = BTN_LABEL) }
                    } else {
                        // 生成中：⏸ 中止生成；空闲：🔄 重新生成（清空会话并重出一道题）
                        val questionShown = vm.similarQuestion != null || vm.similarStreamingText != null
                        if (vm.similarBusy) {
                            TextButton(onClick = { vm.abortSimilar() }, contentPadding = smallPad) {
                                Text(s["solve.abort"], fontSize = BTN_LABEL, maxLines = 1, softWrap = false)
                            }
                        } else if (questionShown || vm.similarInterrupted) {
                            // 出题完成、或被中止 → 都可「重新生成」（清空会话重出一题）
                            TextButton(onClick = { vm.startSimilar() }, contentPadding = smallPad) {
                                Text(s["solve.regen"], fontSize = BTN_LABEL, maxLines = 1, softWrap = false)
                            }
                        }
                        // 存错题本（逻辑同解题页：选分类/标签后保存；已存再点 = 取消保存）
                        val saved = vm.similarSavedQuestionId != null
                        TextButton(
                            onClick = {
                                if (saved) vm.unsaveSimilarFromNotebook() else showSaveDialog = true
                            },
                            enabled = questionShown,
                            contentPadding = smallPad
                        ) {
                            val label = if (saved) s["solve.savedToNotebook"] else s["solve.saveToNotebook"]
                            if (saved) Text(label, color = Color(0xFF4CAF50), fontSize = BTN_LABEL, maxLines = 1, softWrap = false)
                            else Text(label, fontSize = BTN_LABEL, maxLines = 1, softWrap = false)
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
                        resetScrollSignal = resetScrollTick,
                        noFollowSignal = noFollowTick,
                        onAtTopChange = { atTop = it },
                        onScrollableChange = { scrollable = it },
                        // 气泡里的蓝色「继续生成」（出题被中止时）→ 重新出题
                        onContinue = { vm.continueSimilar() },
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
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        // 深色主题：气泡/正文/思考块配色由网页内 CSS 变量切换
                        dark = rememberDarkTheme(vm.theme),
                        // C14 字号档位：网页内文字同步缩放
                        fontScale = vm.fontScale.factor
                    )
                    // 左下角椭圆按钮：查看答案 / 收起答案（答案未生成好时置灰）
                    if (!editMode && (question != null || vm.similarStreamingText != null || revealed)) {
                        Surface(
                            onClick = {
                                if (revealed) {
                                    // 收起解答：回到顶部
                                    resetScrollTick++
                                    vm.hideSimilarAnswer()
                                } else {
                                    // 查看答案：画面保持不动（只关闭自动跟随）
                                    noFollowTick++
                                    vm.revealSimilarAnswer()
                                }
                            },
                            enabled = revealed || answer != null,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f),
                            shadowElevation = 3.dp,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 12.dp, bottom = 12.dp)
                        ) {
                            Text(
                                if (revealed) s["review.hideAnswer"] else s["similar.showAnswer"],
                                fontSize = BTN_LABEL,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                            )
                        }
                    }
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
            // B9 查重：同类题也可能与已存的题重复（打开对话框时先查一次）
            var dupOf by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(vm.similarQuestion) {
                dupOf = vm.similarQuestion?.let { vm.findDuplicateQuestion(it)?.text }
            }
            SaveDialog(
                title = s["solve.catPicker.select"],
                categories = categories,
                tags = tags,
                initialSelectedId = null,
                initialNewName = null,
                initialSelectedTagIds = emptyList(),
                suggestedTagNames = emptyList(),
                duplicateOf = dupOf,
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
