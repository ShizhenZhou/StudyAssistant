package com.zsz.studyassistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.Category
import com.zsz.studyassistant.data.Question
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(nav: NavHostController, vm: MainViewModel) {
    val questions by vm.notebook.collectAsState()
    val categories by vm.categories.collectAsState()
    var filterCategoryId by remember { mutableStateOf<Long?>(null) } // null = 全部
    var showUncategorized by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("错题本（${questions.size}）") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
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

            val filtered = when {
                showUncategorized -> questions.filter { it.categoryId == null }
                filterCategoryId != null -> questions.filter { it.categoryId == filterCategoryId }
                else -> questions
            }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            showUncategorized -> "还没有「未分类」的错题"
                            filterCategoryId != null -> "该分类下暂无错题"
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
                        NotebookItem(q, onClick = {
                            vm.loadQuestion(q)
                            nav.navigate("solve")
                        })
                    }
                }
            }
        }
    }
}

/** 错题条目（双列瀑布流卡片）：图片按比例显示（错开），无图显示文字，单击进入详情页 */
@Composable
private fun NotebookItem(q: Question, onClick: () -> Unit) {
    val bitmap = q.imageBytes?.let {
        try { BitmapFactory.decodeByteArray(it, 0, it.size) } catch (e: Exception) { null }
    }
    Card(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
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
        }
    }
}
