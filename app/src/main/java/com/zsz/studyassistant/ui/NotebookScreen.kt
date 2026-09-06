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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Card
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotebookScreen(nav: NavHostController, vm: MainViewModel) {
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

    val filtered = when {
        showUncategorized -> questions.filter { it.categoryId == null }
        filterCategoryId != null -> questions.filter { it.categoryId == filterCategoryId }
        else -> questions
    }.filter { q ->
        // 按选中的 tag 多选（命中任一即显示）
        filterTagIds.isEmpty() || (qTagIds[q.id] ?: emptyList()).any { it in filterTagIds }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectionMode) "已选 ${selectedIds.size} 项" else "错题本（${questions.size}）") },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { exitSelection() }) { Text("✕", fontSize = 18.sp) }
                    } else {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(onClick = { showBatchCategory = true }) { Text("📁 分类") }
                        TextButton(onClick = { showBatchDelete = true }) { Text("🗑 删除") }
                        TextButton(onClick = { exitSelection() }) { Text("✓ 完成") }
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
                            label = { Text("全部") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = showUncategorized,
                            onClick = { showUncategorized = true; filterCategoryId = null },
                            label = { Text("未分类") }
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
                // 标签多选筛选
                if (tags.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                "标签",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        items(tags, key = { it.id }) { tag ->
                            FilterChip(
                                selected = filterTagIds.contains(tag.id),
                                onClick = {
                                    filterTagIds = if (filterTagIds.contains(tag.id)) filterTagIds - tag.id else filterTagIds + tag.id
                                },
                                label = { Text(tag.name) }
                            )
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            showUncategorized -> "还没有「未分类」的错题"
                            filterCategoryId != null -> "该分类下暂无错题"
                            filterTagIds.isNotEmpty() -> "该标签下暂无错题"
                            else -> "还没有错题，去拍照搜题吧 📷"
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
            title = "批量设置分类",
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
            title = { Text("彻底删除") },
            text = { Text("确定要彻底删除选中的 ${selectedIds.size} 项吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.batchDelete(selectedIds.toList())
                    showBatchDelete = false
                    exitSelection()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showBatchDelete = false }) { Text("取消") } }
        )
    }
}

/** 错题条目（双列瀑布流卡片）：图片按比例显示（错开），无图显示文字；支持多选勾选 + 显示知识点标签 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookItem(
    q: Question,
    tags: List<Tag>,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bitmap = q.imageBytes?.let {
        try { BitmapFactory.decodeByteArray(it, 0, it.size) } catch (e: Exception) { null }
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
                        contentDescription = "错题原图",
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
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(q.createdAt)),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall
                )
                if (tags.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.take(3).forEach { t ->
                            Text(
                                t.name,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
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
