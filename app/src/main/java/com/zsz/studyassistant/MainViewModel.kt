package com.zsz.studyassistant

import android.app.Application
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.withTransaction
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zsz.studyassistant.data.AiLang
import com.zsz.studyassistant.data.AiLangStore
import com.zsz.studyassistant.data.AppDatabase
import com.zsz.studyassistant.data.Category
import com.zsz.studyassistant.data.DeepSeekMessage
import com.zsz.studyassistant.data.KeyManager
import com.zsz.studyassistant.data.Question
import com.zsz.studyassistant.data.QuestionId
import com.zsz.studyassistant.data.QuestionIdentity
import com.zsz.studyassistant.data.QuestionImage
import com.zsz.studyassistant.data.QuestionTag
import com.zsz.studyassistant.data.MAX_QUESTION_IMAGES
import com.zsz.studyassistant.data.mergeImageKeys
import com.zsz.studyassistant.data.questionImageBase64
import com.zsz.studyassistant.data.Review
import com.zsz.studyassistant.data.SOLVE_SESSION_KEEP
import com.zsz.studyassistant.data.SOLVE_SESSION_KEEP_DAYS
import com.zsz.studyassistant.data.SolveSession
import com.zsz.studyassistant.data.StudyAssistant
import com.zsz.studyassistant.data.Tag
import com.zsz.studyassistant.data.solveSessionsToDelete
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** 聊天界面显示的一条消息（可序列化，用于保存对话会话） */
@Serializable
data class ChatItem(val id: Long, val role: String, val content: String, val images: List<String>? = null, val thinking: String? = null)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    init { KeyManager.init(app) }
    private val calendarDay = MutableStateFlow(com.zsz.studyassistant.data.CalendarDay.now())
    fun refreshClock() { calendarDay.value = com.zsz.studyassistant.data.CalendarDay.now() }
    init { viewModelScope.launch { while (isActive) { refreshClock(); kotlinx.coroutines.delay(30_000) } } }
    var reviewOpenRequested by mutableStateOf(false)
        private set
    fun requestReviewNavigation() { reviewOpenRequested = true }
    fun consumeReviewNavigation() { reviewOpenRequested = false }


    private val dao = AppDatabase.get(app).questionDao()
    private val json = Json { ignoreUnknownKeys = true }

    // ---- 应用主题（system/light/dark，默认 system）----
    var theme by mutableStateOf(
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString("theme", "system") ?: "system"
    )
        private set
    fun updateTheme(t: String) {
        theme = t
        getApplication<Application>().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("theme", t).apply()
    }

    // ---- 应用主题配色（预设色板，默认紫色）----
    var themeColor by mutableStateOf(
        com.zsz.studyassistant.ui.AppThemeColor.fromId(
            app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getString("theme_color", null)
        )
    )
        private set
    fun updateThemeColor(c: com.zsz.studyassistant.ui.AppThemeColor) {
        themeColor = c
        getApplication<Application>().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("theme_color", c.id).apply()
    }

    /** 自定义配色的 ARGB（仅当 themeColor == CUSTOM 时生效；由取色盘选择） */
    var customColor by mutableStateOf(
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getInt("theme_color_custom", 0xFF6750A4.toInt())
    )
        private set
    fun updateCustomColor(argb: Int) {
        customColor = argb
        themeColor = com.zsz.studyassistant.ui.AppThemeColor.CUSTOM
        getApplication<Application>().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt("theme_color_custom", argb)
            .putString("theme_color", com.zsz.studyassistant.ui.AppThemeColor.CUSTOM.id)
            .apply()
    }

    // ---- AI 生成语言（拍题/直接提问/追问/批改/同类题的回答语言，默认跟随系统）----
    var aiLang by mutableStateOf(AiLangStore.load(app))
        private set
    fun updateAiLang(lang: AiLang) {
        aiLang = lang
        AiLangStore.save(getApplication(), lang)
    }

    // ---- 界面语言（App 文案语言，默认跟随系统）----
    var uiLang by mutableStateOf(com.zsz.studyassistant.ui.UiLangStore.load(app))
        private set
    fun updateUiLang(lang: com.zsz.studyassistant.ui.UiLang) {
        uiLang = lang
        com.zsz.studyassistant.ui.UiLangStore.save(getApplication(), lang)
        // 通知渠道名是用当前语言建的，切语言后立刻重建一次，免得要等下次启动才更新
        com.zsz.studyassistant.data.ReminderScheduler.ensureChannel(getApplication())
    }

    /** 错题本排序方式（A5）：持久化在 settings，默认"最近添加"（= 历史行为） */
    private val _notebookSort = kotlinx.coroutines.flow.MutableStateFlow(
        com.zsz.studyassistant.data.NotebookSortStore.load(getApplication())
    )
    val notebookSort: StateFlow<com.zsz.studyassistant.data.NotebookSortMode> = _notebookSort

    fun setNotebookSort(mode: com.zsz.studyassistant.data.NotebookSortMode) {
        _notebookSort.value = mode
        com.zsz.studyassistant.data.NotebookSortStore.save(getApplication(), mode)
    }

    /** 界面字号档位（C14）：MainActivity 用它覆写 LocalDensity，对话区 WebView 也同步缩放 */
    var fontScale by mutableStateOf(com.zsz.studyassistant.ui.FontScaleStore.load(getApplication()))
        private set

    fun updateFontScale(scale: com.zsz.studyassistant.ui.FontScale) {
        fontScale = scale
        com.zsz.studyassistant.ui.FontScaleStore.save(getApplication(), scale)
    }

    private val deletedNotebookIds = mutableSetOf<QuestionId>()
    private val deleteQueue = com.zsz.studyassistant.data.DeferredDeletion(viewModelScope,
        commit = { commitDeletion(it) }, failure = { batch ->
            if(savedQuestionId in batch.questions) { isDeleted=false; savedToNotebook=true }
            deleteError = com.zsz.studyassistant.ui.stringsFor(uiLang)["delete.failed"]
        })
    val deletionState = deleteQueue.state
    var deleteError by mutableStateOf<String?>(null)
        private set
    fun clearDeleteError() { deleteError=null }
    fun undoDelete(token: Long) {
        val batch=deletionState.value.pending
        if(deleteQueue.undo(token) && savedQuestionId in batch?.questions.orEmpty()) {
            isDeleted=false; savedToNotebook=true
        }
    }
    private suspend fun commitDeletion(batch: com.zsz.studyassistant.data.DeleteBatch) {
        solveSessionWriteMutex.withLock {
            AppDatabase.get(getApplication()).withTransaction {
                if(batch.categories.isNotEmpty()) {
                    dao.clearCategoryForCategories(batch.categories.toList())
                    dao.deleteCategories(batch.categories.toList())
                }
                if(batch.questions.isNotEmpty()) {
                    dao.clearImagesFor(batch.questions.toList())
                    dao.deleteQuestionTagsForQuestions(batch.questions.toList())
                    dao.deleteReviewsForQuestions(batch.questions.toList())
                    dao.deleteByIds(batch.questions.toList())
                    batch.questions.forEach { dao.unlinkExistingSession(it) }
                }
                if(batch.histories.isNotEmpty()) dao.deleteSolveSessionsByIds(batch.histories.toList())
            }
            deletedNotebookIds += batch.questions
            batch.questions.forEach { pendingNotebookContent.remove(it) }
            try {
                withContext(Dispatchers.IO) {
                    pendingSaveStore.invalidateNotebook(batch.questions)
                    conversationDrafts.invalidate(batch.questions)
                    pendingSaveStore.invalidateHistory(batch.histories)
                }
            } catch (_: Exception) { saveJournalIssue=true }
            if(batch.histories.isNotEmpty()) dropCurrentSessionIfPruned(batch.histories.toList())
            if(savedQuestionId in batch.questions) { savedQuestionId=null; savedToNotebook=false; isDeleted=true }
            if(similarSavedQuestionId in batch.questions) similarSavedQuestionId=null
            refreshPendingSaveState()
        }
    }
    private fun stageQuestionDeletion(ids: Collection<QuestionId>) {
        if(savedQuestionId in ids) { stopActiveGeneration(); isDeleted=true; savedToNotebook=false }
        deleteQueue.stage(questions=ids.toSet())
    }

    /** 错题本数据流（A5：按用户选的排序方式排好；排序变化 → 立即重新发射） */
    val notebook: StateFlow<List<Question>> =
        kotlinx.coroutines.flow.combine(
            dao.getAll(), dao.allReviews(), notebookSort, deletionState
        ) { allQuestions, rs, mode, deletes ->
            val qs=allQuestions.filterNot { it.id in deletes.questionIds }
            com.zsz.studyassistant.data.NotebookSorting.sort(qs, rs.associateBy { it.questionId }, mode)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 复习记录流（A5 复习筛选用：掌握档位 / 下次复习时间） */
    val reviewRecords: StateFlow<List<Review>> =
        dao.allReviews().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * B9 查重：题干（归一化后）相同的已有错题。
     * 只查未删除的题、只取轻字段（不含图片 BLOB），太短的题干不比（见 DuplicateCheck）。
     */
    suspend fun findDuplicateQuestion(text: String, excludeId: QuestionId? = null): Question? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.zsz.studyassistant.data.DuplicateCheck.find(dao.allLightOnce(), text, excludeId)
            }.getOrNull()
        }

    /** 用户自定义分类列表
     *  ★ 2026-10-13：改成 `SharingStarted.Eagerly` —— 这两个小表要**随时可读 `.value`**：
     *   拍题链路的请求是在 CropScreen 里拼的（那时 SolveScreen 还没 compose，没人订阅），
     *   用 `WhileSubscribed` 会让 `.value` 在冷启动/长时间没人看时退回初始空表 →
     *   提示词又变成「当前没有任何分类」→ AI 自己造分类（重复分类的根因）。
     *   分类/标签表极小（几十行），常驻收集的开销可忽略。 */
    val categories: StateFlow<List<Category>> =
        kotlinx.coroutines.flow.combine(dao.categories(), deletionState) { rows, deletes -> rows.filterNot { it.id in deletes.categoryIds } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 知识点标签列表（同上：Eagerly，保证 `.value` 随时可用） */
    val tags: StateFlow<List<Tag>> =
        dao.tags().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ───────── 学习统计 ─────────
    /** 统计快照（全部由现有表计算，不改数据库结构） */
    data class StatsSnapshot(
        val totalQuestions: Int = 0,
        val todayReviewed: Int = 0,
        val weekReviewed: Int = 0,
        val streakDays: Int = 0,
        val dueNow: Int = 0,
        val mastery: List<Pair<String, Int>> = emptyList(),   // 未开始 / 复习中 / 已掌握
        val subjects: List<Pair<String, Int>> = emptyList(),  // 科目（分类）→ 题数
        val last7Days: List<Int> = emptyList(),               // 近 7 天新增（最早 → 今天）
        val last30Days: List<Int> = emptyList()               // 近 30 天新增
    )

    /** A nullable match set reports an IO failure, not an empty successful search. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun notebookMatches(query: String): Flow<Pair<String,Set<QuestionId>?>> = dao.getAll().mapLatest {
        if(query.isNotBlank()) kotlinx.coroutines.delay(200)
        Pair<String,Set<QuestionId>?>(query,dao.matchingQuestionIds(query))
    }.flowOn(Dispatchers.IO).catch { emit(query to null) }

    val stats: StateFlow<StatsSnapshot> =
        kotlinx.coroutines.flow.combine(
            dao.getAll(), dao.allReviews(), dao.categories(), calendarDay, deletionState
        ) { allQuestions, rs, cats, day, deletes ->
            val qs=allQuestions.filterNot { it.id in deletes.questionIds }
            val now = System.currentTimeMillis()
            val dayMs = 24L * 60 * 60 * 1000
            val todayStart = day.start
            val weekStart = day.after(-6)
            val byId = rs.associateBy { it.questionId }

            // ★ 只统计"未被删除"的题：复习记录可能残留在已删除的题上（软删除/移除错题），
            //   原来直接用 allReviews() 会把它们算进"今日复习/近7天/待复习"，导致数字偏大
            val aliveIds = qs.map { it.id }.toSet()
            // 待复习口径与复习会话一致：dueQuestions(endOfToday()) = 未删除 + nextReviewAt <= 今天末
            val endOfToday = day.end
            val todayReviewed = rs.count { it.questionId in aliveIds && it.lastReviewedAt in todayStart..day.end }
            val weekReviewed = rs.count { it.questionId in aliveIds && it.lastReviewedAt in weekStart..day.end }
            val dueNow = rs.count { it.questionId in aliveIds && it.nextReviewAt <= endOfToday }

            // 连续复习天数：从今天（或昨天）往前数，哪天有复习记录就 +1
            val streak = com.zsz.studyassistant.data.ReviewPlanning.recordedStreak(
                rs.filter { it.questionId in aliveIds }.map { it.lastReviewedAt }, day)

            // 掌握度：无记录 = 未开始；intervalStep <= 2 = 复习中；>= 3 = 已掌握
            var notStarted = 0; var learning = 0; var mastered = 0
            qs.forEach { q ->
                val step = byId[q.id]?.intervalStep
                when {
                    step == null -> notStarted++
                    step >= 3 -> mastered++
                    else -> learning++
                }
            }

            val nameOf = cats.associate { it.id to it.name }
            val subjects = qs.groupingBy { q -> q.categoryId?.let { nameOf[it] } ?: "未分类" }
                .eachCount().entries.sortedByDescending { it.value }
                .map { it.key to it.value }

            fun series(days: Int): List<Int> = ((days - 1) downTo 0).map { back ->
                val from = day.after(-back)
                val to = day.after(1 - back)
                qs.count { it.createdAt in from until to }
            }
            val last7 = series(7)
            val last30 = series(30)

            StatsSnapshot(
                totalQuestions = qs.size,
                todayReviewed = todayReviewed,
                weekReviewed = weekReviewed,
                streakDays = streak,
                dueNow = dueNow,
                mastery = listOf("stats.mastery.notStarted" to notStarted, "stats.mastery.learning" to learning, "stats.mastery.mastered" to mastered),
                subjects = subjects,
                last7Days = last7,
                last30Days = last30
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsSnapshot())
    /** 错题↔标签 关联（用于按 tag 筛选） */
    val questionTags: StateFlow<List<QuestionTag>> =
        dao.allQuestionTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 分类名 / 标签名：**直接取界面正在订阅的那两个 StateFlow 的当前值**，供拼提示词用。
     *
     * ★ 2026-10-13 修复（用户报「没有把错题本已有的科目分类传给 AI」）：
     *   原先这里是 `categories.map { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())`，
     *   而**全项目只读它的 `.value`、没有任何地方订阅它** → `WhileSubscribed` 的上游**永远不会启动** →
     *   值恒为 `stateIn` 的初始值 `emptyList()`（读 `.value` 不算订阅！）。
     *   后果：发给 AI 的提示词永远写着「已知分类：（当前没有任何分类）」→ 模型自己造分类 →
     *   错题本里出现「大学物理」和「物理」这种重复分类。
     *   现在改成读 `categories.value`（界面 `collectAsState()` 一直在订阅它，值是最新的）。
     */
    private val categoryNames: List<String> get() = categories.value.map { it.name }
    private val tagNames: List<String> get() = tags.value.map { it.name }

    // ---- 对话流状态 ----
    var imageBytes by mutableStateOf<ByteArray?>(null)
    var chatItems by mutableStateOf<List<ChatItem>>(emptyList())
        private set
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
        private set
    var savedToNotebook by mutableStateOf(false)
        private set
    var savedQuestionId: QuestionId? = null
        private set
    var isFromNotebook by mutableStateOf(false)
        private set
    var networkError by mutableStateOf(false)
        private set
    private var retryAction: (() -> Unit)? = null
    private var saving: Boolean
        get() = sessionRecordBinding.saving
        set(value) { sessionRecordBinding.saving = value }
    private var cancelPending: Boolean
        get() = sessionRecordBinding.cancelPending
        set(value) { sessionRecordBinding.cancelPending = value }
    private var isPhoto = false
    /** 本次会话是否来自「图文提问」（用于标题保持"图文提问"） */
    var askMode by mutableStateOf(false)
        private set
    /** 供界面判断：当前会话是否来自"拍照搜题"（返回时决定回拍题页还是返回来处） */
    val isPhotoSession: Boolean get() = isPhoto
    private var questionText = ""
    /** 只读访问当前题干（B9 查重等界面逻辑用；questionText 本身保持私有以免被误改） */
    val questionTextForUi: String get() = questionText
    /** 直接提问携带的附图（文字 + 多图） */
    private var directImages: List<ByteArray> = emptyList()
    /** 拍照搜题（多张）携带的图，作为题目一起识别 */
    private var multiImages: List<ByteArray> = emptyList()

    /**
     * 当前会话对应的**搜题记录** id（`solve_sessions.id`）——用于"一次会话只落一条"。
     * null = 本会话还没落库（或刚开始）；非 null = 后续追问/继续生成一律 UPDATE 这条。
     * 在 [resetSession] / [prepareSession] 里清空（换了新会话）。
     */
    private class SolveSessionBinding(
        var id: QuestionId? = null,
        var createdAt: Long = 0L,
        var source: String? = null,
        var saving: Boolean = false,
        var cancelPending: Boolean = false,
        var historyAllowed: Boolean = false
    )

    private var sessionRecordBinding = SolveSessionBinding()
    private var retryBinding: SolveSessionBinding? = null
    private var captureIdentity: Pair<QuestionId, Long>? = null
    private val solveSessionWriteMutex = kotlinx.coroutines.sync.Mutex()
    private val historyWriteQueue = com.zsz.studyassistant.data.HistoryWriteQueue()
    var failedHistoryWrites by mutableStateOf(0)
        private set
    var retryingHistoryWrites by mutableStateOf(false)
        private set
    private val conversationDrafts = com.zsz.studyassistant.data.ConversationDraftStore(app)
    private var activeDraft: com.zsz.studyassistant.data.ConversationDraft? = null
    private var currentNotebookBaseline: com.zsz.studyassistant.data.SavePayload? = null
    private var draftRevision = 0L
    var draftSummaries by mutableStateOf<List<com.zsz.studyassistant.data.DraftSummary>>(emptyList())
        private set
    var draftIssue by mutableStateOf(false)
        private set
    var draftError by mutableStateOf<String?>(null)
        private set
    fun clearDraftError() { draftError=null }
    var draftSolveRequested by mutableStateOf(false)
        private set
    var draftReturnOrigin by mutableStateOf("home")
        private set
    fun consumeDraftNavigation() { draftSolveRequested=false }
    init { viewModelScope.launch { refreshDrafts() } }
    private suspend fun refreshDrafts() {
        try {
            val scan=withContext(Dispatchers.IO) { conversationDrafts.summaries() }
            draftSummaries=scan.first; draftIssue=scan.second>0
        } catch (_: Exception) { draftIssue=true }
    }
    private val draftWriteMutex = kotlinx.coroutines.sync.Mutex()
    private suspend fun writeDraft(value: com.zsz.studyassistant.data.ConversationDraft,
        baseline: com.zsz.studyassistant.data.SavePayload?) {
        try {
            val saved=withContext(kotlinx.coroutines.NonCancellable) { draftWriteMutex.withLock { withContext(Dispatchers.IO) { conversationDrafts.write(value,baseline) } } }
            if(activeDraft?.generation==value.generation) activeDraft=activeDraft?.copy(baseline=saved.baseline)
        } catch (_: Exception) { draftError=com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.saveFailed"] }
    }
    private fun checkpointDraft(refresh: Boolean = true) {
        val value=activeDraft?.copy(revision=++draftRevision) ?: return
        val baseline=currentNotebookBaseline
        viewModelScope.launch { writeDraft(value,baseline); if(refresh) refreshDrafts() }
    }
    private suspend fun persistCompletedConversation() {
        saveSessionOnExit()
        solveSessionWriteMutex.withLock { /* Await all snapshots just enqueued by saveSessionOnExit. */ }
    }
    private suspend fun draftCanBeCleared(value: com.zsz.studyassistant.data.ConversationDraft): Boolean {
        val chats=value.completedChats ?: return false
        val text=value.completedQuestion ?: value.question
        val answer=chats.lastOrNull { it.role=="assistant" }?.content.orEmpty()
        val conversation=json.encodeToString(chats)
        return try {
            val scan=withContext(Dispatchers.IO) { pendingSaveStore.scan() }
            if(scan.unreadable!=0 || scan.entries.any { it.payload.id==value.id } ||
                value.id in pendingNotebookContent || historyWriteQueue.failedTickets().any { it.row.id==value.id }) return false
            // Absence of an error is not evidence of a save: read back a durable destination.
            val notebook=dao.heavyOnce(value.id)
            val history=dao.solveSessionOnce(value.id)
            (notebook!=null && !notebook.deleted && notebook.text==text && notebook.answer==answer && notebook.conversationJson==conversation) ||
                (history!=null && history.text==text && history.answer==answer && history.conversationJson==conversation)
        } catch (_: Exception) { saveJournalIssue=true; false }
    }
    internal suspend fun finishDraftCleanup(value: com.zsz.studyassistant.data.ConversationDraft) {
        try { withContext(kotlinx.coroutines.NonCancellable+Dispatchers.IO) { conversationDrafts.clear(value.id,value.generation) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { draftIssue=true; return }
        if(activeDraft?.generation==value.generation) activeDraft=null
    }
    fun discardDraft(id: QuestionId) {
        viewModelScope.launch {
            try {
                val value=withContext(Dispatchers.IO) { conversationDrafts.read(id) } ?: return@launch
                withContext(Dispatchers.IO) { conversationDrafts.clear(id,value.generation) }
                if(activeDraft?.generation==value.generation) { activeDraft=null; discardInterrupted() }
                refreshDrafts()
            } catch (_: Exception) { draftError=com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.saveFailed"] }
        }
    }
    fun resumeDraft(id: QuestionId, origin: String) {
        if(busy || reviewBusy || dataBusy) return
        viewModelScope.launch {
            try {
                val value=draftWriteMutex.withLock { withContext(Dispatchers.IO) { checkNotNull(conversationDrafts.read(id)) } }
                val row=solveSessionWriteMutex.withLock { dao.heavyOnce(id) }
                if(value.notebookId!=null) {
                    check(row!=null && !row.deleted && id !in deletionState.value.questionIds)
                    val actual=withContext(Dispatchers.IO) { com.zsz.studyassistant.data.PendingSaveCodec.fingerprint(com.zsz.studyassistant.data.SavePayload.notebook(row)) }
                    if(actual!=value.baseline) {
                        val completed=value.completedChats?.let { chats -> com.zsz.studyassistant.data.SavePayload(id,value.createdAt,
                            value.completedQuestion ?: value.question,chats.lastOrNull { it.role=="assistant" }?.content.orEmpty(),json.encodeToString(chats)) }
                        if(completed!=null && actual==com.zsz.studyassistant.data.PendingSaveCodec.fingerprint(completed)) {
                            withContext(Dispatchers.IO) { conversationDrafts.clear(id,value.generation) }
                            loadQuestion(row); refreshDrafts(); draftReturnOrigin=origin; draftSolveRequested=true
                            return@launch
                        }
                        error("Draft conflict")
                    }
                }
                stopActiveGeneration()
                resetSession()
                val history=dao.solveSessionOnce(id)
                sessionRecordBinding=SolveSessionBinding(id,value.createdAt,value.source,
                    historyAllowed=value.historyAllowed && history!=null)
                questionText=value.question
                isPhoto=value.photo; askMode=value.ask; gradeMode=value.grade
                pendingRedoFor=value.redoFor
                questionImages=value.images
                directImages=withContext(Dispatchers.IO) { value.images.map { Base64.decode(it,Base64.NO_WRAP) } }
                imageBytes=directImages.firstOrNull()
                savedQuestionId=row?.id; savedToNotebook=row!=null; isFromNotebook=row!=null
                currentQuestionCreatedAt=value.createdAt
                currentQuestionCategoryId=row?.categoryId
                currentQuestionTags = row?.let { dao.tagIdsForQuestion(it.id) }.orEmpty()
                currentNotebookBaseline=row?.let { com.zsz.studyassistant.data.SavePayload.notebook(it) }
                chatItems=value.base
                conversationDrafts.reserve(value.id,value.generation)
                activeDraft=value
                if(value.complete && value.completedChats!=null) {
                    chatItems=value.completedChats; questionText=value.completedQuestion ?: value.question
                    persistCompletedConversation()
                    if(draftCanBeCleared(value)) finishDraftCleanup(value)
                } else {
                    streamingText=value.raw; thinkingText=value.thinking; streamInterrupted=true
                    contModel=value.model; contPrefix=value.raw
                    contMessages=continuationMessages(value.messages,value.raw)
                    contDraftAction=value.action
                    contOnDone={ output -> applyRecoveredReply(value.action,output) }
                }
                refreshDrafts(); draftReturnOrigin=origin; draftSolveRequested=true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { draftError=com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.conflict"] }
        }
    }
    private fun applyRecoveredReply(action: String, output: String) {
        when(action) {
            "grade" -> onGradeReply(output)
            "followup" -> addItem("assistant",StudyAssistant.stripMetaLines(output))
            "regenerate_followup", "regenerate_initial" -> {
                chatItems=com.zsz.studyassistant.data.regenerationBase(chatItems)
                if(action=="regenerate_followup") addItem("assistant",StudyAssistant.stripMetaLines(output))
                else {
                    val result=StudyAssistant.parseVisionOutput(output)
                    suggestedCategory=result.category; suggestedTags=result.tags
                    addItem("assistant",result.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
                }
            }
            else -> {
                val result=StudyAssistant.parseVisionOutput(output)
                if(isPhoto && !askMode) questionText=result.question
                suggestedCategory=result.category; suggestedTags=result.tags
                if(chatItems.none { it.role=="question" }) addItem("question",questionText,questionImages.ifEmpty { null })
                addItem("assistant",result.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
            }
        }
    }

    private val pendingSaveStore = com.zsz.studyassistant.data.PendingSaveStore(app)
    private val pendingSaveRepository = com.zsz.studyassistant.data.PendingSaveRepository(AppDatabase.get(app), pendingSaveStore)
    private val pendingNotebookContent = linkedMapOf<QuestionId, com.zsz.studyassistant.data.SavePayload>()
    var hasSaveActivity by mutableStateOf(false)
        private set
    var saveRecoveryReady by mutableStateOf(false)
        private set
    var pendingSaveCount by mutableStateOf(0)
        private set
    var saveJournalIssue by mutableStateOf(false)
        private set
    var saveOperations by mutableStateOf(0)
        private set
    var saveRevision by mutableStateOf(0L)
        private set
    var saveFeedback by mutableStateOf<String?>(null)
        private set
    var saveFeedbackIsError by mutableStateOf(false)
        private set
    fun clearSaveFeedback() { saveFeedback = null }

    init {
        viewModelScope.launch {
            solveSessionWriteMutex.withLock {
                try {
                    val scan = withContext(Dispatchers.IO) { pendingSaveStore.scan() }
                    hasSaveActivity = scan.entries.isNotEmpty() || scan.unreadable > 0
                    pendingSaveRepository.retryAll(historyCutoff())
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { saveJournalIssue = true }
                refreshPendingSaveState()
            }
        }
    }

    private fun historyCutoff() = System.currentTimeMillis() - SOLVE_SESSION_KEEP_DAYS * DAY_MS
    private suspend fun refreshPendingSaveState() {
        try {
            val scan = withContext(Dispatchers.IO) { pendingSaveStore.scan() }
            val durable = scan.entries.map { "${it.target}:${it.payload.id}" }.toSet()
            val keys = durable.toMutableSet()
            historyWriteQueue.failedTickets().forEach { keys += "HISTORY:${it.row.id}" }
            pendingNotebookContent.keys.forEach { keys += "NOTEBOOK:$it" }
            pendingSaveCount = keys.size
            val copies = scan.entries.associateBy { "${it.target}:${it.payload.id}" }
            val memory = historyWriteQueue.failedTickets().associate { "HISTORY:${it.row.id}" to
                com.zsz.studyassistant.data.SavePayload.history(it.row) } +
                pendingNotebookContent.values.associate { "NOTEBOOK:${it.id}" to it }
            saveRecoveryReady = scan.unreadable == 0 && memory.all { (key, payload) ->
                copies[key]?.payload == payload
            }
            saveJournalIssue = scan.unreadable > 0
        } catch (_: Exception) { saveJournalIssue = true }
        saveRevision++
    }

    private fun queueNotebookSave(payload: com.zsz.studyassistant.data.SavePayload) {
        if(payload.id in deletionState.value.questionIds || payload.id in deletedNotebookIds) return
        hasSaveActivity = true
        pendingNotebookContent[payload.id] = payload
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            withContext(kotlinx.coroutines.NonCancellable) { persistNotebookContent(payload) }
        }
    }
    private suspend fun persistNotebookContent(payload: com.zsz.studyassistant.data.SavePayload) {
        saveOperations++
        try {
            solveSessionWriteMutex.withLock {
                if (pendingNotebookContent[payload.id] !== payload || payload.id in deletedNotebookIds) return@withLock
                try {
                    pendingSaveRepository.saveNotebook(payload)
                    if (pendingNotebookContent[payload.id] === payload) pendingNotebookContent.remove(payload.id)
                    if(savedQuestionId==payload.id) currentNotebookBaseline=payload
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Preserve the in-memory snapshot even if journal creation failed. */ }
                refreshPendingSaveState()
            }
        } finally { saveOperations-- }
    }

    fun exportPendingSaves(uri: android.net.Uri) {
        viewModelScope.launch {
            solveSessionWriteMutex.withLock {
                try {
                    val entries = withContext(Dispatchers.IO) { pendingSaveStore.scan().entries }.toMutableList()
                    historyWriteQueue.failedTickets().forEach { ticket ->
                        entries.removeAll { it.target == com.zsz.studyassistant.data.SaveTarget.HISTORY && it.payload.id == ticket.row.id }
                        entries += com.zsz.studyassistant.data.PendingSave(com.zsz.studyassistant.data.SaveTarget.HISTORY,
                            com.zsz.studyassistant.data.SavePayload.history(ticket.row), null)
                    }
                    pendingNotebookContent.values.forEach { payload ->
                        entries.removeAll { it.target == com.zsz.studyassistant.data.SaveTarget.NOTEBOOK && it.payload.id == payload.id }
                        entries += com.zsz.studyassistant.data.PendingSave(com.zsz.studyassistant.data.SaveTarget.NOTEBOOK, payload, null)
                    }
                    withContext(Dispatchers.IO) {
                        val resolver = getApplication<Application>().contentResolver
                        checkNotNull(resolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
                            it.write(com.zsz.studyassistant.data.PendingSaveCodec.export(entries, conversationDrafts.exportable()))
                        }
                    }
                    saveFeedbackIsError = false
                    saveFeedback = com.zsz.studyassistant.ui.stringsFor(uiLang)["save.exportDone"]
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    saveFeedbackIsError = true
                    saveFeedback = com.zsz.studyassistant.ui.stringsFor(uiLang)["save.exportFailed"]
                }
            }
        }
    }
    fun discardPendingSaves() {
        if (saveOperations > 0 || retryingHistoryWrites) return
        viewModelScope.launch {
            solveSessionWriteMutex.withLock {
                if (saveOperations > 0 || retryingHistoryWrites) return@withLock
                try {
                    withContext(Dispatchers.IO) { pendingSaveStore.discardAll() }
                    historyWriteQueue.clear()
                    pendingNotebookContent.clear()
                    failedHistoryWrites = 0
                    saveFeedback = null
                    hasSaveActivity = false
                    refreshPendingSaveState()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { saveJournalIssue = true }
            }
        }
    }

    private suspend fun requireNoPendingSaves() {
        check(!deletionState.value.active) { com.zsz.studyassistant.ui.stringsFor(uiLang)["delete.wait"] }
        refreshPendingSaveState()
        check(pendingSaveCount == 0 && !saveJournalIssue && historyWriteQueue.pendingTickets().isEmpty()) {
            com.zsz.studyassistant.ui.stringsFor(uiLang)["save.resolveFirst"]
        }
    }

    private val notebookWriteMutex = solveSessionWriteMutex
    private val dataMaintenanceMutex = kotlinx.coroutines.sync.Mutex()

    private var currentSessionId: QuestionId?
        get() = sessionRecordBinding.id
        set(value) { sessionRecordBinding.id = value }

    private var solveSessionCreatedAt: Long
        get() = sessionRecordBinding.createdAt
        set(value) { sessionRecordBinding.createdAt = value }


    /** 启动时定期清理：软删除的题 + 孤儿复习记录/标签关联（不影响当前会话的「恢复」） */
    fun purgeDeletedData() {
        viewModelScope.launch {
            dataMaintenanceMutex.lock()
            var maintained = false
            try {
                // 保留原有启动清理政策；先恢复可恢复图片，再裁剪搜题记录。
                val db = AppDatabase.get(getApplication())
                db.withTransaction {
                    dao.purgeDeletedQuestions()
                    dao.purgeOrphanReviews()
                    dao.purgeOrphanQuestionTags()
                    repairQuestionImagesFromSessions()
                    backfillQuestionImages()
                    fillImagesTable()
                    dao.purgeOrphanImages()
                }
                maintained = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 启动维护失败不阻塞使用，也不把失败记作“已修复”。
            } finally {
                dataMaintenanceMutex.unlock()
            }
            // 维护失败时保留可能仍有恢复价值的记录，避免马上裁掉修复来源。
            if (maintained) pruneSolveSessions()
        }
    }

    /**
     * 只使用 savedQuestionId 明确关联的记录补图；只追加，不改写原会话、不按题干猜配。
     * 异常向上传播，由维护流程的事务负责回滚。
     */
    /** 从现有表、旧主图、题目会话和明确关联的记录收集原图；不修改这些来源。 */
    private suspend fun recoverableImages(q: Question, includeSession: Boolean): List<ByteArray> {
        val result = dao.imagesForQuestion(q.id).sortedBy { it.position }.map { it.bytes }.toMutableList()
        q.imageBytes?.let { result.add(it) }
        val keys = questionImageBase64(q.conversationJson).toMutableList()
        if (includeSession) {
            // 不根据“题干恰好相同”猜关联，避免把另一道题的图补进来。
            val session = dao.solveSessionForQuestion(q.id)
            session?.imageBytes?.let { result.add(it) }
            session?.imagesJson?.let { keys += json.decodeFromString<List<String>>(it) }
            keys += questionImageBase64(session?.conversationJson)
        }
        keys.filter { it.isNotBlank() }.forEach { result.add(Base64.decode(it, Base64.NO_WRAP)) }
        return result
    }

    private suspend fun repairQuestionImagesFromSessions() {
        val db = AppDatabase.get(getApplication())
        for (row in dao.questionsWithConversationOnce()) {
            db.withTransaction {
                val q = dao.heavyOnce(row.id) ?: return@withTransaction
                dao.appendMissingImages(q.id, recoverableImages(q, includeSession = true), "repair")
            }
        }
    }

    /**
     * 一次性「原图回填」（2026-10-14）：把 `imageBytes` 为空、但会话 JSON 里仍有附图的错题补上主图。
     *
     * 背景：`imageBytes`（单图专用字段）只覆盖"单图拍照"这条链路，**多图拍题 / 图文提问**从不给它赋值 →
     * 存进错题本后，错题本卡片缩略图、复习页自测原图、**导出的备份**里都没有图
     * （用户报的"加错题本后原图有时会丢，多图时更容易"）。
     * 图片其实一直在 `conversationJson`（`ChatItem.images`）里，所以这里取回来写回数据库，
     * 让**所有只读 `imageBytes` 的地方**一起恢复（界面侧另有读端兜底，两者互为保险）。
     *
     * 幂等且安全：只处理 `imageBytes IS NULL` 的行；解析/解码失败直接跳过，**绝不动已有图**。
     */
    private suspend fun backfillQuestionImages() {
        val db = AppDatabase.get(getApplication())
        for (row in dao.questionsWithConversationOnce()) {
            db.withTransaction {
                val q = dao.heavyOnce(row.id) ?: return@withTransaction
                if (q.imageBytes == null) {
                    val main = recoverableImages(q, includeSession = true).firstOrNull { it.isNotEmpty() }
                    if (main != null) dao.setQuestionImage(q.id, main)
                }
            }
        }
    }

    /** 原图只按内容去重追加；保留已有主图和顺序，缺图插入失败向上传播。 */
    private suspend fun writeQuestionImages(qid: QuestionId, images: List<ByteArray>, source: String = "save") {
        if (images.isEmpty()) return
        dao.appendMissingImages(qid, images, source)
    }

    /**
     * DB v9 补全任务（幂等，2026-10-16）：**旧数据原地升级**的最后一步。
     *
     * 迁移 SQL 已把 `imageBytes`（主图）搬进新表；这里补两类：
     *  ① 迁移时**没有主图**的题（多图拍题 / 图文提问 → `imageBytes` 为 null），从 `conversationJson` 取图；
     *  ② 有主图但**其余图还没进新表**的题（会话里还有第 2/3 张）。
     * 只做加法、失败跳过，**绝不动已有图**（也不改 `imageBytes`，避免把主图换成会话里的另一张）。
     */
    private suspend fun fillImagesTable() {
        val db = AppDatabase.get(getApplication())
        for (row in dao.questionsWithConversationOnce()) {
            db.withTransaction {
                val q = dao.heavyOnce(row.id) ?: return@withTransaction
                dao.appendMissingImages(q.id, recoverableImages(q, includeSession = false), "upgrade")
            }
        }
    }
    /** 推理模型的思考流（浅灰小字展示；非推理模型为 null） */
    var thinkingText by mutableStateOf<String?>(null)
        private set
    /** 思考是否已结束（结束后不再自动跟随、并触发跳转到答案开头） */
    var thinkingDone by mutableStateOf(false)
        private set
    /** 正在做兜底分类请求（C）：防止重复发起 */
    private var classifying = false

    /** 供界面显示的"题目图"（base64）：原题/我的提问附图，统一作为浅绿用户气泡显示。
     *  只用于显示，不写入会话 JSON（避免数据库膨胀），旧题由 loadQuestion 从 imageBytes 注入。 */
    var questionImages by mutableStateOf<List<String>>(emptyList())
        private set

    /** 当前这道题是否"拍照题"：是则题目气泡只显示原图，不显示 AI 转译题干（题干仍保存，供错题本/搜索/编辑用） */
    var questionFromPhoto by mutableStateOf(false)
        private set

    /** AI 推测的科目（存题时作为默认分类建议，可改/可暂不分类） */
    var suggestedCategory by mutableStateOf<String?>(null)
        private set
    /** 当前题目所属分类 id（存题/加载/详情页改分类用；null = 暂不分类） */
    var currentQuestionCategoryId by mutableStateOf<Long?>(null)
        private set
    /** 当前题目的原始时间戳（loadQuestion 时记录，更新时保持原值，避免时间戳被刷新） */
    private var currentQuestionCreatedAt = 0L
    /** AI 推测的知识点标签（存题时作为默认候选，可改） */
    var suggestedTags by mutableStateOf<List<String>>(emptyList())
        private set
    /** 当前题目的知识点标签 id 列表（存题/加载/详情页改标签用） */
    var currentQuestionTags by mutableStateOf<List<Long>>(emptyList())
        private set

    /** 框选用：暂存拍照生成的图片文件路径（单张旧路径，保留兼容） */
    var pendingImagePath by mutableStateOf<String?>(null)
        private set
    fun updatePendingImagePath(path: String) { pendingImagePath = path }

    // ---- 框选流程：一次 1~2 张（拍题 / 批改 的「两张」模式，两张都各自过框选）----
    /** 待框选的图片路径（有序：单张 = 题目；两张 = 题目、我的作答） */
    var cropPaths by mutableStateOf<List<String>>(emptyList())
        private set
    /** 当前正在框选第几张（0 基） */
    var cropIndex by mutableStateOf(0)
        private set
    /** 本次流程是否属于批改（决定框选完成后走 startGrade 还是 solve） */
    var cropGrade by mutableStateOf(false)
        private set
    /** 期望张数：两张模式 = 2；其余 = 实际选择张数 */
    var cropExpect by mutableStateOf(1)
        private set
    /** 还差一张（两张模式只给了一张）→ 界面应回到拍摄界面继续拍/选 */
    var cropNeedsMore by mutableStateOf(false)
        private set
    private val cropResults = mutableListOf<ByteArray>()
    private val captureFlowStore by lazy { com.zsz.studyassistant.data.CaptureFlowStore(getApplication()) }
    private val captureRestoreMutex = kotlinx.coroutines.sync.Mutex()
    private var captureRecoveryChecked = false
    private var captureStartedAt = 0L
    private var captureFlowRevision = 0L
    private var captureWriteJob: kotlinx.coroutines.Job? = null
    var captureRecoveryNoticeKey by mutableStateOf<String?>(null)
        private set
    fun consumeCaptureRecoveryNotice() { captureRecoveryNoticeKey = null }

    /** Restore only the input/crop queue. Recovery must never start an AI request. */
    suspend fun restoreCaptureFlowIfNeeded() = captureRestoreMutex.withLock {
        if (captureRecoveryChecked || cropPaths.isNotEmpty()) return@withLock
        val revision = captureFlowRevision
        val restored = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { captureFlowStore.read() }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) {
            if (revision == captureFlowRevision) captureRecoveryNoticeKey = "camera.recoveryReadFailed"
            null
        }
        if (revision != captureFlowRevision) return@withLock
        captureRecoveryChecked = true
        if (restored != null) {
            cropPaths = restored.paths
            cropIndex = restored.index
            cropGrade = restored.grade
            cropExpect = restored.expect
            cropNeedsMore = restored.needsMore
            cropResults.clear(); cropResults.addAll(restored.results)
            captureStartedAt = restored.createdAt
            captureIdentity = restored.questionId?.let { it to restored.createdAt }
        }
    }

    private fun checkpointCaptureFlow() {
        if (cropPaths.isEmpty()) return
        captureRecoveryChecked = true
        val revision = ++captureFlowRevision
        val ticket = captureFlowStore.reserve()
        val state = com.zsz.studyassistant.data.CaptureFlowState(cropPaths.toList(), cropIndex, cropGrade,
            cropExpect, cropNeedsMore, cropResults.toList(), captureIdentity?.first, captureStartedAt)
        captureWriteJob?.cancel()
        captureWriteJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val job = kotlinx.coroutines.currentCoroutineContext()
                    captureFlowStore.write(state, ticket) { job.ensureActive() }
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) {
                if (revision == captureFlowRevision) captureRecoveryNoticeKey = "camera.recoveryWriteFailed"
            }
        }
    }

    /** Test/lifecycle callers can wait for the latest durable checkpoint without blocking Main. */
    internal suspend fun awaitCaptureCheckpoint() { captureWriteJob?.join() }


    val currentCropPath: String? get() = cropPaths.getOrNull(cropIndex)
    internal val cropRevision: Long get() = captureFlowRevision
    /**
     * 是否还有待框选的图。
     * 注意：submitCropResult 里"前进"是立刻发生的，所以提交完第一张后
     * currentCropPath 已经指向第二张 —— 这里就用它判断，不要再比较 index 与 size。
     */
    val cropHasNext: Boolean get() = currentCropPath != null
    val cropTotal: Int get() = cropPaths.size
    val cropPos: Int get() = cropIndex + 1

    fun startCropFlow(paths: List<String>, grade: Boolean, expect: Int = paths.size) {
        val firstTime = System.currentTimeMillis()
        captureStartedAt = firstTime
        captureIdentity = if (!grade) QuestionIdentity.create(firstTime) to firstTime else null
        cropPaths = paths
        cropIndex = 0
        cropGrade = grade
        cropExpect = maxOf(expect, paths.size).coerceAtLeast(1)
        cropNeedsMore = false
        cropResults.clear()
        checkpointCaptureFlow()
    }

    /** 两张模式下又拍到 / 选到第 2 张 */
    fun appendCropPath(path: String) {
        // ★ 只在"确实还缺一张"时追加（cropNeedsMore），并把索引指向**刚追加的这张**：
        //   否则框选页仍显示上一张（曾经：第一张提交后守卫直接 return，第二张永远进不来/显示错图）
        if (!cropNeedsMore) return
        cropPaths = cropPaths + path
        cropIndex = cropPaths.size - 1
        cropNeedsMore = false
        checkpointCaptureFlow()
    }

    /** 提交当前这张的框选结果：还有下一张就前进；否则凑够张数就进入解题/批改，不够就回拍摄界面 */
    fun submitCropResult(bytes: ByteArray) {
        cropResults.add(bytes)
        if (cropIndex + 1 < cropPaths.size) {
            cropIndex += 1
        } else if (cropResults.size < cropExpect) {
            cropNeedsMore = true
        } else {
            finishCropFlow()
        }
        if (cropPaths.isNotEmpty()) checkpointCaptureFlow()
    }

    /** 长按「整张图片」：把剩下的（最多两张）全部按整图提交 */
    fun skipRemainingCrop(decode: (String) -> ByteArray?): Boolean {
        if (currentCropPath == null) return false
        // Commit the batch only after every input decoded; dropping one changes question/answer roles.
        val decoded = cropPaths.drop(cropIndex).map { decode(it)?.takeIf { bytes -> bytes.isNotEmpty() } ?: return false }
        cropResults.addAll(decoded)
        cropIndex = cropPaths.size
        if (cropResults.size < cropExpect) { cropNeedsMore = true; checkpointCaptureFlow() } else finishCropFlow()
        return true
    }

    // History is created at initial input, before requesting AI. Both tables share
    // the permanent problem ID. Subsequent calls may only update an existing row.
    // Notebook/review loads explicitly disable history writes.
    private fun recordSolveSession(source: String, create: Boolean = false) {
        val binding = sessionRecordBinding
        if (!binding.historyAllowed) return
        val problemId = binding.id ?: return
        val imgs = when {
            questionImages.isNotEmpty() -> questionImages.map { Base64.decode(it, Base64.NO_WRAP) }
            multiImages.isNotEmpty() -> multiImages
            directImages.isNotEmpty() -> directImages
            imageBytes != null -> listOf(imageBytes!!)
            else -> emptyList()
        }
        val q = questionText.ifBlank { if (imgs.isEmpty()) return else "（图片题）" }
        val row = SolveSession(
            id = problemId, createdAt = binding.createdAt,
            updatedAt = System.currentTimeMillis(), source = binding.source ?: source,
            text = q, answer = lastAnswer(), conversationJson = json.encodeToString(chatItems),
            imageBytes = imgs.firstOrNull(),
            imagesJson = if (imgs.size > 1) json.encodeToString(imgs.map {
                Base64.encodeToString(it, Base64.NO_WRAP)
            }) else null,
            savedQuestionId = savedQuestionId
        )
        hasSaveActivity = true
        val ticket = historyWriteQueue.enqueue(row, create)
        failedHistoryWrites = historyWriteQueue.failedTickets().size
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            withContext(kotlinx.coroutines.NonCancellable) { persistHistoryTicket(ticket) }
        }
    }

    private suspend fun persistHistoryTicket(ticket: com.zsz.studyassistant.data.HistoryWriteQueue.Ticket) {
        saveOperations++
        try { solveSessionWriteMutex.withLock {
            if (!historyWriteQueue.isCurrent(ticket)) return@withLock
            try {
                pendingSaveRepository.saveHistory(ticket.row, historyWriteQueue.canCreate(ticket), historyCutoff())
                historyWriteQueue.complete(ticket)
            } catch (e: CancellationException) {
                historyWriteQueue.fail(ticket)
                throw e
            } catch (_: Exception) {
                // Retain the snapshot for explicit retry; never expose exception payloads or question text.
                historyWriteQueue.fail(ticket)
            } finally {
                failedHistoryWrites = historyWriteQueue.failedTickets().size
                refreshPendingSaveState()
            }
        } } finally { saveOperations-- }
    }

    fun retryFailedHistoryWrites() {
        if (retryingHistoryWrites) return
        retryingHistoryWrites = true
        val tickets = historyWriteQueue.failedTickets()
        viewModelScope.launch {
            try {
                tickets.forEach { persistHistoryTicket(it) }
                pendingNotebookContent.values.toList().forEach { persistNotebookContent(it) }
                solveSessionWriteMutex.withLock {
                    try { pendingSaveRepository.retryAll(historyCutoff()) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { saveJournalIssue = true }
                    refreshPendingSaveState()
                }
            } finally { retryingHistoryWrites = false }
        }
    }

    fun deleteHistoryRecords(ids: List<QuestionId>) { deleteQueue.stage(histories=ids.toSet()) }

    /** 记录被裁剪掉时要顺手把 [currentSessionId] 松开，否则后续 UPDATE 会写进一条不存在的行 */
    private suspend fun dropCurrentSessionIfPruned(deleted: List<QuestionId>) {
        historyWriteQueue.forget(deleted)
        failedHistoryWrites = historyWriteQueue.failedTickets().size
        refreshPendingSaveState()
        if (currentSessionId in deleted) sessionRecordBinding.historyAllowed = false
    }

    /** 启动裁剪（挂在 [purgeDeletedData] 旁边）：保留最近 [SOLVE_SESSION_KEEP] 条 / 最近 [SOLVE_SESSION_KEEP_DAYS] 天 */
    private fun pruneSolveSessions() {
        viewModelScope.launch {
            solveSessionWriteMutex.withLock { runCatching {
                val cutoff = System.currentTimeMillis() - SOLVE_SESSION_KEEP_DAYS * DAY_MS
                val ids = solveSessionsToDelete(dao.solveSessionsLightOnce(), cutoff, SOLVE_SESSION_KEEP)
                if (ids.isNotEmpty()) {
                    withContext(Dispatchers.IO) { pendingSaveStore.invalidateHistory(ids) }
                    dao.deleteSolveSessionsByIds(ids)
                }
                dropCurrentSessionIfPruned(ids)
            } }
        }
    }

    /**
     * 已存记录读取对应错题的最新内容；对应错题不存在 / 已软删除时，独立查看记录。
     * 返回 true 后调用方才导航，期间保留 opening，避免重复打开。
     */
    suspend fun loadSolveSession(s: SolveSession): Boolean {
        if (!canOpenSavedQuestion(s.id)) return false
        stopActiveGeneration()
        val seq = callSeq
        try {
            val linked = dao.heavyOnce(s.id)
            if (seq != callSeq) return false
            if (linked != null && !linked.deleted) {
                // 用户选择 A：以错题本最新内容为准，但继续绑定这条搜题记录。
                loadQuestion(linked, historySession = s)
                return true
            }
            // 对应错题已删除或不存在：记录仍可查看，不再回写失效的错题 id。
            resetSession()
            sessionRecordBinding = SolveSessionBinding(
                s.id, s.createdAt, s.source, historyAllowed = true
            )
            exitRedo()
            isPhoto = s.source == "camera"
            askMode = s.source == "ask"
            chatItems = runCatching {
                json.decodeFromString<List<ChatItem>>(s.conversationJson.orEmpty())
            }.getOrDefault(emptyList())
            if (chatItems.isEmpty()) {
                chatItems = listOf(ChatItem(0, "question", s.text), ChatItem(1, "assistant", s.answer))
            }
            questionText = s.text
            imageBytes = s.imageBytes
            val multiKeys = s.imagesJson?.let { json.decodeFromString<List<String>>(it) }.orEmpty()
            questionImages = mergeImageKeys(
                s.imageBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                multiKeys + questionImageBase64(s.conversationJson)
            )
            directImages = questionImages.map { Base64.decode(it, Base64.NO_WRAP) }
            questionFromPhoto = isPhoto && questionImages.isNotEmpty()
            currentQuestionCreatedAt = s.createdAt
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = com.zsz.studyassistant.ui.stringsFor(uiLang)
                .format("data.failed", "msg" to (e.message ?: ""))
            return false
        }
    }

    /**
     * 统一的会话准备：所有生成入口都从这里开始。
     * 之前每个入口各自 resetSession() + 手工设置 isPhoto/gradeMode/isFromNotebook/questionFromPhoto，
     * 容易漏项（如"带着上一轮的 gradeMode"，曾踩过）——现在集中在这里。
     */
    private fun prepareSession(
        photo: Boolean = false,
        grade: Boolean = false,
        ask: Boolean = false,
        fromNotebook: Boolean = false
    ) {
        val retry = retryBinding
        val capture = if (photo && !ask && !grade) captureIdentity else null
        resetSession()
        val firstTime = capture?.second ?: System.currentTimeMillis()
        sessionRecordBinding = retry ?: SolveSessionBinding(
            id = capture?.first ?: QuestionIdentity.create(firstTime), createdAt = firstTime,
            historyAllowed = !grade && !fromNotebook
        )
        if (capture != null) captureIdentity = null
        currentQuestionCreatedAt = sessionRecordBinding.createdAt
        isPhoto = photo
        gradeMode = grade
        askMode = ask
        isFromNotebook = fromNotebook
        questionFromPhoto = photo
    }

    /** 主页「图文提问」入口：开一个空会话并标记为图文提问（标题保持"图文提问"） */
    fun startAskSession() {
        stopActiveGeneration()   // 新会话前先停掉可能在跑的上一轮
        // ★ 2026-10-13 修复（用户报「图文提问不提问直接回退，又回到拍照界面」）：
        //   原来走 `startNewQuestion()`，它**不复位 isPhoto** → 上一轮拍过题时 isPhoto 仍是 true →
        //   SolveScreen 的返回按钮判定 `!vm.isPhotoSession` 为假 → 走"回拍题界面"分支。
        //   改用 prepareSession：它是 startNewQuestion 的超集，且显式 `isPhoto = false`。
        prepareSession(ask = true)
    }

    /**
     * 向 AI 要一次「分类 / 标签」建议，**只写入会话内的 [suggestedCategory] / [suggestedTags]**（供存题对话框预选）。
     *
     * ★ 2026-10-14（用户要求）：**不再写回数据库**，也**不再**在"打开旧题 / 打开分类对话框"时调用。
     *   原机制有两个落地问题：① 打开已存的题时擅自改写它的分类（"暂不分类存的题被 AI 改掉"）；
     *   ② 在分类对话框打开期间异步返回，把用户刚取消勾选的标签"复活"（取消勾选时常失灵）。
     *   现在唯一的调用点是**解答完成**时（`continueGeneration` 的 onDone），拿到的建议只影响"还没存"的题。
     */
    // Replaceable request boundaries let integration fixtures control completion without network access.
    internal var classifyRequest: suspend (String, List<String>, List<String>, List<ByteArray>, Boolean, Boolean) -> Pair<String?, List<String>> =
        { q, categories, tags, images, category, tag -> StudyAssistant.classifyQuestion(q, categories, tags, images, category, tag) }
    private var classificationJob: Job? = null
    private var classificationOwner: Any? = null

    fun classifyCurrentQuestion(needCategory: Boolean = true, needTags: Boolean = true) {
        if (classifying) return
        val q = questionText
        val imgs = when {
            directImages.isNotEmpty() -> directImages
            multiImages.isNotEmpty() -> multiImages
            imageBytes != null -> listOf(imageBytes!!)
            else -> emptyList()
        }
        if (q.isBlank() && imgs.isEmpty()) {
            return
        }
        val owner = Any()
        val binding = sessionRecordBinding
        val categories = categoryNames.toList()
        val tags = tagNames.toList()
        val images = imgs.map { it.copyOf() }
        val request = classifyRequest
        classificationOwner = owner
        classifying = true
        classificationJob = viewModelScope.launch {
            try {
                val (c, t) = request(q, categories, tags, images, needCategory, needTags)
                if (classificationOwner !== owner || sessionRecordBinding !== binding) return@launch
                // 已有分类（来自库/用户选择）优先，AI 只补空的
                if (!c.isNullOrBlank() && suggestedCategory == null) suggestedCategory = c
                if (t.isNotEmpty()) suggestedTags = t
            } finally {
                // ★ 必须放 finally：请求异常/被取消时也要复位，否则标志卡死 → 之后所有分类都被跳过
                if (classificationOwner === owner && sessionRecordBinding === binding) {
                    classificationOwner = null
                    classificationJob = null
                    classifying = false
                }
            }
        }
    }
    /** 两张模式：从第 2 张返回第 1 张重新框（丢弃第 1 张已提交的结果，保持队列一致） */
    fun cropGoBackOne() {
        if (cropIndex <= 0) return
        if (cropResults.isNotEmpty()) cropResults.removeAt(cropResults.size - 1)
        cropIndex -= 1
        cropNeedsMore = false
        checkpointCaptureFlow()
    }

    /**
     * 回到主页时调用：清空所有拍摄/框选缓存 ——
     * 内存里的框选队列、已框结果、待框路径、追问附图，以及缓存目录里的临时图片文件。
     * （用户要求：回主页不保留任何"已框或未框"的图）
     */
    /**
     * 中止当前生成并清掉流式/思考状态。
     * 用于：**从解题页返回主页**、开始新会话时 —— 否则"回到主页后 AI 还在思考"，
     * 再进图文提问会看到上一轮仍在流式。
     */
    /** 当前是否开启思考模式（设置 → AI 配置 → **解题模式**）：快速=关闭 → 界面不渲染思考块 */
    val thinkingEnabled: Boolean get() = com.zsz.studyassistant.data.CapturePrefs.solveModel(getApplication()) != "flash"

    fun stopActiveGeneration() {
        classificationOwner = null
        classificationJob?.cancel()
        classificationJob = null
        classifying = false
        checkpointDraft()
        activeDraft=null
        val stopping = streamOwner
        stopping?.cancel()
        streamOwner = null
        callSeq++                 // 让旧请求收尾时不再覆盖界面状态
        callJob?.cancel()
        callJob = null
        busy = false
        streamingText = null
        thinkingText = null
        thinkingDone = false
        streamInterrupted = false
        networkError = false
        contMessages = null
        contOnDone = null
        contPrefix = ""
        stopping?.let { com.zsz.studyassistant.data.AnswerForegroundService.stop(getApplication(), it.id) }
    }
    fun clearCaptureCaches() {
        cancelCropFlow()
        pendingImagePath = null
        directImages = emptyList()
        runCatching {
            getApplication<android.app.Application>().cacheDir.listFiles()?.forEach { f ->
                val n = f.name
                if (n.startsWith("capture") || n.startsWith("gallery") || n.startsWith("pick")) {
                    runCatching { f.delete() }
                }
            }
        }
    }

    fun cancelCropFlow() {
        captureRecoveryChecked = true
        val revision = ++captureFlowRevision
        val ticket = captureFlowStore.reserve()
        captureWriteJob?.cancel()
        captureWriteJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { captureFlowStore.clear(ticket) }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) {
                if (revision == captureFlowRevision) captureRecoveryNoticeKey = "camera.recoveryClearFailed"
            }
        }
        cropPaths = emptyList()
        cropIndex = 0
        cropGrade = false
        cropExpect = 1
        cropNeedsMore = false
        cropResults.clear()
        captureIdentity = null
        captureStartedAt = 0L
    }

    private fun finishCropFlow() {
        val imgs = cropResults.toList()
        val grade = cropGrade
        val identity = captureIdentity
        cancelCropFlow()
        captureIdentity = identity
        if (imgs.isEmpty()) return
        if (grade) {
            if (imgs.size >= 2) startGrade(imgs[0], imgs[1]) else startGrade(imgs[0], null)
        } else {
            if (imgs.size >= 2) solveWithImages(imgs) else solveWithImage(imgs[0])
        }
    }

    fun clearError() { error = null }
    fun showError(msg: String) { error = msg }
    fun hasApiKey(): Boolean = KeyManager.getApiKey().isNotBlank()

    /** 开始新一题：清空当前对话与状态 */
    fun startNewQuestion() {
        saveSessionOnExit()
        stopActiveGeneration()
        resetSession()
    }

    /** 从当前 chatItems + 图片来源构建发给模型的对话历史（题目 + 问答） */
    /**
     * 用户自定义要求（设置 → AI 管理）：让 AI 遵守个人偏好（只给思路 / 步骤编号 / 更简洁 …）。
     *
     * ★ 2026-10-13 修复：原先这里叫 `customPromptMessage()` 并且**从来没有被任何地方调用**
     *   → 用户填的「自定义要求」实际上一次都没发给模型。现在改为取原文，由 [buildMessages] 拼进
     *   `languageSystemMessage(aiLang, customPrompt)`（合成一条 system 消息）。
     */
    private fun customPromptText(): String =
        com.zsz.studyassistant.data.CapturePrefs.customPrompt(getApplication()).trim()

    private fun buildMessages(items: List<ChatItem> = chatItems): List<DeepSeekMessage> {
        val msgs = mutableListOf<DeepSeekMessage>()
        // 回答语言 + 用户自定义要求：统一在这里注入，拍题/直接提问/追问/重新生成都走这条路径
        msgs.add(StudyAssistant.languageSystemMessage(aiLang, customPromptText()))
        if (multiImages.isNotEmpty()) {
            // 多张搜题：多图一起作为题目（同一套搜题指令）
            msgs.add(StudyAssistant.visionUserMessageMulti(multiImages, categoryNames, tagNames))
        } else if (directImages.isNotEmpty()) {
            // 直接提问：文字 + 多图一起作为问题
            // ★ 带图的「直接提问」原来用追问提示词（不问分类/知识点）→ 改用搜题指令，保证与拍题一致
            msgs.add(
                StudyAssistant.userMessageWithImages(
                    StudyAssistant.solvePrompt(categoryNames, tagNames) +
                        "\n【我的题目/问题】" + questionText,
                    directImages
                )
            )
        } else if (isPhoto && imageBytes != null) {
            msgs.add(StudyAssistant.visionUserMessage(imageBytes!!, categoryNames, tagNames))
        } else if (questionText.isNotBlank()) {
            msgs.add(StudyAssistant.textSolveMessage(questionText, categoryNames, tagNames))
        }
        msgs.addAll(chatMessages(items))
        return msgs
    }

    private fun chatMessages(items: List<ChatItem>): List<DeepSeekMessage> {
        val msgs = mutableListOf<DeepSeekMessage>()
        for (item in items) {
            when (item.role) {
                "assistant" -> msgs.add(DeepSeekMessage("assistant", JsonPrimitive(item.content)))
                "user" -> {
                    val imgs = item.images
                    if (imgs.isNullOrEmpty()) {
                        msgs.add(DeepSeekMessage("user", JsonPrimitive(item.content)))
                    } else {
                        // 追问带有附图的 user 消息 → 视觉模型（文字 + 多图）
                        val bytes = imgs.map { Base64.decode(it, Base64.NO_WRAP) }
                        msgs.add(StudyAssistant.userMessageWithImages(item.content, bytes))
                    }
                }
                // "question" 已作为题目消息
            }
        }
        return msgs
    }

    private fun friendlyError(e: Exception, fallback: String): String {
        val s = com.zsz.studyassistant.ui.stringsFor(uiLang)
        val msg = e.message.orEmpty().lowercase()
        return when {
            e is com.zsz.studyassistant.data.StreamIncompleteException -> s[e.messageKey]
            msg.contains("timed out") || msg.contains("timeout") || msg.contains("socket") -> s["err.timeout"]
            msg.contains("failed to connect") || msg.contains("unreachable") || msg.contains("connect") -> s["err.connect"]
            msg.contains("api key") || msg.contains("unauthorized") || msg.contains("401") -> s["err.apikey"]
            msg.contains("返回为空") || msg.contains("returned nothing") -> s["err.emptyReply"]
            else -> e.message ?: fallback
        }
    }

    private fun addItem(role: String, content: String, images: List<String>? = null, thinking: String? = null) {
        // 助手消息默认带上本轮思考（这样所有调用方都不用改，避免"正文一出思考就没了"）
        val th = thinking ?: if (role == "assistant") thinkingText?.takeIf { it.isNotBlank() } else null
        chatItems = chatItems + ChatItem(chatItems.size.toLong(), role, content, images, th)
    }

    private fun resetSession() {
        currentNotebookBaseline=null
        sessionRecordBinding = SolveSessionBinding()
        askMode = false
        chatItems = emptyList()
        imageBytes = null
        questionText = ""
        error = null
        savedToNotebook = false
        savedQuestionId = null
        isFromNotebook = false
        isDeleted = false
        isPhoto = false
        suggestedCategory = null
        suggestedTags = emptyList()
        currentQuestionCategoryId = null
        currentQuestionTags = emptyList()
        currentQuestionCreatedAt = 0L
        reviewMode = false
        gradeMode = false
        directImages = emptyList()
        multiImages = emptyList()
        questionImages = emptyList()
        questionFromPhoto = false
    }

    /**
     * 流式生成的中间文本：非 null 时界面把它当作「正在生成」的助手气泡实时渲染。
     * null = 当前没有流式（常规整段等待，或已结束）。
     */
    var streamingText by mutableStateOf<String?>(null)
        private set

    /** 生成被中断（用户点「中止生成」或网络异常），已生成内容保留、可点「继续生成」 */
    var streamInterrupted by mutableStateOf(false)
        private set

    private var callJob: Job? = null
    private var streamOwner: com.zsz.studyassistant.data.StreamOwner? = null

    // 「继续生成」所需的上文：原消息 + 已生成片段 + 完成回调
    private var contMessages: List<DeepSeekMessage>? = null
    private var contModel: String = ""
    private var contPrefix: String = ""
    private var contDraftAction: String = "initial"
    private var contOnDone: ((String) -> Unit)? = null

    /** 中止生成：立即关闭响应流（模型思考中也能立刻停）+ 取消协程；已生成内容保留 */
    fun abortGeneration() {
        // ★ 中止时：从已生成的文本里抢救「分类/知识点」，避免中止后存错题本时丢失预选
        harvestSuggestions(streamingText.orEmpty())
        streamOwner?.cancel()
        callJob?.cancel()
    }

    /**
     * 丢弃「已中止 / 思考中」的那条流式气泡（用户 2026-10-13 要求：中止后这条气泡要能选中删除）。
     *
     * 背景：中止后那条气泡**只存在于渲染层**（`streamingText` + `thinkingText`），它不在 `chatItems` 里，
     * 所以多选/删除（都按 `chatItems` 下标操作）覆盖不到它，长按还会被守卫拒绝。
     * 用户明确删掉它时调用这里：清掉中断态与「继续生成」的上下文 → 气泡消失。
     * 不把它固化进 `chatItems`：`continueGeneration()` 依赖 `contPrefix` + `streamingText` 重放，
     * 固化后会重复出现两次（见 FIXES 记录里的取舍说明）。
     */
    fun discardInterrupted() {
        activeDraft?.let { value -> viewModelScope.launch { withContext(Dispatchers.IO) { conversationDrafts.clear(value.id,value.generation) }; refreshDrafts() } }
        activeDraft=null
        streamInterrupted = false
        streamingText = null
        thinkingText = null
        thinkingDone = false
        contMessages = null
        contOnDone = null
        contPrefix = ""
    }

    /** 继续生成：把已生成部分作为 assistant 上文，要求模型接着往下写（不重复、不重开） */
    fun continueGeneration() {
        val msgs = contMessages ?: return
        val onDone = contOnDone ?: return
        // ★ 包一层：续写完成后把**同一条**搜题记录也更新掉（否则记录里永远停在被中止时的半截答案）
        val wrapped: (String) -> Unit = { out -> onDone(out); updateSolveSession() }
        streamCall(contModel, msgs, contPrefix, wrapped, repeat = null, replyAction=contDraftAction)
    }

    private fun continuationMessages(original: List<DeepSeekMessage>, partial: String): List<DeepSeekMessage> {
        // 一个字都没生成（还在「思考中…」就被中止）：直接重发原请求，不附加空的 assistant 消息
        if (partial.isBlank()) return original
        return original +
            DeepSeekMessage("assistant", JsonPrimitive(partial)) +
            DeepSeekMessage(
                "user",
                JsonPrimitive("请接着上面未写完的内容继续输出，从中断处直接续写；不要重复已经写过的部分，也不要重新开始。")
            )
    }

    private fun runCall(model: String, onDone: (String) -> Unit, repeat: (() -> Unit)? = null) {
        // 非批改的流式请求（解题/追问/重新生成/继续）：按钮显示「⏸ 中止生成」
        busyIsGrade = false
        val messages = buildMessages().toList()
        streamCall(model, messages, prefix = "", onDone = onDone, repeat = repeat)
    }

    /**
     * 流式调用主体。
     * @param prefix 之前已生成的内容（「继续生成」时把上次的片段接回来，界面显示 prefix + 新增）
     */
    private fun streamCall(
        model: String,
        messages: List<DeepSeekMessage>,
        prefix: String,
        onDone: (String) -> Unit,
        repeat: (() -> Unit)?,
        replyAction: String = if(gradeMode) "grade" else if(chatItems.any { it.role=="assistant" }) "followup" else "initial"
    ) {
        if(dataBusy) return
        streamOwner?.cancel()
        callJob?.cancel()
        val owner = com.zsz.studyassistant.data.StreamOwner()
        streamOwner = owner
        val mySeq = ++callSeq
        val draftId=sessionRecordBinding.id
        val template=draftId?.let { com.zsz.studyassistant.data.ConversationDraft(it,sessionRecordBinding.createdAt,
            owner.id,model,replyAction,currentSource(),questionText,isPhoto,askMode,gradeMode,
            sessionRecordBinding.historyAllowed,savedQuestionId,questionImages,chatItems.toList(),messages,raw=prefix,redoFor=pendingRedoFor ?: if(gradeMode) redoQuestionId else null) }
        if(template!=null) { conversationDrafts.reserve(template.id,template.generation); activeDraft=template }
        callJob = viewModelScope.launch {
            busy = true
            error = null
            networkError = false
            retryAction = null
            streamInterrupted = false
            // 前台服务：切后台也不被冻结，保证请求跑完
            val app = getApplication<Application>()
            com.zsz.studyassistant.data.AnswerForegroundService.start(app, owner.id)
            val sb = StringBuilder()
            var lastEmit = 0L
            var keepPartial = false
            streamingText = prefix
            // 思考流（推理模型才有）：每次新请求都清空
            thinkingText = null
            thinkingDone = false
            val thinkSb = StringBuilder()
            var lastThinkEmit = 0L
            try {
                if(template!=null) { writeDraft(template.copy(revision=++draftRevision),currentNotebookBaseline); refreshDrafts() }
                var lastDraftWrite=0L
                val reply = withContext(Dispatchers.IO) {
                    val thinkOn = com.zsz.studyassistant.data.CapturePrefs.solveModel(app) != "flash"
                    StudyAssistant.chatStream(model, messages, onDelta = { delta ->
                        sb.append(delta)
                        // ★ 路线 A 分流：<思考>…</思考> 进思考流，其余进正文
                        // 先把各种可能的写法统一成 <思考>…</思考>：
                        //   全角〈思考〉、HTML 转义 &lt;思考&gt;、<thinking>、DeepSeek 有时写 "思考："
                        val full = sb.toString()
                            .replace("〈思考〉", "<思考>").replace("〈/思考〉", "</思考>")
                            .replace("&lt;思考&gt;", "<思考>").replace("&lt;/思考&gt;", "</思考>")
                            .replace("<thinking>", "<思考>").replace("</thinking>", "</思考>")
                            .replace("【思考】", "<思考>").replace("【/思考】", "</思考>")
                        val ts = full.indexOf("<思考>")
                        val te = full.indexOf("</思考>")
                        var bodyText = full
                        // 兜底②：模型没写标记但用了"思考：…解答："这类纯文本写法时，也能分流
                        var ts2 = ts
                        var te2 = te
                        if (ts2 < 0) {
                            val m = Regex("(?:^|\\n)\\s*(?:思考|分析|思路)[:：]").find(full)
                            val mEnd = Regex("\\n\\s*(?:解答|答案|题目)[:：]").find(full)
                            if (m != null && mEnd != null && mEnd.range.first > m.range.last) {
                                ts2 = m.range.first; te2 = mEnd.range.first
                                val think = full.substring(m.range.last + 1, te2)
                                val nowT = System.currentTimeMillis()
                                if (nowT - lastThinkEmit >= 120) {
                                    lastThinkEmit = nowT
                                    withContext(Dispatchers.Main) { thinkingText = think.trim() }
                                }
                            }
                        }
                        if (ts2 >= 0) {
                            val think = if (te2 > ts2) full.substring(ts2 + 4, te2) else full.substring(ts2 + 4)
                            val nowT = System.currentTimeMillis()
                            if (nowT - lastThinkEmit >= 120) {
                                lastThinkEmit = nowT
                                withContext(Dispatchers.Main) { thinkingText = think }
                            }
                            bodyText = if (te2 > ts2) full.substring(te2 + 5) else ""
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 180) {
                            lastEmit = now
                            val snapshot = prefix + bodyText
                            withContext(Dispatchers.Main) {
                                if(mySeq==callSeq) {
                                    streamingText = snapshot
                                    activeDraft=activeDraft?.copy(raw=snapshot,thinking=thinkingText)
                                    if(now-lastDraftWrite>=1000) { lastDraftWrite=now; checkpointDraft(refresh=false) }
                                }
                            }
                        }
                    }, thinkingEnabled = thinkOn, owner = owner, onReasoning = { r ->
                        thinkSb.append(r)
                        val now = System.currentTimeMillis()
                        if (now - lastThinkEmit >= 120) {
                            lastThinkEmit = now
                            val snap = thinkSb.toString()
                            withContext(Dispatchers.Main) { if(mySeq == callSeq) thinkingText = snap }
                        }
                    })
                }
                if (mySeq != callSeq) return@launch
                thinkingDone = true
                recordUsage(app, owner.usage)
                // ⚠️ 这里原先有一段「把 thinkingText 挂到 indexOfLast{assistant}」的代码，已删除：
                //   调用点在 onDone 之前，此刻 chatItems 里最后一条助手消息是**上一轮的** →
                //   追问时会把上一轮答案的思考块覆盖成本轮思考。
                //   本轮思考由 addItem() 的默认值自动挂到**新建的那条**助手消息上（见 addItem）。
                onDone(prefix + reply.replace(Regex("<思考>[\\s\\S]*?</思考>"), "").trim())
                if(mySeq==callSeq) {
                    val completed=activeDraft?.copy(raw=prefix+reply,complete=true,completedChats=chatItems.toList(),completedQuestion=questionText)
                    activeDraft=completed
                    completed?.let { writeDraft(it.copy(revision=++draftRevision),currentNotebookBaseline) }
                    withContext(kotlinx.coroutines.NonCancellable) { persistCompletedConversation() }
                    if(template!=null && completed!=null && draftCanBeCleared(completed)) {
                        finishDraftCleanup(completed)
                    }
                    refreshDrafts()
                }
                // ★ 分类兜底（C）：正文里没给出「分类：/知识点：」时，解答完成即后台补一次，
                //   这样等用户去点「存错题本」时建议已经就绪（不必在对话框里干等）
                if (suggestedCategory == null && questionText.isNotBlank()) classifyCurrentQuestion()
                // 放在 onDone 之后：万一 onDone 内部抛异常，已生成的内容仍留在界面上（catch 会保留）
                streamingText = null
                contMessages = null
                contOnDone = null
                contPrefix = ""
            } catch (e: Exception) {
                // 用户中止时协程已被取消（关闭响应体导致的 IOException 也走这里）：
                // 统一按「中断」处理——**甚至一个字都没生成时也保留气泡**（界面显示「思考中…」+ 蓝色「继续生成」）
                val aborted = e is CancellationException || !currentCoroutineContext().isActive
                val partial = prefix + sb
                // 有内容 → 保留；零输出但属于「用户主动中止」→ 也保留（气泡不消失）；零输出且是失败 → 走错误分支
                keepPartial = partial.isNotBlank() || aborted || e is com.zsz.studyassistant.data.StreamIncompleteException
                // 已被新请求取代的旧请求：不改界面状态（否则会把新请求的「思考中…」/内容冲掉）
                if (mySeq == callSeq) {
                    activeDraft=activeDraft?.copy(raw=partial,thinking=thinkingText)
                    activeDraft?.let { writeDraft(it.copy(revision=++draftRevision),currentNotebookBaseline) }
                    withContext(kotlinx.coroutines.NonCancellable) { refreshDrafts() }
                    contDraftAction=replyAction
                    if (keepPartial) {
                        streamInterrupted = true
                        contModel = model
                        contMessages = continuationMessages(messages, partial)
                        contPrefix = partial
                        contOnDone = onDone
                        // 用完整累积文本刷新一次界面（可能是空串 → 界面显示「思考中…」+ 蓝色「继续生成」）
                        streamingText = partial
                    } else {
                        streamingText = null
                    }
                    if (aborted) {
                        error = null
                        retryAction = null
                    } else {
                        error = friendlyError(e, com.zsz.studyassistant.ui.stringsFor(uiLang)["err.requestFailed"])
                        val msg = e.message.orEmpty().lowercase()
                        if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("connect") ||
                            msg.contains("unreachable") || msg.contains("socket") || msg.contains("network")
                        ) {
                            networkError = true
                            val failedBinding = sessionRecordBinding
                            retryAction = repeat?.let { action -> {
                                if (sessionRecordBinding === failedBinding) {
                                    retryBinding = failedBinding
                                    try { action() } finally { retryBinding = null }
                                }
                            } }
                        }
                    }
                }
                if (e is CancellationException) throw e
            } finally {
                // 已被新请求取代的旧请求：不要动界面状态（避免把新请求的气泡/忙碌态清掉）
                if (mySeq == callSeq) {
                    busy = false
                    if (!keepPartial) streamingText = null
                    callJob = null
                }
                com.zsz.studyassistant.data.AnswerForegroundService.stop(app, owner.id)
            }
        }
    }

    /** 网络断开后点击"继续生成"：用最后一次提问内容重新调用 */
    fun retry() {
        retryAction?.invoke()
    }

    override fun onCleared() {
        val readJob = backupReadJob
        val exportJob = backupExportJob
        val importDirectory = preparedBackup?.directory
        val exportDirectory = backupExportDirectory
        readJob?.cancel()
        exportJob?.cancel()
        // A picker/preview can outlive the operation job; clean only its private UUID folders.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            readJob?.join()
            exportJob?.join()
            removeBackupWorkspace(importDirectory)
            removeBackupWorkspace(exportDirectory)
        }
        streamOwner?.let { it.cancel(); com.zsz.studyassistant.data.AnswerForegroundService.stop(getApplication(),it.id) }
        similarStreamOwner?.let { it.cancel(); com.zsz.studyassistant.data.AnswerForegroundService.stop(getApplication(),it.id) }
        super.onCleared()
    }

    // ---- API 用量统计（累计调用次数与 token）----
    var usageCalls by mutableStateOf(0)
        private set
    var usagePromptTokens by mutableStateOf(0)
        private set
    var usageCompletionTokens by mutableStateOf(0)
        private set

    /** 每次接口调用后：写入统计并刷新界面状态 */
    private fun recordUsage(app: Application, usage: com.zsz.studyassistant.data.DeepSeekUsage?) {
        com.zsz.studyassistant.data.UsageStats.add(app, usage)
        refreshUsage()
    }

    fun refreshUsage() {
        val s = com.zsz.studyassistant.data.UsageStats.load(getApplication())
        usageCalls = s.calls
        usagePromptTokens = s.promptTokens
        usageCompletionTokens = s.completionTokens
    }

    fun resetUsage() {
        com.zsz.studyassistant.data.UsageStats.reset(getApplication())
        refreshUsage()
    }

    // ---- 数据管理：导出 / 导入 / 清空 ----
    var dataBusy by mutableStateOf(false)
        private set
    /** 备份管理子页自己的结果文案（导出 / 导入） */
    var dataMessage by mutableStateOf<String?>(null)
        private set
    var dataMessageIsError by mutableStateOf(false)
        private set
    /**
     * 数据升级 / 修复子页的结果文案 —— ★ 2026-10-17（用户反馈）**与备份页分开**：
     * 原来和 [dataMessage] 共用一个，导致「一键升级完成」的提示串到「备份管理」页面上显示。
     */
    var upgradeMessage by mutableStateOf<String?>(null)
        private set
    var upgradeMessageIsError by mutableStateOf(false)
        private set
    /** 「数据管理」一级页的结果文案（清空所有数据 —— 2026-10-17 从备份管理挪到一级页） */
    var menuMessage by mutableStateOf<String?>(null)
        private set
    var menuMessageIsError by mutableStateOf(false)
        private set

    fun clearDataMessage() { dataMessage = null }
    fun clearUpgradeMessage() { upgradeMessage = null }
    fun clearMenuMessage() { menuMessage = null }

    var currentDatabaseVersion by mutableStateOf<Int?>(null)
        private set
    var databaseVersionReadFailed by mutableStateOf(false)
        private set
    var hasLegacyData by mutableStateOf(false)
        private set

    private suspend fun readDatabaseVersion(): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        AppDatabase.get(getApplication()).openHelper.readableDatabase.version
    }

    fun refreshDatabaseVersion() {
        viewModelScope.launch {
            dataMaintenanceMutex.lock()
            currentDatabaseVersion = null
            databaseVersionReadFailed = false
            hasLegacyData = false
            try {
                currentDatabaseVersion = readDatabaseVersion()
                hasLegacyData = needsImageUpgrade()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                databaseVersionReadFailed = true
            } finally {
                dataMaintenanceMutex.unlock()
            }
        }
    }

    var preparedExport by mutableStateOf<com.zsz.studyassistant.data.BackupArchive.ArchiveSet?>(null)
        private set
    var preparedBackup by mutableStateOf<com.zsz.studyassistant.data.BackupRepository.PreparedImport?>(null)
        private set
    var dataProgressKey by mutableStateOf<String?>(null)
        private set
    private var backupReadJob: Job? = null
    private var backupExportJob: Job? = null
    private var backupExportDirectory: java.io.File? = null

    private fun backupWorkspace(): java.io.File = java.io.File(
        getApplication<Application>().noBackupFilesDir, "backup-manual/${java.util.UUID.randomUUID()}"
    )

    private suspend fun cleanAbandonedBackupWorkspaces(current: java.io.File) = withContext(Dispatchers.IO) {
        val root = current.parentFile ?: return@withContext
        if (!root.isDirectory) return@withContext
        val keep = setOfNotNull(current.canonicalFile, backupExportDirectory?.canonicalFile,
            preparedBackup?.directory?.canonicalFile)
        java.nio.file.Files.newDirectoryStream(root.toPath()).use { entries ->
            for (entry in entries) {
                val candidate = entry.toFile()
                if (!java.nio.file.Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                if (runCatching { java.util.UUID.fromString(candidate.name) }.isFailure) continue
                if (candidate.canonicalFile !in keep &&
                    System.currentTimeMillis() - candidate.lastModified() > 24L * 60 * 60 * 1000) {
                    removeBackupWorkspace(candidate)
                }
            }
        }
    }

    private suspend fun removeBackupWorkspace(directory: java.io.File?) {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            directory?.let {
                val root = java.io.File(getApplication<Application>().noBackupFilesDir, "backup-manual").canonicalFile
                val target = it.canonicalFile
                check(target != root && target.toPath().startsWith(root.toPath()))
                target.deleteRecursively()
            }
        }
    }

    private fun backupError(e: Exception) {
        val s = com.zsz.studyassistant.ui.stringsFor(uiLang)
        val detail = if (e is com.zsz.studyassistant.data.BackupValidationException) s[e.messageKey]
            else s["data.autoBackup.storageFailed"]
        dataMessageIsError = true
        dataMessage = s.format("data.failed", "msg" to detail)
    }

    fun prepareBackupExport(onReady: (com.zsz.studyassistant.data.BackupArchive.ArchiveSet) -> Unit) {
        if (dataBusy || preparedBackup != null || preparedExport != null) return
        dataBusy = true
        dataMessage = null
        val directory = backupWorkspace()
        backupExportJob = viewModelScope.launch {
            var ready = false
            try {
                cleanAbandonedBackupWorkspaces(directory)
                val set = com.zsz.studyassistant.data.BackupJobs.exclusive(getApplication()) {
                    com.zsz.studyassistant.data.BackupRepository(getApplication()).export(directory,
                        progress = { phase, _ -> dataProgressKey = phase })
                }
                backupExportDirectory = directory
                preparedExport = set
                ready = true
                onReady(set)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { backupError(e) }
            finally {
                if (!ready) removeBackupWorkspace(directory)
                dataBusy = false
                dataProgressKey = null
            }
        }
    }

    fun discardPreparedExport() {
        val directory = backupExportDirectory
        backupExportDirectory = null
        preparedExport = null
        viewModelScope.launch { removeBackupWorkspace(directory) }
    }

    fun writePreparedBackup(uri: android.net.Uri, tree: Boolean = false) {
        if (dataBusy) return
        val set = preparedExport ?: return
        val directory = backupExportDirectory
        dataBusy = true
        dataProgressKey = "data.backupJob.writing"
        backupExportJob = viewModelScope.launch {
            try {
                val job = kotlinx.coroutines.currentCoroutineContext()[Job]
                val stop = { job?.ensureActive(); Unit }
                com.zsz.studyassistant.data.BackupJobs.exclusive(getApplication()) {
                if (tree) {
                    val result = com.zsz.studyassistant.data.BackupDocuments.writeSet(getApplication(), set, uri, false, stop)
                    if (!result.ok) throw com.zsz.studyassistant.data.BackupValidationException(
                        result.errorKey ?: "data.autoBackup.storageFailed")
                } else {
                    check(set.files.size == 1)
                    com.zsz.studyassistant.data.BackupDocuments.writeSingle(getApplication(), set.files.single(), uri, stop)
                }
                }
                dataMessageIsError = false
                dataMessage = com.zsz.studyassistant.ui.stringsFor(uiLang)["data.backupSuccess"]
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { backupError(e) }
            finally {
                preparedExport = null
                backupExportDirectory = null
                removeBackupWorkspace(directory)
                dataBusy = false
                dataProgressKey = null
            }
        }
    }

    fun discardPreparedBackup() {
        backupReadJob?.cancel()
        val directory = preparedBackup?.directory
        preparedBackup = null
        viewModelScope.launch { removeBackupWorkspace(directory) }
    }

    fun cancelBackupOperation() { backupReadJob?.cancel(); backupExportJob?.cancel() }

    fun prepareBackupImport(uris: List<android.net.Uri>) {
        if (dataBusy || preparedBackup != null || preparedExport != null || uris.isEmpty()) return
        dataBusy = true
        dataMessage = null
        dataProgressKey = "data.backupRead"
        val directory = backupWorkspace()
        backupReadJob = viewModelScope.launch {
            var ready = false
            try {
                cleanAbandonedBackupWorkspaces(directory)
                preparedBackup = com.zsz.studyassistant.data.BackupRepository(getApplication()).prepareImport(
                    uris, directory, progress = { phase, _ -> dataProgressKey = phase })
                ready = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { backupError(e) }
            finally {
                if (!ready) removeBackupWorkspace(directory)
                dataBusy = false
                dataProgressKey = null
            }
        }
    }

    fun importBackup() {
        if (dataBusy) return
        val file = preparedBackup ?: return
        preparedBackup = null
        dataBusy = true
        dataProgressKey = "data.backupMerge"
        backupReadJob = viewModelScope.launch {
            try {
                val r = com.zsz.studyassistant.data.BackupJobs.exclusive(getApplication()) {
                    solveSessionWriteMutex.withLock {
                        requireNoPendingSaves()
                        com.zsz.studyassistant.data.BackupRepository(getApplication()).merge(file)
                    }
                }
                dataMessageIsError = false
                dataMessage = com.zsz.studyassistant.ui.stringsFor(uiLang).format("data.importDone",
                    "q" to "${r.questions}", "c" to "${r.categories}", "t" to "${r.tags}", "s" to "${r.skipped}")
                refreshDataSummary()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { backupError(e) }
            finally {
                removeBackupWorkspace(file.directory)
                dataBusy = false
                dataProgressKey = null
            }
        }
    }

    // ── 「数据需要升级」提示（2026-10-16，用户规格：更新后**第一次进入应用**弹一次）──────

    /** 启动时若"有图但还没进新表" → 弹一次提示 */
    var showUpgradePrompt by mutableStateOf(false)
        private set

    /** 供设置页使用：从提示/警示"直达"数据升级子页（设置页消费后清零） */
    var openBackupManagementPage by mutableStateOf(false)

    internal var backupSetupChecked = false

    var openUpdatePage by mutableStateOf(false)

    var openDataUpgradePage by mutableStateOf(false)

    private var upgradePromptChecked = false

    /**
     * 数据升级提示跟随 DB schema，不跟随 App 小版本。
     * 兼容以前已显示的 v9 提示；读取旧格式时仍有独立警示。
     */
    fun checkUpgradePromptOnce(currentVersion: String) {
        if (upgradePromptChecked) return
        upgradePromptChecked = true
        viewModelScope.launch {
            dataMaintenanceMutex.lock()
            try {
                val prefs = getApplication<Application>()
                    .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                val schema = AppDatabase.SCHEMA_VERSION
                val oldAppMarker = prefs.getString("upgrade_prompt_version", null)
                val shownV9Previously = oldAppMarker == "0.6.6" || oldAppMarker == "0.6.6.1"
                val baseline = if (shownV9Previously) 9 else 0
                val previous = prefs.getInt("data_upgrade_seen_schema", baseline)
                val prompted = prefs.getInt("data_upgrade_prompted_schema", baseline)
                val legacy = needsImageUpgrade()
                val shouldPrompt = com.zsz.studyassistant.data.needsDataUpgradePrompt(
                    schema, previous, prompted, legacy
                )
                val editor = prefs.edit().putInt("data_upgrade_seen_schema", schema)
                if (shouldPrompt) editor.putInt("data_upgrade_prompted_schema", schema)
                editor.apply()
                showUpgradePrompt = shouldPrompt
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 读取失败时不写“已提示 / 已完成”，以后仍可重试。
                upgradePromptChecked = false
            } finally {
                dataMaintenanceMutex.unlock()
            }
        }
    }

    fun dismissUpgradePrompt(goUpgrade: Boolean = false) {
        showUpgradePrompt = false
        if (goUpgrade) openDataUpgradePage = true
    }

    // ── 「读到旧格式数据」警示（2026-10-16，用户规格：[确认]→去升级 / [跳过]→继续看）──────
    var showLegacyImageWarn by mutableStateOf(false)

    fun dismissLegacyImageWarn(goUpgrade: Boolean = false) {
        showLegacyImageWarn = false
        if (goUpgrade) openDataUpgradePage = true
    }

    /** 还有"有图但没进新表"的题 ⇒ 需要升级（一次查询 + 一次轻扫描） */
    private suspend fun needsImageUpgrade(): Boolean {
        for (row in dao.questionsWithConversationOnce()) {
            val q = dao.heavyOnce(row.id) ?: continue
            val existing = dao.imagesForQuestion(q.id).map { it.bytes }
            val candidates = recoverableImages(q, includeSession = true)
            if (com.zsz.studyassistant.data.additionalQuestionImages(existing, candidates).isNotEmpty()) return true
            if (q.imageBytes == null && candidates.any { it.isNotEmpty() }) return true
        }
        return false
    }

    /**
     * 记录补图检查成功；与用户确认数据库结构升级的记录分开。
     * 幂等、可重复执行；只做加法（不动已有图、不改 `imageBytes`）。
     */
    private fun recordImageRepairSuccess() {
        getApplication<Application>().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit().putInt("image_repair_schema", AppDatabase.SCHEMA_VERSION)
            .putLong("image_repair_last_success_at", System.currentTimeMillis()).apply()
    }

    fun upgradeData() {
        repairData(isUpgrade = true)
    }

    /**
     * 先检查原图完整性：完整则无需修复，否则事务内追加、回填并复查。
     * 不清除软删除题目；成功提交后才记录完成的 DB 版本与时间。
     */
    fun repairData(isUpgrade: Boolean = false) {
        if (dataBusy) return
        viewModelScope.launch {
            dataMaintenanceMutex.lock()
            dataBusy = true
            try {
                val s = com.zsz.studyassistant.ui.stringsFor(uiLang)
                val db = AppDatabase.get(getApplication())
                val schema = readDatabaseVersion()
                val prefs = getApplication<Application>()
                    .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                val confirmationNeeded = com.zsz.studyassistant.data.needsDataUpgradeConfirmation(
                    schema, prefs.getInt("data_upgrade_prompted_schema", 0),
                    prefs.getInt("data_upgrade_confirmed_schema", 0)
                )
                var needed = false
                var count = 0
                db.withTransaction {
                    needed = needsImageUpgrade()
                    if (needed) {
                        repairQuestionImagesFromSessions()
                        backfillQuestionImages()
                        fillImagesTable()
                        check(!needsImageUpgrade())
                    }
                    count = dao.countQuestions()
                }
                // 只有事务提交且复查完整后，才写入完成记录。
                recordImageRepairSuccess()
                if (isUpgrade) prefs.edit().putInt("data_upgrade_confirmed_schema", schema).apply()
                currentDatabaseVersion = schema
                databaseVersionReadFailed = false
                hasLegacyData = false
                upgradeMessageIsError = false
                upgradeMessage = if (needed || (isUpgrade && confirmationNeeded)) {
                    s.format(if (isUpgrade) "data.upgradeDone" else "data.repairDone", "n" to "$count")
                } else s[if (isUpgrade) "data.upgradeNotNeeded" else "data.repairNotNeeded"]
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                upgradeMessageIsError = true
                upgradeMessage = com.zsz.studyassistant.ui.stringsFor(uiLang)
                    .format("data.failed", "msg" to (e.message ?: ""))
            } finally {
                dataBusy = false
                dataMaintenanceMutex.unlock()
            }
        }
    }

    fun clearAllData() {
        if(dataBusy) return
        // Late file-picker results must not export a snapshot made before this clear.
        discardPreparedBackup()
        discardPreparedExport()
        dataBusy = true
        viewModelScope.launch {
            try {
                val stoppingJob=callJob
                stopActiveGeneration()
                stoppingJob?.join()
                com.zsz.studyassistant.data.BackupJobs.exclusive(getApplication()) {
                solveSessionWriteMutex.withLock {
                    requireNoPendingSaves()
                    com.zsz.studyassistant.data.BackupManager.clearAll(AppDatabase.get(getApplication()))
                    draftWriteMutex.withLock { withContext(Dispatchers.IO) { conversationDrafts.clearAll() } }
                    historyWriteQueue.clear()
                    failedHistoryWrites = 0
                    activeDraft=null
                    resetSession()
                    exitReviewMode()
                    draftError=null
                    refreshDrafts()
                }
                }
                menuMessageIsError = false
                menuMessage = com.zsz.studyassistant.ui.stringsFor(uiLang)["data.clearDone"]
            } catch (e: Exception) {
                menuMessageIsError = true
                menuMessage = com.zsz.studyassistant.ui.stringsFor(uiLang).format("data.failed", "msg" to (e.message ?: ""))
            } finally {
                dataBusy = false
            }
        }
    }

    /** 用于「数据管理」页显示的规模：错题数 / 分类数 / 标签数 */
    var dataSummary by mutableStateOf<Triple<Int, Int, Int>?>(null)
        private set

    fun refreshDataSummary() {
        viewModelScope.launch {
            dataSummary = Triple(
                dao.countQuestions(),
                dao.allCategoriesOnce().size,
                dao.getAllTagsOnce().size
            )
        }
    }

    /** 拍照解答 */
    fun solveWithImage(bytes: ByteArray) {
        prepareSession(photo = true)
        imageBytes = bytes
        isPhoto = true
        isFromNotebook = false
        questionFromPhoto = true
        questionImages = listOf(Base64.encodeToString(bytes, Base64.NO_WRAP))
        recordSolveSession("camera", create = true)
        runCall(StudyAssistant.MODEL_VISION, onDone = { output ->
            val r = StudyAssistant.parseVisionOutput(output)
            questionText = r.question
            suggestedCategory = r.category
            suggestedTags = r.tags
            addItem("question", r.question)
            addItem("assistant", r.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
            recordSolveSession("camera")     // ★ 尽早落库：进程被杀也不丢（见 recordSolveSession）
        }, repeat = { solveWithImage(bytes) })
    }

    /** 拍照解答（多张）：两张图作为同一道题目一起识别解答 */
    fun solveWithImages(images: List<ByteArray>) {
        prepareSession(photo = true)
        isPhoto = true
        isFromNotebook = false
        multiImages = images
        questionFromPhoto = true
        questionImages = images.map { Base64.encodeToString(it, Base64.NO_WRAP) }
        recordSolveSession("camera", create = true)
        runCall(StudyAssistant.MODEL_VISION, onDone = { output ->
            val r = StudyAssistant.parseVisionOutput(output)
            questionText = r.question
            suggestedCategory = r.category
            suggestedTags = r.tags
            // ★★ 2026-10-14 修「两张图存进错题本后只剩一张」的**真正根因**：
            //   这里原来 `addItem("question", r.question)` **没带 images** → 题目气泡的 `ChatItem.images` 为空
            //   → 而 `saveToNotebook` 存的 `conversationJson = chatItems` **正是从它来的** →
            //   第二张图**从来没被存下来**（读取端再怎么兜底也找不到）。
            //   现在把 `questionImages`（全部图，base64）一起写进题目气泡，存档后即可完整还原。
            addItem("question", r.question, questionImages.ifEmpty { null })
            addItem("assistant", r.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
            recordSolveSession("camera")     // ★ 多图一并落进 imagesJson（搜题记录侧也存全）
        }, repeat = { solveWithImages(images) })
    }

    /** 文字解答 */
    fun solveText(question: String) {
        prepareSession()
        isPhoto = false
        isFromNotebook = false
        questionFromPhoto = false
        questionText = question
        suggestedCategory = null
        suggestedTags = emptyList()
        recordSolveSession("text", create = true)
        runCall(StudyAssistant.MODEL_TEXT, onDone = { reply ->
            addItem("question", question)
            // ★ 与图文提问走同一条清洗路径：否则原始 reply 里的「题目：/分类：」整块会显示出来
            //   （这就是"答案里又出现了题目"）
            val r = StudyAssistant.parseVisionOutput(reply)
            suggestedCategory = r.category
            suggestedTags = r.tags
            addItem("assistant", r.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
            recordSolveSession("text")
        }, repeat = { solveText(question) })
    }

    /** 直接提问：文字 + 可选多张图，作为一条问题解答 */
    fun solveDirect(text: String, images: List<ByteArray>) {
        prepareSession(photo = images.isNotEmpty(), ask = true)
        isPhoto = images.isNotEmpty()
        isFromNotebook = false
        questionFromPhoto = false   // 直接提问显示用户自己写的文字，不是 AI 转译题干
        questionText = text
        directImages = images
        suggestedCategory = null
        suggestedTags = emptyList()
        // 把"我的提问 + 附图"作为一条对话消息显示（与拍题后的问答一致）
        // 附图同时存进该条消息，重开会话（如从错题本进入）仍能看到原图
        val encoded = images.map { Base64.encodeToString(it, Base64.NO_WRAP) }
        questionImages = encoded
        addItem("question", text, if (encoded.isEmpty()) null else encoded)
        recordSolveSession("ask", create = true)
        val model = if (images.isNotEmpty()) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        runCall(model, onDone = { reply ->
            if (images.isNotEmpty()) {
                val r = StudyAssistant.parseVisionOutput(reply)
                suggestedCategory = r.category
                suggestedTags = r.tags
                addItem("assistant", r.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
            } else {
                // ★ 纯文字的「直接提问」也要解析分类/知识点——这里原来直接 addItem(reply)，
                //   不解析就永远拿不到预选（拍题/带图提问都解析了，只有这条路径漏了）
                val r = StudyAssistant.parseVisionOutput(reply)
                suggestedCategory = r.category
                suggestedTags = r.tags
                addItem("assistant", r.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
            }
            recordSolveSession("ask")
        }, repeat = { solveDirect(text, images) })
    }

    /** 接续追问（可附带 1~3 张图，文字 + 图一起发给视觉模型） */
    fun sendFollowUp(text: String, images: List<ByteArray> = emptyList()) {
        if(streamInterrupted) { error=com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.resolveFirst"]; return }
        if (text.isBlank() && images.isEmpty()) return
        val encoded = images.map { Base64.encodeToString(it, Base64.NO_WRAP) }
        addItem("user", text, encoded)
        val model = if (isPhoto || gradeMode || images.isNotEmpty()) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        runCall(model, onDone = { reply ->
            // 追问也剔掉回显的元信息行（否则答案里会又冒出「题目：…」）
            addItem("assistant", StudyAssistant.stripMetaLines(reply))
            updateSolveSession()
        }, repeat = { retryFollowUp(encoded) })
    }

    /** 当前会话的来源标签（camera/ask/text/grade）——更新已有记录时沿用，避免来回改来源 */
    private fun currentSource(): String = when {
        gradeMode -> "grade"
        askMode -> "ask"
        isPhoto -> "camera"
        else -> "text"
    }

    /** 追问 / 继续生成后的更新：**UPDATE 同一条**记录（没落过库则什么都不做，由生成入口负责 insert） */
    private fun updateSolveSession() {
        if (currentSessionId == null) return
        recordSolveSession(currentSource())
    }

    /** 网络失败后重新发送最后一次追问（不重复添加 user 消息，保留附图） */
    private fun retryFollowUp(images: List<String>? = null) {
        val model = if (isPhoto || !images.isNullOrEmpty()) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        runCall(model, onDone = { reply ->
            addItem("assistant", StudyAssistant.stripMetaLines(reply))
        }, repeat = { retryFollowUp(images) })
    }

    /** 重新生成：**保留提问气泡**，只重做回答 */
    fun regenerate() {
        if(busy || streamInterrupted) {
            if(streamInterrupted) error=com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.resolveFirst"]
            return
        }
        val retained=com.zsz.studyassistant.data.regenerationBase(chatItems)
        val followup=retained.lastOrNull()?.role=="user"
        val action=if(followup) "regenerate_followup" else "regenerate_initial"
        val model=if(isPhoto || retained.lastOrNull()?.images?.isNotEmpty()==true) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        streamCall(model,buildMessages(retained),"",onDone={ output -> applyRecoveredReply(action,output) },
            repeat={ regenerate() },replyAction=action)
    }

    private fun lastAnswer(): String =
        chatItems.lastOrNull { it.role == "assistant" }?.content ?: ""

    /**
     * B9 查重用：**将要落库的题干**（与 [saveToNotebook] 完全一样的取法）。
     * ⚠️ 必须共用同一套取法：之前界面拿的是 `questionText`，而落库时可能在它为空的情况下
     * 回退到"AI 识别出的题干" —— 存的串和查的串不一致，就会永远查不出重复。
     */
    fun pendingQuestionText(): String = questionText.ifBlank {
        runCatching { StudyAssistant.parseVisionOutput(lastAnswer() ?: "").question }
            .getOrNull()?.trim().orEmpty()
    }

    // ---- B7 导出（长图/PDF）已在 v0.6.4 **取消并移除**：相关只读访问器（exportQuestion /
    //      exportAnswer / exportImageBytes）与 ui/QuestionExporter.kt、ui/ExportDialog.kt、
    //      assets/export_render.html 一并删除，不要再往回加。----

    /** 保存到错题本（带分类）。name 非空→新建分类；categoryId 为 null→暂不分类 */
    fun saveToNotebook(name: String?, categoryId: Long?, tagNames: List<String> = emptyList(), tagIds: List<Long> = emptyList()) {
        if(dataBusy) return
        if (saving) { cancelPending = true; return }
        if (savedQuestionId != null) return
        val hasImg = questionImages.isNotEmpty() || imageBytes != null || directImages.isNotEmpty()
        val q = questionText.ifBlank {
            if (!hasImg) return
            runCatching { StudyAssistant.parseVisionOutput(lastAnswer()).question }
                .getOrNull()?.trim().orEmpty().ifBlank { "（图片题）" }
        }
        val binding = sessionRecordBinding
        val stableId = binding.id ?: return
        val firstCreatedAt = binding.createdAt
        val convJson = json.encodeToString(chatItems)
        val a = lastAnswer()
        val img = imageBytes ?: questionImages.firstOrNull()?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }
        val allImgBytes = questionImages.take(MAX_QUESTION_IMAGES).mapNotNull {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }.ifEmpty { img?.let { listOf(it) } ?: emptyList() }
        val now = System.currentTimeMillis()
        saving = true
        cancelPending = false
        viewModelScope.launch {
            try {
                val db = AppDatabase.get(getApplication())
                var reusedQuestion: Question? = null
                val saved = notebookWriteMutex.withLock { db.withTransaction {
                    if (binding.cancelPending || stableId in deletionState.value.questionIds) return@withTransaction null
                    val existingQuestion = dao.heavyOnce(stableId)
                    if (existingQuestion != null) {
                        // Explicit save can restore this ID, but must not replace a newer notebook copy.
                        if (existingQuestion.deleted) dao.restore(stableId)
                        reusedQuestion = existingQuestion.copy(deleted = false)
                        dao.linkExistingSession(stableId)
                        return@withTransaction Triple(stableId, existingQuestion.categoryId, dao.tagIdsForQuestion(stableId))
                    }
                    val cid = if (!name.isNullOrBlank()) {
                        dao.insertCategory(Category(name = name.trim()))
                    } else categoryId
                    val qid = dao.insertQuestionWithId(Question(id = stableId, text = q, answer = a, imageBytes = img,
                        conversationJson = convJson, categoryId = cid, createdAt = firstCreatedAt))
                    writeQuestionImages(qid, allImgBytes, source = "save")
                    val ids = tagIds.distinct().toMutableList()
                    for (tn in tagNames.take(5)) {
                        val n = tn.trim()
                        if (n.isBlank()) continue
                        val existing = dao.getAllTagsOnce().firstOrNull { it.name == n }
                        val tid = existing?.id ?: dao.insertTag(Tag(name = n))
                        if (tid !in ids) ids += tid
                    }
                    val finalIds = ids.take(5)
                    for (tid in finalIds) dao.insertQuestionTag(QuestionTag(qid, tid))
                    dao.insertReview(Review(questionId = qid, intervalStep = 0,
                        nextReviewAt = now, reviewCount = 0))
                    if (binding.cancelPending) {
                        dao.clearImages(qid)
                        dao.clearQuestionTags(qid)
                        dao.deleteReviewsForQuestions(listOf(qid))
                        dao.deleteById(qid)
                        null
                    } else {
                        dao.linkExistingSession(qid)
                        Triple(qid, cid, finalIds)
                    }
                } }
                if (binding === sessionRecordBinding) {
                    val reused = reusedQuestion
                    if (reused != null) {
                        val history = if (binding.historyAllowed) dao.solveSessionOnce(stableId) else null
                        if (binding === sessionRecordBinding) loadQuestion(reused, historySession = history)
                        return@launch
                    }
                    savedQuestionId = saved?.first
                    savedToNotebook = saved != null
                    if (saved != null) { isDeleted = false; deletedNotebookIds.remove(stableId) }
                    currentQuestionCategoryId = saved?.second
                    currentQuestionTags = saved?.third.orEmpty()
                    if (saved != null) {
                        currentQuestionCreatedAt = firstCreatedAt
                        currentNotebookBaseline=com.zsz.studyassistant.data.SavePayload(stableId,firstCreatedAt,q,a,convJson)
                    }
                    cancelPending = false
                    recordSolveSession(currentSource())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (binding === sessionRecordBinding) {
                    error = com.zsz.studyassistant.ui.stringsFor(uiLang)
                        .format("data.failed", "msg" to tagWriteError(e))
                }
            } finally {
                binding.saving = false
            }
        }
    }

    /** 取消保存当前错题（从错题本移除） */
    fun unsaveFromNotebook() { savedQuestionId?.let { stageQuestionDeletion(listOf(it)) } }

    /** 修改当前题目的分类（详情页用）；name 非空→新建分类，categoryId 为 null→暂不分类 */
    fun changeCurrentCategory(name: String?, categoryId: Long?) {
        val id = savedQuestionId ?: return
        val binding = sessionRecordBinding
        viewModelScope.launch {
            var cid = categoryId
            if (!name.isNullOrBlank()) cid = dao.insertCategory(Category(name = name.trim()))
            dao.setCategoryForIds(listOf(id), cid)
            if (binding === sessionRecordBinding && savedQuestionId == id) {
                currentQuestionCategoryId = cid
            }
        }
    }

    /** 批量改分类：name 非空→新建分类，categoryId 为 null→暂不分类 */
    fun batchSetCategory(ids: List<QuestionId>, name: String?, categoryId: Long?) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var cid = categoryId
            if (!name.isNullOrBlank()) {
                cid = dao.insertCategory(Category(name = name.trim()))
            }
            dao.setCategoryForIds(ids, cid)
        }
    }

    private fun tagWriteError(e: Exception): String = if (e is com.zsz.studyassistant.data.TagMergeException)
        com.zsz.studyassistant.ui.stringsFor(uiLang)[e.messageKey] else e.message.orEmpty()

    fun onNotebookTagsChanged() {
        refreshDataSummary(); refreshUsage()
        val id = savedQuestionId ?: return
        val binding = sessionRecordBinding
        viewModelScope.launch {
            try {
            val ids = dao.tagIdsForQuestion(id)
            val all = dao.getAllTagsOnce()
            if (binding === sessionRecordBinding && savedQuestionId == id) {
                currentQuestionTags = ids
                suggestedTags = ids.mapNotNull { tid -> all.firstOrNull { it.id == tid }?.name }
            }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (binding === sessionRecordBinding) error = tagWriteError(e) }
        }
    }

    private fun editCurrentTags(action: suspend (QuestionId) -> Unit) {
        val id = savedQuestionId ?: return
        val binding = sessionRecordBinding
        viewModelScope.launch {
            try {
                AppDatabase.get(getApplication()).withTransaction {
                    if (!dao.questionExists(id)) throw com.zsz.studyassistant.data.TagMergeException("tagMerge.changed")
                    action(id)
                }
                if (binding === sessionRecordBinding && savedQuestionId == id) onNotebookTagsChanged()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (binding === sessionRecordBinding) error = tagWriteError(e) }
        }
    }

    /** 给当前题目加一个标签；在事务内重新核对编号，防止使用已合并标签。 */
    fun addTagToCurrent(name: String?, tagId: Long?) {
        editCurrentTags { id ->
            var tid = tagId
            if (!name.isNullOrBlank()) {
                val existing = dao.getAllTagsOnce().firstOrNull { it.name == name.trim() }
                tid = existing?.id ?: dao.insertTag(Tag(name = name.trim()))
            }
            if (tid == null) return@editCurrentTags
            if (dao.tagIdsForQuestion(id).contains(tid)) return@editCurrentTags
            dao.insertQuestionTag(QuestionTag(id, tid))
        }
    }

    /** 移除当前题目的一个标签 */
    fun removeTagFromCurrent(tagId: Long) {
        editCurrentTags { id ->
            dao.deleteQuestionTag(id, tagId)
        }
    }

    /** 重置当前题目的标签（详情页批量改；tagIds + 新建 tagNames，≤5 个） */
    fun setCurrentTags(tagIds: List<Long>, newTagNames: List<String>) {
        editCurrentTags { id ->
            dao.clearQuestionTags(id)
            val finalIds = tagIds.toMutableList()
            for (tn in newTagNames.take(5)) {
                val n = tn.trim()
                if (n.isBlank()) continue
                val ex = dao.getAllTagsOnce().firstOrNull { it.name == n }
                val tid = ex?.id ?: dao.insertTag(Tag(name = n))
                if (!finalIds.contains(tid)) finalIds += tid
            }
            dao.insertQuestionTags(finalIds.distinct().take(5).map { QuestionTag(id, it) })
        }
    }

    /** 批量彻底删除 */
    fun batchDelete(ids: List<QuestionId>) { stageQuestionDeletion(ids) }

    // ---- 复习（艾宾浩斯）----

    /** 今天到期的错题 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun dueQuestionsToday(): Flow<List<Question>> = kotlinx.coroutines.flow.combine(calendarDay.flatMapLatest { dao.dueQuestions(it.end) }, deletionState) { rows, deletes -> rows.filterNot { it.id in deletes.questionIds } }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun dueQuestionsWeek(): Flow<List<Question>> = kotlinx.coroutines.flow.combine(calendarDay.flatMapLatest { dao.dueQuestions(it.weekEnd) }, deletionState) { rows, deletes -> rows.filterNot { it.id in deletes.questionIds } }

    var reviewBusy by mutableStateOf(false)
        private set
    private var reviewEpoch = 0L
    private val committedReviewIds = mutableSetOf<QuestionId>()
    private suspend fun commitReview(questionId: QuestionId, level: Int) {
        AppDatabase.get(getApplication()).withTransaction {
            val question = dao.questionLightOnce(questionId) ?: error("Question no longer exists")
            check(!question.deleted && questionId !in deletionState.value.questionIds)
            val before = dao.reviewFor(questionId) ?: Review(questionId).also { dao.insertReview(it) }
            val (step, days) = com.zsz.studyassistant.data.ReviewPlanning.schedule(level, before.intervalStep)
            dao.updateReview(questionId, step, com.zsz.studyassistant.data.CalendarDay.now().after(days), System.currentTimeMillis())
        }
    }

    /** 复习模式：点进错题查看；详情页底部为 熟悉/模糊/忘记 三按钮 */
    var reviewMode by mutableStateOf(false)
        private set
    private var reviewQueue: List<Question> = emptyList()
    private var currentReviewQuestion: Question? = null

    /** 本轮复习进度：第 reviewDone+1 / reviewTotal 题（进入复习时由列表确定总数） */
    var reviewTotal by mutableStateOf(0)
        private set
    var reviewDone by mutableStateOf(0)
        private set

    /** 从复习列表进入时调用：记录本轮总数与起始位置，用于「第 i/n 题」显示 */
    fun startReviewSession(candidates: List<Question>, index: Int) {
        reviewEpoch++
        committedReviewIds.clear()
        val ids = com.zsz.studyassistant.data.ReviewPlanning.ordered(candidates.map { it.id }, index)
        val byId = candidates.associateBy { it.id }
        reviewQueue = ids.mapNotNull { byId[it] }
        reviewTotal = reviewQueue.size
        reviewDone = 0
        reviewBusy = false
    }

    fun loadForReview(q: Question, onReady: () -> Unit = {}) {
        stopActiveGeneration()
        val seq = callSeq
        val binding = sessionRecordBinding
        viewModelScope.launch {
            try {
                if (!canOpenSavedQuestion(q.id)) return@launch
                val full = dao.heavyOnce(q.id) ?: return@launch
                if (seq != callSeq || binding !== sessionRecordBinding) return@launch
                redoMessage = null
                loadQuestion(full, keepReview = true)
                onReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == callSeq && binding === sessionRecordBinding) {
                    error = com.zsz.studyassistant.ui.stringsFor(uiLang)
                        .format("data.failed", "msg" to (e.message ?: ""))
                }
            }
        }
    }

    /** 点三按钮后：更新调度并自动跳到下一题；无下一题时回调 onNoMore */
    fun reviewNext(level: Int, onNoMore: () -> Unit) {
        submitReview(savedQuestionId ?: return, level, onNoMore)
    }
    fun confirmRedo(level: Int, onNoMore: () -> Unit) {
        submitReview(redoQuestionId ?: return, level, onNoMore)
    }
    private fun submitReview(id: QuestionId, level: Int, onNoMore: () -> Unit) {
        if (reviewBusy) return
        reviewBusy = true
        val epoch = reviewEpoch
        val seq = callSeq
        val binding = sessionRecordBinding
        fun ownsSession() = epoch == reviewEpoch && seq == callSeq && binding === sessionRecordBinding
        viewModelScope.launch {
            try {
                if(id !in committedReviewIds) withContext(kotlinx.coroutines.NonCancellable) {
                    commitReview(id, level)
                    if(epoch==reviewEpoch) committedReviewIds.add(id)
                }
                if (ownsSession()) advanceReview(id, epoch, onNoMore)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if(ownsSession()) error = com.zsz.studyassistant.ui.stringsFor(uiLang)[
                    if(id in committedReviewIds) "review.loadFailed" else "review.saveFailed"]
            }
            finally { if (epoch == reviewEpoch) reviewBusy = false }
        }
    }
    private suspend fun advanceReview(answered: QuestionId?, epoch: Long, onNoMore: () -> Unit) {
        val seq = callSeq
        val binding = sessionRecordBinding
        fun ownsSession() = epoch == reviewEpoch && seq == callSeq && binding === sessionRecordBinding
        if (!ownsSession()) return
        val wasQueued=reviewQueue.any { it.id==answered }
        val remaining = reviewQueue.filter { it.id != answered }
        reviewQueue = remaining
        if(wasQueued) reviewDone = (reviewDone + 1).coerceAtMost(reviewTotal)
        for (candidate in remaining) {
            if (!ownsSession()) return
            if (!canOpenSavedQuestion(candidate.id, ::ownsSession)) return
            if (!ownsSession()) return
            val full = dao.heavyOnce(candidate.id)
            if (!ownsSession()) return
            if (full != null && !full.deleted && full.id !in deletionState.value.questionIds) {
                redoMessage = null
                loadQuestion(full, keepReview = true)
                error=null
                return
            }
            reviewQueue = reviewQueue.filter { it.id != candidate.id }
            reviewTotal = (reviewTotal - 1).coerceAtLeast(reviewDone)
        }
        redoMessage = null
        error=null
        onNoMore()
    }
    fun reviewAdvance(onNoMore: () -> Unit) {
        if (reviewBusy) return
        reviewBusy = true
        val epoch = reviewEpoch
        val seq = callSeq
        val binding = sessionRecordBinding
        fun ownsSession() = epoch == reviewEpoch && seq == callSeq && binding === sessionRecordBinding
        viewModelScope.launch {
            try { if (ownsSession()) advanceReview(savedQuestionId ?: redoQuestionId, epoch, onNoMore) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if(ownsSession()) error = com.zsz.studyassistant.ui.stringsFor(uiLang)["review.loadFailed"] }
            finally { if (epoch == reviewEpoch) reviewBusy = false }
        }
    }

    fun exitReviewMode() {
        reviewEpoch++
        committedReviewIds.clear()
        reviewBusy = false
        reviewQueue = emptyList()
        reviewMode = false
        reviewTotal = 0
        reviewDone = 0
    }

    // ---------------------------------------------------------------------
    // 重做模式（B6，已接入界面）
    //
    // 闭环：复习页/复习中的题目 → 点「✏️ 手写重做」→ 在纸上写解答 → 拍一张 → 复用批改链路
    //      （题目图有则用题目图，纯文字题走 gradeTextUserMessage）→ 由用户确认批改建议后更新掌握档位
    //      → 回到解题页看批改结果，点「下一题」继续本轮复习。
    // 与人工点「熟悉/模糊/忘记」并行存在：自动更新只是省一步，用户仍可手动覆盖。
    // ---------------------------------------------------------------------

    /** 是否处于重做模式（GradeScreen 据此切成"只拍我的作答"） */
    var redoMode by mutableStateOf(false)
        private set

    /** 重做批改完成后给界面的提示（非空 = 解题页显示「已更新掌握度 …」+「下一题」） */
    var redoMessage by mutableStateOf<String?>(null)
        private set

    /** 当前重做的题目 id */
    var redoQuestionId by mutableStateOf<QuestionId?>(null)
        private set

    private var redoQuestionText: String = ""
    private var redoQuestionImage: ByteArray? = null

    /** 本次批改是否属于"重做"（一次性）：批改结束时据此自动更新掌握度 */
    private var pendingRedoFor: QuestionId? = null

    /** 复习时点「✏️ 手写重做」：记住当前题，等用户拍/选作答照片 */
    fun startRedo() {
        val q = currentReviewQuestion ?: return
        redoQuestionId = q.id
        redoQuestionText = q.text
        redoQuestionImage = q.imageBytes
        redoMessage = null
        redoMode = true
    }

    /** 提交手写作答照片 → 走批改链路（批改结束会自动更新掌握度） */
    fun submitRedoAnswer(answerBytes: ByteArray) {
        val qid = redoQuestionId ?: return
        val img = redoQuestionImage
        if (img != null) startGrade(img, answerBytes, redoFor = qid)
        else startGradeText(redoQuestionText, answerBytes, redoFor = qid)
    }

    /** 批改结束（重做）→ 按结论映射档位、写回复习计划，并给界面一句提示 */
    var redoSuggestedLevel by mutableStateOf<Int?>(null)
        private set
    private fun applyRedoResult(qid: QuestionId, reply: String) {
        redoQuestionId = qid
        redoSuggestedLevel = com.zsz.studyassistant.data.GradeVerdict.suggestion(reply)
        val s = com.zsz.studyassistant.ui.stringsFor(uiLang)
        val label = when(redoSuggestedLevel) { 2 -> s["solve.familiar"]; 0 -> s["solve.forgot"]; 1 -> s["solve.vague"]; else -> s["review.unknownVerdict"] }
        redoMessage = if(redoSuggestedLevel==null) s["review.unknownVerdict"] else s.format("review.suggestion", "level" to label)
    }

    /** 退出重做（离开复习/换会话时清干净，避免下一次普通批改被误判成重做） */
    fun exitRedo() {
        redoMode = false
        redoMessage = null
        redoQuestionId = null
        pendingRedoFor = null
    }

    /**
     * 离开「拍作答」页时调用：**只**关掉重做模式的界面标志。
     * ⚠️ 不能顺手清 [pendingRedoFor]：批改是异步的，用户拍完就跳到批改页了，
     * 这时清掉会让"按批改结果自动更新掌握度"静默失效。
     */
    fun leaveRedoCamera() { redoMode = false }

    // ---- 练同类题 ----
    var similarQuestion by mutableStateOf<String?>(null)
        private set
    var similarAnswer by mutableStateOf<String?>(null)
        private set
    var similarBusy by mutableStateOf(false)
        private set
    var similarMessages by mutableStateOf<List<ChatItem>>(emptyList())
        private set
    /** 同类题的答案是否已显示（原在页面上；挪到 VM 以便多选删除时统一处理） */
    var similarRevealed by mutableStateOf(false)
        private set

    fun revealSimilarAnswer() { similarRevealed = true }

    /** 收起同类题答案（左下角椭圆按钮切换） */
    fun hideSimilarAnswer() { similarRevealed = false }

    /** 同类题流式中的文本：出题时 = 只含题目部分（答案不外显）；追问时 = 助手回复 */
    var similarStreamingText by mutableStateOf<String?>(null)
        private set

    /** 同类题出题被中止（还没出题成功）→ 界面显示蓝色「继续生成」 */
    var similarInterrupted by mutableStateOf(false)
        private set

    /** 同类题的出题/追问任务（用于「⏸ 中止生成」） */
    private var similarJob: Job? = null
    private var similarStreamOwner: com.zsz.studyassistant.data.StreamOwner? = null
    private var similarSeq = 0L
    private class SimilarProgress(val initial: Boolean, val raw: StringBuffer = StringBuffer())
    private var similarProgress: SimilarProgress? = null
    internal var similarQuestionRequest: suspend (Question, AiLang, com.zsz.studyassistant.data.StreamOwner, suspend (String) -> Unit) -> StudyAssistant.SimilarResult =
        { question, lang, owner, delta -> StudyAssistant.generateSimilarQuestionStream(question, lang, owner, delta) }

    /**
     * 删除同类题会话中的消息。索引按界面渲染顺序：
     *   0            = AI 出的题目（删它 = 整段会话失去上下文 → 清空，等价于重新出题）
     *   1..n         = 追问往返（对应 similarMessages[0..n-1]）
     *   末位（答案已显示时）= 答案（删它 = 收起答案，可再点「查看答案」）
     */
    fun deleteSimilarItems(indices: List<Int>) {
        if (indices.isEmpty()) return
        val hasAnswer = similarRevealed && similarAnswer != null
        val total = 1 + similarMessages.size + (if (hasAnswer) 1 else 0)
        if (indices.contains(0)) {
            abortSimilar()
            similarQuestion = null
            similarAnswer = null
            similarMessages = emptyList()
            similarRevealed = false
            return
        }
        if (hasAnswer && indices.contains(total - 1)) similarRevealed = false
        val kill = indices.filter { it in 1..similarMessages.size }.map { it - 1 }.toSet()
        if (kill.isNotEmpty()) {
            similarMessages = similarMessages.filterIndexed { i, _ -> i !in kill }
            persistSimilarConversation()
        }
    }

    fun startSimilar() {
        val q = currentReviewQuestion ?: return
        abortSimilar()
        similarCreatedAt = System.currentTimeMillis()
        similarIdentity = QuestionIdentity.create(similarCreatedAt)
        val mySeq = ++similarSeq
        similarStreamOwner?.cancel()
        similarJob?.cancel()
        val owner = com.zsz.studyassistant.data.StreamOwner()
        similarStreamOwner = owner
        val progress = SimilarProgress(initial = true)
        similarProgress = progress
        val request = similarQuestionRequest
        similarJob = viewModelScope.launch {
            similarBusy = true
            similarQuestion = null; similarAnswer = null; similarMessages = emptyList(); similarRevealed = false
            similarStreamingText = null
            similarInterrupted = false
            similarSavedQuestionId = null
            val app = getApplication<Application>()
            com.zsz.studyassistant.data.AnswerForegroundService.start(app, owner.id)
            val sb = progress.raw
            var lastEmit = 0L
            try {
                // 流式出题：界面只显示「题目」部分，答案随流到达但不外显（点「查看答案」才展开）
                val r = withContext(Dispatchers.IO) {
                    request(q, aiLang, owner) { delta ->
                        sb.append(delta)
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 180) {
                            lastEmit = now
                            val acc = sb.toString()
                            val shown = StudyAssistant.similarQuestionPortion(acc)
                            // 一旦出现「解答：」→ 题目已成型：立即提交为正式题目，
                            // 此后界面进入「等答案」状态（「查看答案」按钮可见但不可用）
                            val answerStarted = acc.contains("解答：") || acc.contains("解答:")
                            withContext(Dispatchers.Main) {
                                if (mySeq != similarSeq) return@withContext   // 已被中止/重开
                                if (answerStarted) {
                                    if (shown.isNotBlank()) similarQuestion = shown
                                    similarStreamingText = null
                                } else {
                                    similarStreamingText = shown
                                }
                            }
                        }
                    }
                }
                if (mySeq == similarSeq) {
                    thinkingDone = true
                recordUsage(app, owner.usage)
                // ⚠️ 已删除「挂到 indexOfLast{assistant}」的旧代码：同类题的产物存进 similarQuestion/
                //   similarAnswer，并不在 chatItems 里 → 那段只会去改**别的**气泡，纯属误伤。
                    similarQuestion = r.question
                    similarAnswer = r.answer
                    similarStreamingText = null
                    persistSimilarConversation()
                }
            } catch (e: Exception) {
                if (mySeq == similarSeq) {
                    val partial = StudyAssistant.similarQuestionPortion(sb.toString())
                    val aborted = e is CancellationException
                    when {
                        partial.isNotBlank() -> similarQuestion = partial          // 中断也保留已生成的题目
                        aborted -> similarQuestion = null                           // 中止且零输出：回到「思考中…」
                        else -> similarQuestion = com.zsz.studyassistant.ui.stringsFor(uiLang).format("err.similarFailed", "msg" to friendlyError(e, ""))
                    }
                    similarInterrupted = true
                    val receivedAnswer = Regex("解答[:：]([\\s\\S]+)")
                        .find(sb.toString())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                    if (e is com.zsz.studyassistant.data.StreamIncompleteException) {
                        val notice = friendlyError(e, "")
                        similarAnswer = receivedAnswer?.let { it + "\n\n" + notice }
                        similarMessages = similarMessages + ChatItem(similarMessages.size.toLong(), "assistant", notice)
                    } else similarAnswer = receivedAnswer
                    similarStreamingText = null
                    persistSimilarConversation()
                }
            }
            if (mySeq == similarSeq) { similarBusy = false; similarProgress = null }
            com.zsz.studyassistant.data.AnswerForegroundService.stop(app, owner.id)
        }
    }

    /** 中止同类题的出题 / 追问（与解题页的 ⏸ 中止生成 对齐） */
    fun abortSimilar() {
        val progress = similarProgress ?: return
        val partial = progress.raw.toString()
        similarProgress = null
        similarSeq++
        similarStreamOwner?.let { com.zsz.studyassistant.data.AnswerForegroundService.stop(getApplication(), it.id) }
        similarStreamOwner?.cancel()
        similarJob?.cancel()
        similarJob = null
        similarBusy = false
        if (progress.initial) {
            // Having parsed a question does not mean the initial answer has finished.
            similarInterrupted = true
            similarQuestion = StudyAssistant.similarQuestionPortion(partial).takeIf { it.isNotBlank() }
            similarAnswer = Regex("解答[:：]([\\s\\S]+)").find(partial)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            similarStreamingText = null
        } else {
            // 追问阶段被中止：把已生成片段保留成正式消息，避免白等
            if (!partial.isNullOrBlank()) {
                similarMessages = similarMessages + ChatItem(similarMessages.size.toLong(), "assistant", partial)
            }
            similarStreamingText = null
        }
        persistSimilarConversation()
    }

    /** 同类题出题被中止后的「继续生成」：重新出题 */
    fun continueSimilar() {
        similarInterrupted = false
        startSimilar()
    }

    internal var similarChatRequest: suspend (String, List<DeepSeekMessage>, com.zsz.studyassistant.data.StreamOwner, suspend (String) -> Unit) -> String =
        { model, messages, owner, onDelta -> StudyAssistant.chatStream(model, messages, owner = owner, onDelta = onDelta) }

    fun sendSimilar(text: String, images: List<ByteArray> = emptyList()) {
        val qTitle = similarQuestion ?: return
        val txt = text.trim()
        if (txt.isEmpty() && images.isEmpty()) return
        abortSimilar()
        similarMessages = similarMessages + ChatItem(
            similarMessages.size.toLong(),
            "user",
            txt.ifBlank { "[图片]" },
            images.map { Base64.encodeToString(it, Base64.NO_WRAP) }
        )
        val mySeq = ++similarSeq
        similarStreamOwner?.cancel()
        similarJob?.cancel()
        val owner = com.zsz.studyassistant.data.StreamOwner()
        similarStreamOwner = owner
        similarBusy = true
        similarStreamingText = ""
        val history = similarMessages.map { it.copy(images = it.images?.toList()) }
        val baseCount = history.size
        val msgs = buildList {
            add(StudyAssistant.systemMessage(aiLang, customPromptText()))
            add(DeepSeekMessage("user", JsonPrimitive("题目：$qTitle")))
            similarAnswer?.takeIf { it.isNotBlank() }?.let { add(DeepSeekMessage("assistant", JsonPrimitive(it))) }
            addAll(chatMessages(history))
        }
        val model = if (history.any { !it.images.isNullOrEmpty() }) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        val request = similarChatRequest
        val progress = SimilarProgress(initial = false)
        similarProgress = progress
        similarJob = viewModelScope.launch {
            val app = getApplication<Application>()
            com.zsz.studyassistant.data.AnswerForegroundService.start(app, owner.id)
            val sb = progress.raw
            var lastEmit = 0L
            try {
                val reply = withContext(Dispatchers.IO) {
                    request(model, msgs, owner) { delta ->
                        sb.append(delta)
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 180) {
                            lastEmit = now
                            val snapshot = sb.toString()
                            withContext(Dispatchers.Main) {
                                if (mySeq == similarSeq) similarStreamingText = snapshot
                            }
                        }
                    }
                }
                if (mySeq == similarSeq) {
                    thinkingDone = true
                recordUsage(app, owner.usage)
                // ⚠️ 已删除「挂到 indexOfLast{assistant}」的旧代码：此处该挂的是**新建的** similarMessages 末条，
                //   而旧代码去改 chatItems 里的别的气泡（同类题页也不渲染思考块）。
                    similarStreamingText = null
                    similarMessages = similarMessages + ChatItem(baseCount.toLong(), "assistant", reply)
                    persistSimilarConversation()
                }
            } catch (e: Exception) {
                if (mySeq == similarSeq) {
                    val partial = sb.toString()
                    similarStreamingText = null
                    val aborted = e is CancellationException
                    if (partial.isNotBlank() || !aborted) {
                        similarMessages = similarMessages + ChatItem(
                            baseCount.toLong(),
                            "assistant",
                            if (partial.isNotBlank()) partial + (if (e is com.zsz.studyassistant.data.StreamIncompleteException) "\n\n" + friendlyError(e, "") else "")
                            else com.zsz.studyassistant.ui.stringsFor(uiLang).format("err.chatFailed", "msg" to friendlyError(e, ""))
                        )
                    }
                    persistSimilarConversation()
                }
            }
            if (mySeq == similarSeq) { similarBusy = false; similarProgress = null }
            com.zsz.studyassistant.data.AnswerForegroundService.stop(app, owner.id)
        }
    }

    // ---- 同类题：存错题本（逻辑与解题页一致：分类 + 标签，可取消保存）----
    private var similarIdentity = QuestionIdentity.create()
    private var similarCreatedAt = System.currentTimeMillis()
    var similarSavedQuestionId by mutableStateOf<QuestionId?>(null)
        private set

    // A followup can finish while the first insert is waiting for the write mutex.
    private var similarSavingQuestionId: QuestionId? = null
    private var similarSaveOwner: Any? = null

    private fun similarChats() = listOf(ChatItem(0,"question",similarQuestion.orEmpty()), ChatItem(1,"assistant",similarAnswer.orEmpty())) + similarMessages.mapIndexed { i,m -> m.copy(id=(i+2).toLong()) }
    private fun persistSimilarConversation() {
        val id = (similarSavedQuestionId ?: similarSavingQuestionId)?.takeIf { it == similarIdentity } ?: return
        if(id in deletionState.value.questionIds) return
        val chats=similarChats()
        val payload=com.zsz.studyassistant.data.SavePayload(id,similarCreatedAt,similarQuestion.orEmpty(),similarAnswer.orEmpty(),json.encodeToString(chats))
        queueNotebookSave(payload)
    }
    fun saveSimilarToNotebook(
        name: String?, categoryId: Long?, tagNames: List<String> = emptyList(), tagIds: List<Long> = emptyList()
    ) {
        val title = similarQuestion ?: return
        val id = similarIdentity
        val created = similarCreatedAt
        val seq = similarSeq
        val conversationJson = json.encodeToString(similarChats())
        val selectedTagIds = tagIds.toList()
        val selectedTagNames = tagNames.toList()
        val answerText = buildString {
            similarAnswer?.let { append(it) }
            for (m in similarMessages) {
                if (isNotEmpty()) append("\n\n")
                append(if (m.role == "assistant") "AI：" else "我：")
                append(m.content)
            }
        }
        val owner = Any()
        similarSaveOwner = owner
        similarSavingQuestionId = id
        viewModelScope.launch {
            try {
                notebookWriteMutex.withLock {
                    AppDatabase.get(getApplication()).withTransaction {
                        if (!dao.questionExists(id)) {
                            val cid = if (!name.isNullOrBlank()) dao.insertCategory(Category(name=name.trim())) else categoryId
                            dao.insertQuestionWithId(Question(id,title,answerText,created,categoryId=cid,conversationJson=conversationJson))
                            val finalIds = selectedTagIds.distinct().toMutableList()
                            for (tn in selectedTagNames.take(5)) {
                                val n = tn.trim()
                                if (n.isBlank()) continue
                                val tid = dao.getAllTagsOnce().firstOrNull { it.name==n }?.id ?: dao.insertTag(Tag(name=n))
                                if (tid !in finalIds) finalIds += tid
                            }
                            dao.insertQuestionTags(finalIds.take(5).map { QuestionTag(id,it) })
                            dao.insertReview(Review(id,nextReviewAt=System.currentTimeMillis()))
                        } else {
                            // Re-saving the same identity advances only its frozen content snapshot.
                            // Existing notebook classification, tags and review scheduling remain authoritative.
                            dao.updateAnswerContent(id, title, answerText, conversationJson)
                        }
                    }
                }
                if (id == similarIdentity) similarSavedQuestionId = id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == similarSeq) error = com.zsz.studyassistant.ui.stringsFor(uiLang)
                    .format("data.failed", "msg" to tagWriteError(e))
            } finally {
                if (similarSaveOwner === owner) {
                    similarSaveOwner = null
                    similarSavingQuestionId = null
                }
            }
        }
    }

    /** 取消保存（把刚存的同类题从错题本删掉） */
    fun unsaveSimilarFromNotebook() {
        val id = similarSavedQuestionId ?: return
        stageQuestionDeletion(listOf(id))
        // ★ 2026-10-14（用户要求）：不再实时问 AI 重算 —— 清空会话内预置即可；
        //   之后要再存，就按"已存信息/置空"处理（分类＝暂不分类、不勾选标签）。
        suggestedCategory = null
        suggestedTags = emptyList()
    }

    /** 从（可能不完整的）模型输出里提取「分类：/知识点：」，命中就写入会话建议 */
    private fun harvestSuggestions(text: String) {
        if (text.isBlank()) return
        runCatching {
            val cat = Regex("分类[：:]\\s*([^\\n\\r]+)").find(text)?.groupValues?.get(1)?.trim()
            if (!cat.isNullOrBlank() && cat.length <= 12) suggestedCategory = cat
            val tags = Regex("知识点[：:]\\s*([^\\n\\r]+)").find(text)?.groupValues?.get(1)
            if (!tags.isNullOrBlank()) {
                val list = tags.split("、", ",", "，").map { it.trim() }.filter { it.isNotBlank() }.take(5)
                if (list.isNotEmpty()) suggestedTags = list
            }
        }
    }
    /** 删除追问消息（按 chatItems 索引）。删除后构建消息仅基于剩余，AI 接续对话 */
    fun deleteMessages(indices: List<Int>) {
        if (indices.isEmpty()) return
        val toRemove = indices.toSet()
        chatItems = chatItems.filterIndexed { i, _ -> i !in toRemove }
    }

    /**
     * 退出页面/应用时调用（`SolveScreen` 的 `onDispose`）。
     *
     * ★ 2026-10-13 修复「忘了存错题本就退出 → 记录丢失」：
     *   原实现第一行是 `val id = savedQuestionId ?: return` —— **没存错题本就什么都不做**，
     *   整个会话就此消失。现在分两种情况：
     *   ① 已存错题本 → 照旧把完整对话更新进那条错题；
     *   ② 没存错题本 → 把这次会话落成/补齐一条**搜题记录**（首次 = INSERT，已落过 = UPDATE 同一条）。
     *   这也覆盖了"生成成功后又被中断"（onDone 没跑、记录不存在）的情况。
     */
    fun saveSessionOnExit() {
        if (chatItems.isEmpty()) return
        val id = savedQuestionId
        val snapshot = id?.takeIf { QuestionIdentity.valid(it) }?.let {
            Question(
                id = it, text = questionText, answer = lastAnswer(), imageBytes = imageBytes,
                conversationJson = json.encodeToString(chatItems), deleted = isDeleted,
                categoryId = currentQuestionCategoryId,
                createdAt = currentQuestionCreatedAt
            )
        }
        // 在启动任何异步任务之前捕获记录快照，而不是写库结束后再读取 VM。
        recordSolveSession(currentSource())
        if (snapshot != null && !snapshot.deleted) queueNotebookSave(com.zsz.studyassistant.data.SavePayload.notebook(snapshot))
    }

    /** 从解题页软删除当前已存的这条错题（可恢复） */
    var isDeleted by mutableStateOf(false)
        private set
    fun deleteSavedQuestion() { savedQuestionId?.let { stageQuestionDeletion(listOf(it)) } }
    fun restoreSavedQuestion() { deletionState.value.pending?.let { undoDelete(it.token) } }
    fun deleteFromNotebook(q: Question) { stageQuestionDeletion(listOf(q.id)) }

    // ---- 科目（分类）管理：重命名 / 批量删除 ----
    fun renameCategory(id: Long, newName: String) {
        val n = newName.trim()
        if (n.isEmpty()) return
        viewModelScope.launch { dao.renameCategory(id, n) }
    }

    /**
     * 批量删除科目。
     * deleteQuestions = true：连科目下的错题一起删（同时清掉标签关联与复习记录）
     * deleteQuestions = false：题目保留，改为「未分类」
     */
    fun deleteCategories(ids: List<Long>, deleteQuestions: Boolean) {
        if(ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val qids=if(deleteQuestions) dao.questionIdsInCategories(ids) else emptyList()
                if(savedQuestionId in qids) { stopActiveGeneration(); isDeleted=true; savedToNotebook=false }
                deleteQueue.stage(questions=qids.toSet(), categories=ids.toSet())
            } catch (_: Exception) { deleteError=com.zsz.studyassistant.ui.stringsFor(uiLang)["delete.failed"] }
        }
    }

    /** 批改题目：单张(题目)或两张(题目+手写答案) */
    var gradeResult by mutableStateOf("")
        private set
    var gradeBusy by mutableStateOf(false)
        private set

    // [已停用 2026-09-16] 非流式旧实现，保留备查（当前全部走流式 streamCall）
//     fun grade(questionBytes: ByteArray, answerBytes: ByteArray?) {
//         viewModelScope.launch {
//             gradeBusy = true
//             gradeResult = ""
//             val app = getApplication<Application>()
//             com.zsz.studyassistant.data.AnswerForegroundService.start(app)
//             try {
//                 gradeResult = StudyAssistant.gradeWithImages(questionBytes, answerBytes, aiLang)
//                 thinkingDone = true
//                 recordUsage(app)
//                thinkingText?.takeIf { it.isNotBlank() }?.let { th ->
//                    val idx = chatItems.indexOfLast { it.role == "assistant" }
//                    if (idx >= 0) {
//                        val list = chatItems.toMutableList()
//                        list[idx] = list[idx].copy(thinking = th)
//                        chatItems = list
//                    }
//                }
//             } catch (e: Exception) {
//                 gradeResult = com.zsz.studyassistant.ui.stringsFor(uiLang).format("err.gradeFailed", "msg" to (e.message ?: ""))
//             } finally {
//                 gradeBusy = false
//                 com.zsz.studyassistant.data.AnswerForegroundService.stop(app)
//             }
//         }
//     }

    fun clearGradeResult() { gradeResult = "" }

    /** 批改模式：复用解题页界面（标题显示「批改」），会话 = 题目/作答图 + 批改结果 + 后续问答 */
    var gradeMode by mutableStateOf(false)
        private set

    /**
     * 开始批改（流式、全屏复用解题页）：把「题目图 [+ 我的作答图]」作为一条提问，
     * 批改结果流式追加；之后可继续带图追问。
     */
    fun startGrade(questionBytes: ByteArray, answerBytes: ByteArray?, redoFor: QuestionId? = null) {
        prepareSession(grade = true)
        gradeMode = true
        pendingRedoFor = redoFor
        gradeTextQuestion = null
        val str = com.zsz.studyassistant.ui.stringsFor(uiLang)
        val imgs = buildList {
            add(questionBytes)
            answerBytes?.let { add(it) }
        }
        directImages = imgs
        questionFromPhoto = false
        questionText = if (answerBytes != null) {
            str["grade.label.question"] + " + " + str["grade.label.answer"]
        } else {
            str["grade.label.question"]
        }
        val encoded = imgs.map { Base64.encodeToString(it, Base64.NO_WRAP) }
        questionImages = encoded
        addItem("question", questionText, encoded)
        gradeCall()
    }

    /**
     * 批改（**纯文字题干** + 手写作答图）：B6 重做时，复习的题可能本来就没有原图。
     * 与 [startGrade] 的区别只有首轮消息的构造方式（文字题干 + 作答图）。
     */
    fun startGradeText(questionText: String, answerBytes: ByteArray, redoFor: QuestionId? = null) {
        prepareSession(grade = true)
        gradeMode = true
        pendingRedoFor = redoFor
        gradeTextQuestion = questionText
        val str = com.zsz.studyassistant.ui.stringsFor(uiLang)
        directImages = listOf(answerBytes)
        questionFromPhoto = false
        this.questionText = questionText
        val encoded = listOf(Base64.encodeToString(answerBytes, Base64.NO_WRAP))
        questionImages = encoded
        // 气泡：题干文字 + 我的作答图（与批改页的"题目 + 作答"语义一致）
        addItem("question", questionText + "\n" + str["grade.label.answer"], encoded)
        gradeTextCall(questionText)
    }

    /** 当前批改是否为"纯文字题干"（重新批改时据此选对构造方式） */
    private var gradeTextQuestion: String? = null

    /** 批改完成的公共收尾：解析分类/知识点 + 落一条助手消息 + 重做时给出待确认的掌握度建议 */
    private fun onGradeReply(reply: String) {
        // ★ 批改同样要解析分类/知识点（与拍题/直接提问一致），否则批改后存错题本无法预选
        val r = StudyAssistant.parseVisionOutput(reply)
        suggestedCategory = r.category
        suggestedTags = r.tags
        addItem("assistant", r.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
        // ★ 批改（source="grade"）不属于「4 个生成入口」，但同样要尽早落库，
        //   否则"批改完进程被杀"还是会丢（只在 saveSessionOnExit 里落就晚了）
        recordSolveSession("grade")
        // B6：重做的批改 → 按结论给出待确认的建议（一次性，取完即清）
        val redoFor = pendingRedoFor
        if (redoFor != null) {
            pendingRedoFor = null
            applyRedoResult(redoFor, reply)
        }
    }

    /** 批改请求（流式）；repeat 用于网络失败重试 */
    private fun gradeCall() {
        val imgs = directImages
        if (imgs.isEmpty()) return
        val msgs = listOf(
            StudyAssistant.languageSystemMessage(aiLang, customPromptText()),
            StudyAssistant.gradeUserMessage(imgs[0], imgs.getOrNull(1), aiLang, categoryNames, tagNames)
        )
        busyIsGrade = true
        streamCall(
            StudyAssistant.MODEL_VISION, msgs, prefix = "",
            onDone = { onGradeReply(it) },
            repeat = { gradeCall() }
        )
    }

    /** 文字题干版批改请求（B6 重做：题目没有原图） */
    private fun gradeTextCall(qText: String) {
        val ans = directImages.firstOrNull() ?: return
        val msgs = listOf(
            StudyAssistant.languageSystemMessage(aiLang, customPromptText()),
            StudyAssistant.gradeTextUserMessage(qText, ans, aiLang, categoryNames, tagNames)
        )
        busyIsGrade = true
        streamCall(
            StudyAssistant.MODEL_VISION, msgs, prefix = "",
            onDone = { onGradeReply(it) },
            repeat = { gradeTextCall(qText) }
        )
    }

    /** 重新批改（用当前会话的题目/作答图再跑一次） */
    fun regrade() {
        if (busy || streamInterrupted) return
        val text = gradeTextQuestion
        if (text != null) gradeTextCall(text) else gradeCall()
    }

    /** 当前这次流式请求是否为「批改」（决定按钮显示「⏸ 中止批改」还是「⏸ 中止生成」） */
    var busyIsGrade by mutableStateOf(false)
        private set

    /** 流式请求序号：用于识别"已被新请求取代的旧请求"，避免旧请求收尾时覆盖新请求的界面状态 */
    private var callSeq = 0L

    /** 加载某条错题的完整对话会话（用于续答） */
    /**
     * 装载完整 Question，隔离记录绑定；后续图片 / 标签查询只回填同一会话。
     * 错题本 getAll 已取完整字段；复习与历史入口在调用前 heavyOnce。
     * historySession 仅保留当前记录身份；返回来路仍由 AppNav 决定。
     */
    private suspend fun canOpenSavedQuestion(id: QuestionId, ownsSession: () -> Boolean = { true }): Boolean = solveSessionWriteMutex.withLock {
        try {
            val scan=withContext(Dispatchers.IO) { pendingSaveStore.scan() }
            val blocked=scan.unreadable>0 || scan.entries.any { it.payload.id==id } || id in pendingNotebookContent || id in deletionState.value.questionIds
            if(blocked && ownsSession()) error=com.zsz.studyassistant.ui.stringsFor(uiLang)["save.resolveFirst"]
            !blocked
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (ownsSession()) error=com.zsz.studyassistant.ui.stringsFor(uiLang)["save.resolveFirst"]
            false
        }
    }
    fun openNotebookQuestion(id: QuestionId, onReady: () -> Unit) {
        val seq = callSeq
        viewModelScope.launch {
            try {
                if (!canOpenSavedQuestion(id)) return@launch
                val full = dao.heavyOnce(id) ?: return@launch
                if (seq != callSeq || full.deleted) return@launch
                loadQuestion(full)
                onReady()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = com.zsz.studyassistant.ui.stringsFor(uiLang)["err.requestFailed"] }
        }
    }

    fun loadQuestion(q: Question, keepReview: Boolean = false, historySession: SolveSession? = null) {
        // 调用方提供完整 Question：错题本来自 getAll，复习 / 记录入口先 heavyOnce。
        if (keepReview && reviewMode) saveSessionOnExit()
        stopActiveGeneration()
        resetSession()
        sessionRecordBinding = SolveSessionBinding(
            id = q.id,
            createdAt = QuestionIdentity.earliest(q.createdAt, historySession?.createdAt ?: 0L),
            source = historySession?.source,
            historyAllowed = historySession != null
        )
        val binding = sessionRecordBinding
        exitRedo()
        reviewMode = keepReview
        chatItems = runCatching {
            json.decodeFromString<List<ChatItem>>(q.conversationJson.orEmpty())
        }.getOrDefault(emptyList())
        if (chatItems.isEmpty()) {
            chatItems = listOf(ChatItem(0, "question", q.text), ChatItem(1, "assistant", q.answer))
        }
        currentNotebookBaseline=com.zsz.studyassistant.data.SavePayload.notebook(q)
        questionText = q.text
        imageBytes = q.imageBytes
        isPhoto = q.imageBytes != null
        savedToNotebook = !q.deleted
        savedQuestionId = q.id
        isFromNotebook = true
        isDeleted = q.deleted
        currentQuestionCategoryId = q.categoryId
        currentQuestionCreatedAt = q.createdAt
        questionImages = mergeImageKeys(
            q.imageBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            questionImageBase64(q.conversationJson)
        )
        questionFromPhoto = q.imageBytes != null
        if (keepReview) currentReviewQuestion = q
        viewModelScope.launch {
            try {
                val rows = dao.imagesForQuestion(q.id).sortedBy { it.position }
                val storedKeys = rows.map { Base64.encodeToString(it.bytes, Base64.NO_WRAP) }
                val fallbackKeys = mergeImageKeys(
                    q.imageBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                    questionImageBase64(q.conversationJson)
                )
                val keys = mergeImageKeys(storedKeys.firstOrNull(), storedKeys.drop(1) + fallbackKeys)
                val cats = dao.allCategoriesOnce()
                val allTags = dao.getAllTagsOnce()
                val tagIds = dao.tagIdsForQuestion(q.id)
                if (binding !== sessionRecordBinding || savedQuestionId != q.id) return@launch
                questionImages = keys
                directImages = keys.mapNotNull {
                    runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
                }
                if (rows.size < MAX_QUESTION_IMAGES && fallbackKeys.any { it !in storedKeys }) {
                    showLegacyImageWarn = true
                }
                currentQuestionTags = tagIds
                suggestedCategory = cats.firstOrNull { it.id == q.categoryId }?.name
                suggestedTags = tagIds.mapNotNull { id -> allTags.firstOrNull { it.id == id }?.name }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (binding === sessionRecordBinding) {
                    error = com.zsz.studyassistant.ui.stringsFor(uiLang)
                        .format("data.failed", "msg" to (e.message ?: ""))
                }
            }
        }
    }
}

/** 时间戳若无有效值(0)则回退为当前时间 */
private fun Long.ifZeroToNow(): Long = if (this > 0) this else System.currentTimeMillis()

/** 艾宾浩斯间隔档位（天级）：0,1,2,4,7,15,30 */
private val INTERVAL_DAYS = intArrayOf(0, 1, 2, 4, 7, 15, 30)
private const val DAY_MS = 86400000L

/** 在解答底部附加「核心知识点/难点」提示（跟随界面语言） */
private fun com.zsz.studyassistant.data.StudyAssistant.SolveResult.withHint(s: com.zsz.studyassistant.ui.Strings): String =
    if (tags.isNotEmpty()) answer + s["solve.tagsHint"] + tags.joinToString("、") else answer
