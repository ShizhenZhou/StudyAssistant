package com.zsz.studyassistant.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.Category
import com.zsz.studyassistant.data.NotebookSortMode
import com.zsz.studyassistant.data.Question
import com.zsz.studyassistant.data.QuestionTag
import com.zsz.studyassistant.data.Tag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 复用同一个格式化器，避免列表每项每次重组都新建 SimpleDateFormat */
private val TIME_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotebookScreen(nav: NavHostController, vm: MainViewModel) {
    val s = LocalStrings.current
    val questions by vm.notebook.collectAsState()
    val categories by vm.categories.collectAsState()
    val tags by vm.tags.collectAsState()
    val questionTags by vm.questionTags.collectAsState()
    var filterCategoryId by remember { mutableStateOf<Long?>(null) } // null = 全部
    var showUncategorized by remember { mutableStateOf(false) }
    var filterTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val sortMode by vm.notebookSort.collectAsState()
    // 列表滚动状态：切换排序后**主动回到顶部**。
    // 不这样做的话，LazyVerticalStaggeredGrid 会按"第一个可见项的 key"锚定滚动位置：
    // 你在顶部时第一个可见项是"最近添加"的那道题，切换成"最早添加"后它跑到列表末尾 →
    // 视图跟着它跳到**底部**（用户实测反馈的 bug）。
    val gridState = rememberLazyStaggeredGridState()
    LaunchedEffect(sortMode) { runCatching { gridState.scrollToItem(0) } }
    var page by remember { mutableStateOf("list") }   // list / manage（科目管理）
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchCategory by remember { mutableStateOf(false) }
    var showBatchDelete by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun closeSearch() {
        searchOpen = false
        keyboard?.hide()
    }

    // 返回键：科目管理 → 回列表；搜索打开 → 先关搜索
    BackHandler(enabled = page != "list" || searchOpen) {
        if (page != "list") { page = "list"; searchOpen = false } else closeSearch()
    }

    fun exitSelection() { selectionMode = false; selectedIds = emptySet() }

    // 每题 → 其 tagId 列表；tagId → Tag
    val qTagIds = remember(questionTags) { questionTags.groupBy { it.questionId }.mapValues { e -> e.value.map { it.tagId } } }
    val tagById = remember(tags) { tags.associateBy { it.id } }

    // 筛选结果缓存，避免每次重组都重新过滤
    val filtered = remember(questions, showUncategorized, filterCategoryId, filterTagIds, qTagIds, query) {
        when {
            showUncategorized -> questions.filter { it.categoryId == null }
            filterCategoryId != null -> questions.filter { it.categoryId == filterCategoryId }
            else -> questions
        }.filter { q ->
            // 按选中的 tag 多选（命中任一即显示）
            filterTagIds.isEmpty() || (qTagIds[q.id] ?: emptyList()).any { it in filterTagIds }
        }.filter { q ->
            // 全文搜索：题干（AI 转译文本）+ 解答
            val kw = query.trim()
            kw.isEmpty() || q.text.contains(kw, ignoreCase = true) || q.answer.contains(kw, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        // 多选：小字号（与解题页会话多选一致）——左侧「全选/取消全选」+ 已选条数
                        // 全部不换行，避免被右侧按钮挤成两行
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val allSelected = filtered.isNotEmpty() && selectedIds.size == filtered.size
                            TextButton(
                                onClick = { selectedIds = if (allSelected) emptySet() else filtered.map { it.id }.toSet() },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(
                                    if (allSelected) s["solve.deselectAll"] else s["solve.selectAll"],
                                    fontSize = BTN_LABEL,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Text(
                                s.format("notebook.selectedCount", "n" to "${selectedIds.size}"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else {
                        // 页标题：只写「错题本」（题数已挪到「按标签搜索」那一行右侧 → 「当前列表共 N 题」）
                        Text(
                            if (page == "manage") s["catManage.title"] else s["notebook.titlePlain"],
                            fontSize = SUBPAGE_TITLE,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    when {
                        // 多选：✕ 用紧凑 TextButton（IconButton 固定 48dp，会挤占标题空间）
                        selectionMode -> TextButton(
                            onClick = { exitSelection() },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("✕", fontSize = 18.sp) }
                        // 返回箭头也用紧凑 TextButton：宽度只有"箭头 + 4dp"，标题因此更靠近 ←（用户要求）
                        page == "manage" -> TextButton(
                            onClick = { page = "list"; searchOpen = false },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("←", fontSize = SUBPAGE_TITLE) }
                        else -> TextButton(
                            onClick = { nav.popBackStack() },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("←", fontSize = SUBPAGE_TITLE) }
                    }
                },
                actions = {
                    when {
                        page == "manage" -> { /* 管理页无额外操作 */ }
                        selectionMode -> {
                            // 三个按钮紧凑摆放（缩小左右内边距），给标题让出空间
                            val compact = PaddingValues(horizontal = 4.dp)
                            TextButton(onClick = { showBatchCategory = true }, contentPadding = compact) { Text(s["notebook.category"], maxLines = 1, softWrap = false) }
                            TextButton(onClick = { showBatchDelete = true }, contentPadding = compact) { Text(s["solve.delete"], maxLines = 1, softWrap = false) }
                            TextButton(onClick = { exitSelection() }, contentPadding = compact) { Text(s["solve.done"], maxLines = 1, softWrap = false) }
                        }
                        else -> {
                            // 🔍 搜索：点开才出现搜索框，再点别处关闭
                            IconButton(onClick = {
                                if (searchOpen) closeSearch() else searchOpen = true
                            }) { Text("🔍", fontSize = 18.sp) }
                            // ⇅ 排序（A5）：最近添加 / 最早添加 / 下次复习 / 复习次数 / 最不熟
                            Box {
                                TextButton(onClick = { sortMenu = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text(s["notebook.sort"], maxLines = 1, softWrap = false)
                                }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    NotebookSortMode.menuOrder.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(s["notebook.sort.${m.id}"]) },
                                            onClick = { vm.setNotebookSort(m); sortMenu = false },
                                            trailingIcon = { if (m == sortMode) Text("✓") }
                                        )
                                    }
                                }
                            }
                            // 管理：科目（分类）管理
                            TextButton(onClick = { page = "manage"; searchOpen = false }) { Text(s["notebook.manage"]) }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (page == "manage") {
            CategoryManagePage(
                vm = vm,
                categories = categories,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            return@Scaffold
        }
        // 点空白处关闭搜索框（卡片/按钮自己处理点击，不受影响）
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(searchOpen) {
                    if (searchOpen) {
                        detectTapGestures {
                            searchOpen = false
                            keyboard?.hide()
                        }
                    }
                }
        ) {
            if (!selectionMode) {
                // 搜索框：点标题栏 🔍 才出现
                if (searchOpen) {
                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).focusRequester(searchFocus),
                        placeholder = { Text(s["notebook.search"], style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                TextButton(onClick = { query = "" }) { Text("✕") }
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                // 分类筛选 chips（全部 / 未分类 / 各分类）
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = filterCategoryId == null && !showUncategorized,
                            onClick = { filterCategoryId = null; showUncategorized = false },
                            label = { Text(s["notebook.filter.all"]) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = showUncategorized,
                            onClick = { showUncategorized = true; filterCategoryId = null },
                            label = { Text(s["notebook.filter.uncategorized"]) }
                        )
                    }
                    items(categories, key = { it.id }) { c ->
                        FilterChip(
                            selected = filterCategoryId == c.id && !showUncategorized,
                            onClick = { filterCategoryId = c.id; showUncategorized = false },
                            label = { Text(c.name) }
                        )
                    }
                }
                // 标签筛选：「按标签搜索」按钮（有标签才显示）+ **当前列表条数**（始终显示在右侧）
                // 题数从标题挪到这里：它是"筛选后的结果数"，跟筛选控件放一起更直观
                var tagMenu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (tags.isNotEmpty()) {
                        Box {
                            Button(onClick = { tagMenu = true }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                                Text(
                                    if (filterTagIds.isEmpty()) s["notebook.filter.tags"] else s.format("notebook.filter.tagsSelected", "n" to "${filterTagIds.size}")
                                )
                            }
                            DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                                tags.forEach { tag ->
                                    DropdownMenuItem(
                                        text = { Text(tag.name) },
                                        onClick = {
                                            filterTagIds = if (filterTagIds.contains(tag.id)) filterTagIds - tag.id else filterTagIds + tag.id
                                        },
                                        trailingIcon = { if (filterTagIds.contains(tag.id)) Text("✓") }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        s.format("notebook.listCount", "n" to "${filtered.size}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            showUncategorized -> s["notebook.empty.uncategorized"]
                            filterCategoryId != null -> s["notebook.empty.category"]
                            filterTagIds.isNotEmpty() -> s["notebook.empty.tag"]
                            else -> s["notebook.empty.default"]
                        }
                    )
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalItemSpacing = 8.dp
                ) {
                    // 瀑布流：卡片高度随图片比例错开，新→旧 从左到右、从上到下
                    staggeredItems(filtered, key = { it.id }) { q ->
                        NotebookItem(
                            q = q,
                            tags = (qTagIds[q.id] ?: emptyList()).mapNotNull { tagById[it] },
                            selectionMode = selectionMode,
                            selected = selectedIds.contains(q.id),
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = if (selectedIds.contains(q.id)) selectedIds - q.id else selectedIds + q.id
                                } else {
                                    vm.loadQuestion(q)
                                    nav.navigate("solve")
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = selectedIds + q.id
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 批量分类对话框
    if (showBatchCategory) {
        CategoryDialog(
            title = s["notebook.batchCategory"],
            categories = categories,
            initialSelectedId = null,
            initialNewName = null,
            onConfirm = { name, cid ->
                vm.batchSetCategory(selectedIds.toList(), name, cid)
                showBatchCategory = false
                exitSelection()
            },
            onDismiss = { showBatchCategory = false }
        )
    }

    // 批量删除确认框
    if (showBatchDelete) {
        AlertDialog(
            onDismissRequest = { showBatchDelete = false },
            title = { Text(s["notebook.purge.title"]) },
            text = { Text(s.format("notebook.purge.text", "n" to "${selectedIds.size}")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.batchDelete(selectedIds.toList())
                    showBatchDelete = false
                    exitSelection()
                }) { Text(s["common.delete"]) }
            },
            dismissButton = { TextButton(onClick = { showBatchDelete = false }) { Text(s["common.cancel"]) } }
        )
    }
}

/* 错题条目（双列瀑布流卡片）：图片按比例显示（错开），无图显示文字；支持多选勾选 + 显示知识点标签 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun NotebookItem(
    q: Question,
    tags: List<Tag>,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val s = LocalStrings.current
    // 用 remember 缓存 + 降采样解码，避免每次重组都解码全尺寸大图
    val bitmap = remember(q.id, q.imageBytes) {
        q.imageBytes?.let { decodeSampledBitmap(it, 400) }
    }
    Card(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        border = if (selected) BorderStroke(2.dp, Color(0xFF4CAF50)) else null
    ) {
        Box {
            Column(Modifier.padding(8.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = s["notebook.imageDesc"],
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        TextPretty.oneLine(q.text, 110),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    TIME_FMT.format(Date(q.createdAt)),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall
                )
                if (tags.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        tags.take(3).forEach { t ->
                            Text(
                                t.name,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                    .widthIn(max = 110.dp)
                            )
                        }
                    }
                }
            }
            // 多选：右上角勾选框
            if (selectionMode) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(if (selected) Color(0xFF4CAF50) else Color(0x88000000), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Text("✓", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * 科目管理：纵向列表，每行可勾选、可 ✏️ 重命名；底部批量删除。
 * 删除前二次确认，并询问是否连同科目下的错题一起删（不勾选则题目改为「未分类」）。
 */
@Composable
private fun CategoryManagePage(
    vm: MainViewModel,
    categories: List<Category>,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var renaming by remember { mutableStateOf<Category?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    var deleteQuestions by remember { mutableStateOf(false) }

    Column(modifier.padding(12.dp)) {
        Text(
            s["catManage.hint"],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))

        if (categories.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s["catManage.empty"], color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(categories, key = { it.id }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (selected.contains(c.id)) selected - c.id else selected + c.id
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected.contains(c.id),
                            onCheckedChange = {
                                selected = if (selected.contains(c.id)) selected - c.id else selected + c.id
                            }
                        )
                        Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { renaming = c; renameText = c.name }) { Text("✏️") }
                    }
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showDelete = true },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(s.format("catManage.delete", "n" to "${selected.size}"))
            }
        }
    }

    // 重命名
    renaming?.let { c ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(s["catManage.renameTitle"]) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(s["catManage.renameHint"]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameCategory(c.id, renameText)
                    renaming = null
                }) { Text(s["common.save"]) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(s["common.cancel"]) } }
        )
    }

    // 删除二次确认 + 是否连题一起删
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(s["catManage.deleteTitle"]) },
            text = {
                Column {
                    Text(s.format("catManage.deleteText", "n" to "${selected.size}"))
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { deleteQuestions = !deleteQuestions },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = deleteQuestions, onCheckedChange = { deleteQuestions = it })
                        Text(
                            s["catManage.deleteQuestions"],
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteCategories(selected.toList(), deleteQuestions)
                        selected = emptySet()
                        deleteQuestions = false
                        showDelete = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(s["common.ok"]) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false; deleteQuestions = false }) { Text(s["common.cancel"]) }
            }
        )
    }
}
