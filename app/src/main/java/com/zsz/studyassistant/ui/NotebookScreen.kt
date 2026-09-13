package com.zsz.studyassistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.Category
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
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchCategory by remember { mutableStateOf(false) }
    var showBatchDelete by remember { mutableStateOf(false) }

    fun exitSelection() { selectionMode = false; selectedIds = emptySet() }

    // 每题 → 其 tagId 列表；tagId → Tag
    val qTagIds = remember(questionTags) { questionTags.groupBy { it.questionId }.mapValues { e -> e.value.map { it.tagId } } }
    val tagById = remember(tags) { tags.associateBy { it.id } }

    // 筛选结果缓存，避免每次重组都重新过滤
    val filtered = remember(questions, showUncategorized, filterCategoryId, filterTagIds, qTagIds) {
        when {
            showUncategorized -> questions.filter { it.categoryId == null }
            filterCategoryId != null -> questions.filter { it.categoryId == filterCategoryId }
            else -> questions
        }.filter { q ->
            // 按选中的 tag 多选（命中任一即显示）
            filterTagIds.isEmpty() || (qTagIds[q.id] ?: emptyList()).any { it in filterTagIds }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectionMode) s.format("notebook.selectedCount", "n" to "${selectedIds.size}") else s.format("notebook.title", "n" to "${questions.size}")) },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { exitSelection() }) { Text("✕", fontSize = 18.sp) }
                    } else {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s["common.back"])
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(onClick = { showBatchCategory = true }) { Text(s["notebook.category"]) }
                        TextButton(onClick = { showBatchDelete = true }) { Text(s["solve.delete"]) }
                        TextButton(onClick = { exitSelection() }) { Text(s["solve.done"]) }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!selectionMode) {
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
                // 标签筛选：选择框「按标签搜索」，点开后才出现下拉选择（纵向可滑动，可多选）
                if (tags.isNotEmpty()) {
                    var tagMenu by remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Button(onClick = { tagMenu = true }) {
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
                        q.text.replace('\n', ' '),
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
