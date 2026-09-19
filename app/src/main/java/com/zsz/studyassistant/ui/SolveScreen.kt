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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.delay
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
    val s = LocalStrings.current
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
    // 复习模式：默认先只给题目，自己回忆后再展开解答
    var answerRevealed by remember { mutableStateOf(false) }
    LaunchedEffect(vm.savedQuestionId) { answerRevealed = false }

    // 系统相册（开启**有序选择**）：选 1~3 张附到追问消息里，最多保留 3 张（返回顺序 = 勾选顺序）
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(3)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newOnes = uris.take(3).mapNotNull { uriToCompressedBytes(context, it) }
            selectedImages = (selectedImages + newOnes).take(3)
        }
    }

    // 退出本页时：若已加入错题本，把当前完整对话更新保存
    DisposableEffect(Unit) {
        onDispose { vm.saveSessionOnExit() }
    }

    // 一键回到顶部 / 滚到底部：每次自增向 WebView 发一次信号
    var scrollTopTick by remember { mutableIntStateOf(0) }
    var scrollBottomTick by remember { mutableIntStateOf(0) }
    // 会话是否已在顶部（网页上报）→ 按钮显示 ↓
    var atTop by remember { mutableStateOf(true) }
    // 「看解答」不动画面、「收起解答」回顶
    var noFollowTick by remember { mutableIntStateOf(0) }
    // 会话内容是否超过一屏：没超过就不显示 ↑/↓ 按钮
    var scrollable by remember { mutableStateOf(false) }
    // 换题（复习上一题→下一题、错题本切换）时立刻回到顶部
    var resetScrollTick by remember { mutableIntStateOf(0) }

    // 复习切题：先做一次「清屏」（人眼可见地提示换了题），同时折叠答案、回到顶部
    var switching by remember { mutableStateOf(false) }
    LaunchedEffect(vm.reviewMode, vm.reviewDone) {
        if (vm.reviewMode) {
            answerRevealed = false      // 切换题目时自动把答案折叠回去
            resetScrollTick++           // 新题从顶部开始
            switching = true
            delay(320)                  // 约 0.3s 的空白，明确让人看到“换题了”
            switching = false
        }
    }

    // 复习：题目切换（reviewDone 变化或换了题）→ 页面回到顶端
    // 只在复习模式生效：否则「存错题本」等操作改了 savedQuestionId 也会误触发回顶
    LaunchedEffect(vm.reviewMode, vm.reviewDone, vm.savedQuestionId) {
        if (vm.reviewMode) resetScrollTick++
    }

    // 对话消息：题目/我的提问统一作为浅绿色用户气泡（附图来自 questionImages），与后续问答一致
    // 拍照题：气泡只显示原图，不显示 AI 转译题干（题干照旧保存，供错题本/搜索/编辑用）
    // 复习模式且未展开解答时：只渲染题目气泡（先想再看）
    // 注意：streamInterrupted 也必须作为 key——中止时 streamingText 可能没变（节流窗口内），
    // 少了这个 key 这段就不会重算，末尾的蓝色「继续生成」永远不出现（曾踩过）。
    val messages = remember(vm.chatItems, vm.questionImages, vm.questionFromPhoto, vm.reviewMode, answerRevealed, vm.streamingText, vm.streamInterrupted) {
        var items = if (vm.reviewMode && !answerRevealed) vm.chatItems.filter { it.role == "question" } else vm.chatItems
        // ★ 生成中：题干/问答还没进 chatItems 时，先把"题目气泡"补上——
        //   否则拍题/批改/图文提问从发起到首字出现这段时间里，页面上看不到自己刚发的图与题干
        if (items.none { it.role == "question" } && vm.questionImages.isNotEmpty()) {
            items = listOf(com.zsz.studyassistant.ChatItem(-1L, "question", "", vm.questionImages)) + items
        }
        val mapped = items.map { c ->
            val imgs = if (c.role == "question") {
                // 题目/首次提问：优先用 questionImages；重开会话时回退到已保存的附图
                vm.questionImages.ifEmpty { c.images ?: emptyList() }
            } else {
                c.images ?: emptyList()
            }
            val hideAiText = c.role == "question" && vm.questionFromPhoto && imgs.isNotEmpty()
            ChatMsg(
                role = if (c.role == "assistant") "assistant" else "user",
                content = if (hideAiText) "" else c.content,
                images = imgs
            )
        }
        // 流式：把已生成的部分作为一条「正在生成」的助手气泡实时渲染（末尾光标提示未完成）
        val live = vm.streamingText
        when {
            live == null -> mapped
            // 被中断（网络异常 / 用户中止）：在已生成内容末尾补一行可点的蓝色「继续生成」
            // 注意要排在「空内容」之前：思考期就被中止时，一个字都没有 → 显示「思考中…」+ 继续生成，气泡不消失
            vm.streamInterrupted -> mapped + ChatMsg(
                role = "assistant",
                content = (if (live.isEmpty()) s["solve.thinking"] else live) + "\n\n[[CONTINUE|" + s["solve.continue"] + "]]",
                images = emptyList()
            )
            live.isEmpty() -> mapped + ChatMsg(role = "assistant", content = s["solve.thinking"], images = emptyList())
            else -> mapped + ChatMsg(role = "assistant", content = live + "\n\n▍", images = emptyList())
        }
    }

    // 多选时禁止删除：题干（第一条 question）与 AI 的第一条回复（答案 / 批改结果）
    // 批改模式下「我的手写作答」也在第一条 question 气泡里，因此一并受保护
    val lockedIndices = remember(vm.chatItems) {
        buildSet {
            vm.chatItems.indexOfFirst { it.role == "question" }.takeIf { it >= 0 }?.let { add(it) }
            vm.chatItems.indexOfFirst { it.role == "assistant" }.takeIf { it >= 0 }?.let { add(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (editMode) {
                        // 多选态：左侧「全选 / 取消全选」+ 已选条数（删除/完成仍在右侧）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val selectable = vm.chatItems.indices.filter { it !in lockedIndices }
                            val allSelected = selectable.isNotEmpty() && selectedIndices.size == selectable.size
                            TextButton(
                                onClick = {
                                    selectedIndices = if (allSelected) emptySet() else vm.chatItems.indices.filter { it !in lockedIndices }.toSet()
                                },
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
                    } else if (vm.gradeMode) {
                        // 批改模式：全屏复用解题界面，标题「批改」
                        Text(
                            s["solve.gradeTitle"],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (vm.reviewMode) {
                        // 复习：标题显示「复习 当前/总数」（总数 = 打开复习时今日剩余的错题数）
                        Text(
                            s.format(
                                "review.titleProgress",
                                "i" to "${vm.reviewDone + 1}",
                                "n" to "${vm.reviewTotal}"
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (vm.isFromNotebook) {
                        // 错题页标题：与「解题」「错题本」统一用大字号（TopAppBar 默认）
                        Text(
                            s["solve.mistakeTitle"],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            s["solve.title"],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(min = 40.dp)
                        )
                    }
                },
                navigationIcon = {
                    if (editMode) {
                        TextButton(onClick = { editMode = false; selectedIndices = emptySet() }) { Text("✕", fontSize = 18.sp) }
                    } else {
                        TextButton(onClick = {
                            if (vm.isFromNotebook || vm.gradeMode) {
                                // 错题本 / 批改：返回上一页
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
                    val smallPad = PaddingValues(horizontal = 6.dp)
                    if (vm.reviewMode) {
                        TextButton(onClick = { vm.startSimilar(); nav.navigate("similar") }, contentPadding = smallPad) { Text(s["solve.practiceSimilar"], fontSize = 13.sp) }
                    } else if (editMode) {
                        TextButton(onClick = { showEditDelete = true }, enabled = selectedIndices.isNotEmpty(), contentPadding = smallPad) { Text(s["solve.delete"], fontSize = 13.sp) }
                        TextButton(onClick = { editMode = false; selectedIndices = emptySet() }, contentPadding = smallPad) { Text(s["solve.done"], fontSize = 13.sp) }
                    } else {
                        // ★ 统一三段式（拍题 / 批改 / 图文提问 / 错题本 完全一致）：
                        //   右侧 = [⏸ 中止 或 🔄 重新生成] 紧挨 [📚 存错题本 或 📁 分类]
                        //   （不再区分「中止批改 / 重新批改」，也不再单独放删除按钮）
                        if (vm.busy) {
                            TextButton(onClick = { vm.abortGeneration() }, contentPadding = smallPad) {
                                Text(s["solve.abort"], fontSize = 13.sp)
                            }
                        } else {
                            TextButton(
                                onClick = { if (vm.gradeMode) vm.regrade() else vm.regenerate() },
                                // ★ 中止后 chatItems 可能还是空的（题干/答案要流式结束才进），
                                //   但仍有流式气泡/已中断状态 → 「重新生成」必须可用
                                enabled = vm.chatItems.isNotEmpty() ||
                                    vm.streamInterrupted || vm.streamingText != null,
                                contentPadding = smallPad
                            ) { Text(s["solve.regen"], fontSize = 13.sp) }
                        }
                        if (vm.isDeleted) {
                            // 已删除状态：保留「恢复」入口（删除入口已移入分类对话框）
                            TextButton(onClick = { vm.restoreSavedQuestion() }, contentPadding = smallPad) {
                                Text(s["solve.restore"], fontSize = 13.sp)
                            }
                        } else if (vm.savedToNotebook || vm.savedQuestionId != null) {
                            // 已存入错题本 → 与错题本界面同一个「分类」按钮
                            val curCat = categories.firstOrNull { it.id == vm.currentQuestionCategoryId }
                            TextButton(onClick = { categoryDialogFor = "change" }, contentPadding = smallPad) {
                                val catName = curCat?.name ?: s["solve.noCategory"]
                                Text(
                                    if (curCat != null) s.format("solve.category", "name" to catName) else s["solve.category.none"],
                                    fontSize = when {
                                        catName.length <= 3 -> 12.sp
                                        catName.length <= 5 -> 11.sp
                                        else -> 10.sp
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            TextButton(
                                onClick = { vm.ensureClassification(); categoryDialogFor = "save" },
                                enabled = vm.chatItems.isNotEmpty() && !vm.busy,
                                contentPadding = smallPad
                            ) { Text(s["solve.saveToNotebook"], fontSize = 13.sp) }
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
                title = { Text(s["solve.deleteConfirm.title"]) },
                text = { Text(s["solve.deleteConfirm.text"]) },
                confirmButton = {
                    TextButton(onClick = {
                        // 从错题本移除（硬删除）：右上角按钮随即变回「📚 存错题本」，界面留在当前题目会话
                        vm.unsaveFromNotebook()
                        showDeleteConfirm = false
                    }) { Text(s["common.delete"], color = Color(0xFFE53935)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text(s["common.cancel"]) }
                }
            )
        }
        // 编辑模式：删除选中消息确认
        if (showEditDelete) {
            AlertDialog(
                onDismissRequest = { showEditDelete = false },
                title = { Text(s["solve.deleteMessages.title"]) },
                text = { Text(s.format("solve.deleteMessages.text", "n" to "${selectedIndices.size}")) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteMessages(selectedIndices.filter { it !in lockedIndices })
                        showEditDelete = false
                        editMode = false
                        selectedIndices = emptySet()
                    }) { Text(s["common.delete"]) }
                },
                dismissButton = { TextButton(onClick = { showEditDelete = false }) { Text(s["common.cancel"]) } }
            )
        }
        // 分类 + 标签选择对话框（存题 / 详情页改）
        when (categoryDialogFor) {
            "save" -> {
                val defaultCat = vm.suggestedCategory
                val initCatId = categories.firstOrNull { it.name == defaultCat }?.id
                // 打开保存对话框时若还没有分类建议 → 后台兜底问一次（C）
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    vm.ensureClassification()
                }
                androidx.compose.runtime.key(vm.suggestedCategory, vm.suggestedTags) {
                SaveDialog(
                    title = s["solve.catPicker.select"],
                    categories = categories,
                    tags = tags,
                    initialSelectedId = initCatId,
                    initialNewName = if (initCatId == null) defaultCat else null,
                    // 默认勾选 AI 建议的知识点标签（与已有标签同名者）
                    initialSelectedTagIds = tags.filter { tg ->
                        vm.suggestedTags.any { it.trim() == tg.name.trim() }
                    }.map { it.id },
                    suggestedTagNames = vm.suggestedTags,
                    onConfirm = { name, cid, tagNames, tagIds ->
                        vm.saveToNotebook(name, cid, tagNames, tagIds)
                        categoryDialogFor = null
                    },
                    onDismiss = { categoryDialogFor = null }
                )
                }
            }
            "change" -> {
                SaveDialog(
                    title = s["solve.catPicker.edit"],
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
                    onDismiss = { categoryDialogFor = null },
                    // 分类对话框左下角的红色删除 → 弹确认框
                    onDelete = { categoryDialogFor = null; showDeleteConfirm = true }
                )
            }
        }
        Column(Modifier.fillMaxSize().imePadding().padding(padding)) {
            if (!hasKey) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s["solve.noKey"], color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { nav.navigate("settings") }) { Text(s["solve.goSettings"]) }
                    }
                }
            }

            vm.error?.let { err ->
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(err, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.clearError() }) { Text(s["solve.gotIt"]) }
                    }
                }
            }

            // 网络意外断开 → 蓝色下划线"继续生成"，点击后用最后提问内容重新生成
            if (vm.networkError) {
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s["solve.networkLost"], style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.retry() }) {
                            Text(
                                s["solve.continueGen"],
                                color = Color(0xFF2196F3),
                                textDecoration = TextDecoration.Underline
                            )
                        }
                    }
                }
            }

            // 题目不再单独折叠显示：原题/我的提问已作为浅绿用户气泡显示在对话里

            // 多选不再切到缩略列表：仍在原消息界面上操作（气泡右上角圆圈 + 选中红框）
            // 生成中即使一条消息都还没有，也走 WebView —— 由「流式气泡」显示「思考中…」（用户要求：无内容时要有气泡）
            // ★ 另外：**被中止**时（busy=false 但 streamingText 还在）也必须走 WebView，
            //   否则会掉进"正在等待题目…"占位页，把「思考中… + 蓝色继续生成」的气泡弄丢（曾回归）
            val hasLiveBubble = vm.streamingText != null
            if (switching || (vm.chatItems.isEmpty() && !vm.busy && !hasLiveBubble)) {
                if (switching) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            s["review.nextQuestion"],
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    // ★ 空会话：直接给大输入框（图文提问的入口）——
                    //   省掉"先进入独立输入页再跳转"的一跳，三种模式从此共用同一页
                    Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                        Text(
                            s["ask.title"],
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = followUp,
                            onValueChange = { followUp = it },
                            placeholder = { Text(s["ask.hint"]) },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            maxLines = 12
                        )
                        if (selectedImages.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                s.format("solve.imagesCount", "n" to "${selectedImages.size}"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        FormulaBar(onInsert = { followUp = followUp + it })
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { imagePicker.launch(imagePickRequest(maxItems = 3)) }) {
                                Text(s["ask.addImage"])
                            }
                            Button(
                                onClick = {
                                    vm.solveDirect(followUp.trim(), selectedImages)
                                    followUp = ""
                                    selectedImages = emptyList()
                                },
                                enabled = followUp.isNotBlank() || selectedImages.isNotEmpty(),
                                modifier = Modifier.height(52.dp)
                            ) { Text(s["solve.send"]) }
                        }
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ConversationWebView(
                        messages = messages,
                        scrollTopSignal = scrollTopTick,
                        onContinue = { vm.continueGeneration() },
                        // 长按气泡 → 进入多选并选中该条（多选态下长按无效：只允许单次点击选择，JS 侧已拦截，这里再兜一层）
                        // 受保护的消息（题干 / AI 首条回复）：从普通态长按进入多选时**不选中它**（显示灰色虚线圈）
                        onLongPressMessage = { idx ->
                            if (!editMode && !vm.reviewMode && !vm.busy && idx in vm.chatItems.indices) {
                                if (idx in lockedIndices) {
                                    if (!editMode) {
                                        editMode = true
                                        selectedIndices = emptySet()
                                    }
                                } else if (editMode) {
                                    selectedIndices = if (selectedIndices.contains(idx)) selectedIndices - idx else selectedIndices + idx
                                } else {
                                    editMode = true
                                    selectedIndices = setOf(idx)
                                }
                            }
                        },
                        // 多选态：气泡右上角圆圈 + 选中红框；点气泡切换选中
                        selectionMode = editMode,
                        selectedIndices = selectedIndices,
                        lockedIndices = lockedIndices,
                        onToggleSelect = { idx ->
                            if (idx in vm.chatItems.indices && idx !in lockedIndices) {
                                selectedIndices = if (selectedIndices.contains(idx)) selectedIndices - idx else selectedIndices + idx
                            }
                        },
                        scrollBottomSignal = scrollBottomTick,
                        resetScrollSignal = resetScrollTick,
                        noFollowSignal = noFollowTick,
                        onAtTopChange = { atTop = it },
                        onScrollableChange = { scrollable = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp)
                    )
                    // 快速跳转按钮：仅在**内容超过一屏**时出现；在顶部显示 ↓（一按滚到最底），否则 ↑（一按回到顶部）
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
                                Text(
                                    if (atTop) "↓" else "↑",
                                    fontSize = 19.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
                if (vm.busy) {
                    Text(
                        s["solve.generating"],
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 复习模式：进度 + 折叠解答；底部为 熟悉/模糊/忘记 三按钮
            if (vm.reviewMode) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 进度条（替代原来的「第 x/n 题」文字）：已完成比例 = 当前题号 / 总数
                    val total = vm.reviewTotal.coerceAtLeast(1)
                    val cur = (vm.reviewDone + 1).coerceIn(0, total)
                    LinearProgressIndicator(
                        progress = { cur.toFloat() / total.toFloat() },
                        modifier = Modifier.weight(1f).height(8.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "$cur/$total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = {
                        if (answerRevealed) {
                            resetScrollTick++          // 收起解答 → 回到顶部
                            answerRevealed = false
                        } else {
                            noFollowTick++             // 看解答 → 画面保持不动
                            answerRevealed = true
                        }
                    }) {
                        Text(if (answerRevealed) s["review.hideAnswer"] else s["review.showAnswer"])
                    }
                }
                val onNoMore: () -> Unit = {
                    vm.exitReviewMode()
                    nav.popBackStack()
                    android.widget.Toast.makeText(context, s["solve.reviewDone"], android.widget.Toast.LENGTH_SHORT).show()
                }
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { vm.reviewNext(2, onNoMore) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) { Text(s["solve.familiar"]) }
                    Button(
                        onClick = { vm.reviewNext(1, onNoMore) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300))
                    ) { Text(s["solve.vague"]) }
                    Button(
                        onClick = { vm.reviewNext(0, onNoMore) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    ) { Text(s["solve.forgot"]) }
                }
            } else if (vm.chatItems.isNotEmpty() || vm.busy) {
                // 输入栏（与同类题页共用组件：附图 + 公式键盘 + 图库 + 发送）
                // ★ 生成中也显示：否则拍题后到首字出现之前，整条输入栏会被"答案生成中…"顶掉
                ConversationInputBar(
                    value = followUp,
                    onValueChange = { followUp = it },
                    images = selectedImages,
                    onRemoveImage = { selectedImages = selectedImages - it },
                    onPickImages = { imagePicker.launch(imagePickRequest(maxItems = 3)) },
                    onSend = {
                        vm.sendFollowUp(followUp.trim(), selectedImages)
                        followUp = ""
                        selectedImages = emptyList()
                    },
                    busy = vm.busy,
                    hint = s["solve.followUpHint"],
                    sendLabel = s["solve.send"],
                    imageDesc = s["solve.imageDesc"]
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
    val s = LocalStrings.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(s["solve.cat.choose"], style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                FilterChip(
                    selected = !newMode && selId == null,
                    onClick = { selId = null; newMode = false },
                    label = { Text(s["solve.noCategory"]) }
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
                    label = { Text(s["solve.cat.new"]) }
                )
                if (newMode) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(s["solve.cat.name"]) },
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
            }) { Text(s["common.save"]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s["common.cancel"]) }
        }
    )
}

/** 编辑（多选删除）模式下的一条消息 */
@Composable
private fun EditMsgRow(item: ChatItem, selected: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
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
                Text(s["solve.textImageMark"], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
    onDismiss: () -> Unit,
    /** 非空时在对话框左下角显示红色「🗑 删除」（分类场景用） */
    onDelete: (() -> Unit)? = null
) {
    // 分类状态
    var selCatId by remember { mutableStateOf(initialSelectedId) }
    var newCatMode by remember { mutableStateOf(!initialNewName.isNullOrBlank()) }
    var newCatName by remember { mutableStateOf(initialNewName ?: "") }
    val s = LocalStrings.current

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
                Text(s["solve.cat.name"], style = MaterialTheme.typography.labelMedium)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !newCatMode && selCatId == null, onClick = { selCatId = null; newCatMode = false }, label = { Text(s["solve.noCategory"]) })
                }
                if (categories.isNotEmpty()) {
                    LazyRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories, key = { it.id }) { c ->
                            FilterChip(selected = !newCatMode && selCatId == c.id, onClick = { selCatId = c.id; newCatMode = false }, label = { Text(c.name) })
                        }
                    }
                }
                FilterChip(selected = newCatMode, onClick = { newCatMode = true }, label = { Text(s["solve.cat.new"]) })
                if (newCatMode) {
                    OutlinedTextField(value = newCatName, onValueChange = { newCatName = it }, label = { Text(s["solve.cat.name"]) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }

                Spacer(Modifier.height(14.dp))
                Text(s["solve.tags"], style = MaterialTheme.typography.labelMedium)
                // 下拉菜单选择已有标签（标签多时可上下滑动）
                var tagMenu by remember { mutableStateOf(false) }
                Box {
                    Button(onClick = { tagMenu = true }, modifier = Modifier.padding(top = 6.dp)) {
                        Text(s.format("solve.tags.selected", "n" to "${selectedNames.size}"))
                    }
                    DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                        if (tags.isEmpty()) {
                            DropdownMenuItem(text = { Text(s["solve.tags.empty"]) }, onClick = { tagMenu = false })
                        } else {
                            tags.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.name) },
                                    onClick = { toggleTagName(t.name) },
                                    trailingIcon = { if (selectedNames.contains(t.name)) Text("✓") }
                                )
                            }
                        }
                    }
                }
                // 已选（含 AI 自动预选、新建），点按可移除
                if (selectedNames.isNotEmpty()) {
                    LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(selectedNames.toList(), key = { it }) { name ->
                            FilterChip(selected = true, onClick = { toggleTagName(name) }, label = { Text(name) })
                        }
                    }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(s["solve.tags.new"]) },
                        singleLine = true,
                        placeholder = { Text(s["solve.tags.newHint"]) }
                    )
                    TextButton(onClick = {
                        val n = newTag.trim()
                        if (n.isNotEmpty() && n !in selectedNames && selectedNames.size < 5) { toggleTagName(n); newTag = "" }
                    }) { Text(s["solve.tags.add"]) }
                }
            }
        },
        confirmButton = {
            // 有删除时：取消放到右侧紧挨"保存"，删除单独占最左
            if (onDelete != null) {
                TextButton(onClick = onDismiss) { Text(s["common.cancel"]) }
            }
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
            }) { Text(s["common.save"]) }
        },
        // 有删除时：删除单独占最左，取消移到右侧紧挨"保存"（保存/取消位置与原来一致）
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = { onDelete() }) {
                    Text(s["common.delete"], color = Color(0xFFE53935))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(s["common.cancel"]) }
            }
        }
    )
}


/** 在已有分类里找最相近的：完全相等 → 包含关系 → 共有 2 个以上相同字（优先已有科目，避免重复新建） */
private fun bestCategoryMatch(categories: List<Category>, name: String?): Category? {
    if (name.isNullOrBlank()) return null
    val n = name.trim()
    categories.firstOrNull { it.name.trim() == n }?.let { return it }
    categories.firstOrNull { it.name.contains(n) || n.contains(it.name.trim()) }?.let { return it }
    return categories
        .map { it to it.name.trim().count { ch -> n.contains(ch) } }
        .filter { it.second >= 2 }
        .maxByOrNull { it.second }?.first
}
