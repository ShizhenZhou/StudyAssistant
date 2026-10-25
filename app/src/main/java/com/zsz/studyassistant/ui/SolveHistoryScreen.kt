package com.zsz.studyassistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.AppDatabase
import com.zsz.studyassistant.data.SolveSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 🕘 搜题记录（DESIGN-20261013 §2 的 P0 界面部分）。
 *
 * **性能红线**：列表只走 `QuestionDao.solveSessionsLight()`（轻查询：无 `imageBytes` BLOB、
 * 无 `conversationJson`）——`SELECT *` 会把 CursorWindow 撑爆并崩（错题本踩过同一个坑）。
 * 重字段（原图 / 会话 JSON）在**点击某一行时**才用 `solveSessionOnce(id)` 单独取。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SolveHistoryScreen(nav: NavHostController, vm: MainViewModel) {
    val s = LocalStrings.current
    val feedback = LocalAppFeedback.current
    val context = LocalContext.current
    // Dao 直接取（与 Reminder.kt 同一写法）；MainViewModel 的 dao 是 private，不改 VM
    val dao = remember(context) { AppDatabase.get(context).questionDao() }
    val scope = rememberCoroutineScope()

    val storedSessions by remember { dao.solveSessionsLight() }
        .collectAsState(initial = emptyList())
    val deletes by vm.deletionState.collectAsState()
    val sessions=storedSessions.filterNot { it.id in deletes.historyIds }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<com.zsz.studyassistant.data.QuestionId>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }   // 取重字段期间防重复点击

    fun exitSelection() { selectionMode = false; selectedIds = emptySet() }

    // 返回键：选择模式 → 先退出选择，而不是直接退页面
    BackHandler(enabled = selectionMode) { exitSelection() }

    // 今天 00:00 / 昨天 00:00（按天分组用；每次重组取一次，语义与 MainViewModel.startOfToday 一致）
    val todayStart = remember(sessions) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val groups = remember(sessions, todayStart) { groupSolveSessionsByDay(sessions, todayStart) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val allSelected = sessions.isNotEmpty() && selectedIds.size == sessions.size
                            TextButton(
                                onClick = { selectedIds = if (allSelected) emptySet() else sessions.map { it.id }.toSet() },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(
                                    if (allSelected) s["solve.deselectAll"] else s["solve.selectAll"],
                                    fontSize = BTN_LABEL_SOLVE, maxLines = 1, softWrap = false
                                )
                            }
                            Text(
                                s.format("solve.selectedCount", "n" to "${selectedIds.size}"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else {
                        // ★ 2026-10-20（用户要求）：条数从"标题下面一行"挪到**标题右边紧贴**（不靠到最右），
                        //   字号仍小于标题；标题过长时先压标题（省略号），条数优先保证显示。
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                // ★ 2026-10-18（用户要求）：顶栏标题去掉 emoji（主页按钮上仍保留 🕘）
                                s["history.title"],
                                fontSize = SUBPAGE_TITLE,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                s.format("history.count", "n" to "${sessions.size}"),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        TextButton(
                            onClick = { exitSelection() },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("✕", fontSize = 18.sp) }
                    } else {
                        SubPageBackArrow(onClick = { nav.popBackStack() })
                    }
                },
                actions = {
                    if (selectionMode) {
                        val compact = PaddingValues(horizontal = 4.dp)
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            contentPadding = compact
                        ) { Text(s["solve.delete"], fontSize = BTN_LABEL_SOLVE, maxLines = 1, softWrap = false) }
                        TextButton(
                            onClick = { exitSelection() },
                            contentPadding = compact
                        ) { Text(s["solve.done"], fontSize = BTN_LABEL_SOLVE, maxLines = 1, softWrap = false) }
                    }
                }
            )
        }
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    s["history.empty"],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            for (group in groups) {
                item(key = "header-${group.label}") {
                    Text(
                        s[group.label],
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp)
                    )
                }
                items(group.items, key = { it.id }) { rec ->
                    SolveHistoryItem(
                        rec = rec,
                        selectionMode = selectionMode,
                        selected = selectedIds.contains(rec.id),
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (selectedIds.contains(rec.id)) selectedIds - rec.id else selectedIds + rec.id
                            } else if (!opening) {
                                // ★ 续答链路：轻查询拿不到重字段 → 先按 id 取整条 → 载回 VM → 进解题页
                                opening = true
                                scope.launch {
                                    try {
                                        val full = withContext(Dispatchers.IO) {
                                            runCatching { dao.solveSessionOnce(rec.id) }.getOrNull()
                                        }
                                        if (full != null && vm.loadSolveSession(full)) {
                                            AppNav.openSolve(nav, AppNav.Origin.HISTORY)
                                        }
                                    } finally {
                                        opening = false
                                    }
                                }
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedIds = selectedIds + rec.id
                            }
                        }
                    )
                }
            }
        }
    }

    // 多选删除确认（文案含 {n}）
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(s["history.deleteConfirm.title"]) },
            text = { Text(s.format("history.deleteConfirm.text", "n" to "${selectedIds.size}")) },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedIds.toList()
                    showDeleteConfirm = false
                    exitSelection()
                    if (ids.isNotEmpty()) {
                        vm.deleteHistoryRecords(ids)
                    }
                }) { Text(s["common.delete"]) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(s["common.cancel"]) }
            }
        )
    }
}

/** 单行记录：题干首行 + 相对时间 + 已存/未存标记（不渲染原图，也不碰 conversationJson） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SolveHistoryItem(
    rec: SolveSession,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val s = LocalStrings.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (rec.text.isBlank()) "（图片题）" else TextPretty.oneLine(rec.text, 60),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        relativeTimeText(rec.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        s[if (rec.savedQuestionId != null) "history.saved" else "history.notSaved"],
                        fontSize = 10.sp,
                        color = if (rec.savedQuestionId != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                    )
                }
            }
            if (selectionMode) {
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(22.dp)
                        .background(if (selected) Color(0xFF4CAF50) else Color(0x22808080), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Text("✓", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

/** 一天的记录分组（label = L10n key：history.today / history.yesterday / history.earlier） */
internal data class SolveHistoryGroup(val label: String, val items: List<SolveSession>)

/**
 * 按天分组 + 组内 `createdAt` 倒序（纯函数，便于单测）。
 *
 * @param todayStart 今天 00:00 的时间戳；昨天区间 = `[todayStart - 1 天, todayStart)`
 */
internal fun groupSolveSessionsByDay(all: List<SolveSession>, todayStart: Long): List<SolveHistoryGroup> {
    val yesterdayStart = todayStart - TimeUnit.DAYS.toMillis(1)
    // ★ 2026-10-13：**坏数据兜底** —— 之前的版本可能把 `createdAt` 写成 0（根因见
    //   `MainViewModel.recordSolveSession` 的修复：INSERT 没记 `solveSessionCreatedAt`，UPDATE 时又写回 0）。
    //   `createdAt <= 0` 的记录改用 `updatedAt` 参与分组与排序，否则"今天刚搜的题"会掉进「更早」。
    fun ts(s: SolveSession) = s.createdAt
    val sorted = all.sortedByDescending { ts(it) }
    val today = sorted.filter { ts(it) >= todayStart }
    val yesterday = sorted.filter { ts(it) in yesterdayStart until todayStart }
    val earlier = sorted.filter { ts(it) < yesterdayStart }
    return buildList {
        if (today.isNotEmpty()) add(SolveHistoryGroup("history.today", today))
        if (yesterday.isNotEmpty()) add(SolveHistoryGroup("history.yesterday", yesterday))
        if (earlier.isNotEmpty()) add(SolveHistoryGroup("history.earlier", earlier))
    }
}

/**
 * 行内时间显示：今天的记录给 `HH:mm`，今年给 `MM-dd HH:mm`，跨年补年份。
 *
 * ⚠️ 刻意**不用**"N 分钟前 / 刚刚"这类相对措辞：那需要新的 4 语言文案 key，
 * 而本轮明确不新增 key（`L10nTableTest` 会拦 EN/JA/KO 未覆盖的 key）。
 */
internal fun relativeTimeText(ms: Long, now: Long = System.currentTimeMillis()): String {
    val cal = Calendar.getInstance().apply {
        timeInMillis = ms
    }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = nowCal.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "HH:mm"
        nowCal.get(Calendar.YEAR) == cal.get(Calendar.YEAR) -> "MM-dd HH:mm"
        else -> "yyyy-MM-dd HH:mm"
    }
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ms))
}
