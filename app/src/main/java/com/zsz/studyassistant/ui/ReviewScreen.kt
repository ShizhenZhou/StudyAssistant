package com.zsz.studyassistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.zsz.studyassistant.data.Question
import com.zsz.studyassistant.data.QuestionTag
import com.zsz.studyassistant.data.Tag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 复用同一个格式化器，避免列表每项每次重组都新建 SimpleDateFormat */
private val TIME_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/** 复习：今天 / 本周（截止周日）两个选项卡，按 科目→知识点 分组，两列瀑布流 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(nav: NavHostController, vm: MainViewModel) {
    val s = LocalStrings.current
    var tab by remember { mutableIntStateOf(0) } // 0=今天 1=本周
    // 固定 Flow 实例，避免每次重组都重新订阅/查询数据库
    val todayFlow = remember { vm.dueQuestionsToday() }
    val weekFlow = remember { vm.dueQuestionsWeek() }
    val dueToday by todayFlow.collectAsState(emptyList())
    val dueWeek by weekFlow.collectAsState(emptyList())
    val questions = if (tab == 0) dueToday else dueWeek

    val categories by vm.categories.collectAsState()
    val tags by vm.tags.collectAsState()
    val questionTags by vm.questionTags.collectAsState()
    val catById = remember(categories) { categories.associateBy { it.id } }
    val tagById = remember(tags) { tags.associateBy { it.id } }
    val qTagIds = remember(questionTags) { questionTags.groupBy { it.questionId }.mapValues { e -> e.value.map { it.tagId } } }

    // 按 科目 → 知识点 分组；组内保持 nextReviewAt 升序（dueQuestions 已排序）
    val groups = remember(questions, catById, tagById, qTagIds, s) {
        val m = LinkedHashMap<String, MutableList<Question>>()
        for (q in questions) {
            val cn = q.categoryId?.let { catById[it]?.name } ?: s["notebook.filter.uncategorized"]
            val tn = (qTagIds[q.id] ?: emptyList()).mapNotNull { tagById[it]?.name }
            val key = if (tn.isEmpty()) cn else "$cn · ${tn.joinToString("/")}"
            m.getOrPut(key) { mutableListOf() }.add(q)
        }
        m.map { it.key to it.value.toList() }
    }

    // 扁平化顺序（跨分组），用于「第 i/n 题」进度：n = 本轮要复习的总数
    val flat = remember(groups) { groups.flatMap { it.second } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s["review.title"]) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s["common.back"])
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(s["review.tab.today"]) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(s["review.tab.week"]) })
            }
            if (questions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (tab == 0) s["review.empty.today"] else s["review.empty.week"])
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(1),   // 单列：整行宽度，图更大
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalItemSpacing = 8.dp
                ) {
                    for (g in groups) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            Text(
                                g.first,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                        items(g.second, key = { it.id }) { q ->
                            ReviewCard(q, onClick = {
                                // 记录本轮复习进度（第 i/n 题，n = 今日/本周待复习总数）
                                vm.startReviewSession(flat.size, flat.indexOf(q))
                                vm.loadForReview(q)
                                nav.navigate("solve")
                            })
                        }
                    }
                }
            }
        }
    }
}

/** 复习卡片（两列瀑布流）：图片按比例或文字 + 学科标签 + 下次时间 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewCard(q: Question, onClick: () -> Unit) {
    val s = LocalStrings.current
    val bitmap = remember(q.id, q.imageBytes) { q.imageBytes?.let { decodeSampledBitmap(it, 900) } }
    Card(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = null),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = s["review.imageDesc"],
                    modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
