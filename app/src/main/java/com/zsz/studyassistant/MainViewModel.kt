package com.zsz.studyassistant

import androidx.room3.support.getSupportWrapper
import android.app.Application
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room3.withWriteTransaction
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zsz.studyassistant.data.AiLang
import com.zsz.studyassistant.data.AiLangStore
import com.zsz.studyassistant.data.AppDatabase
import com.zsz.studyassistant.data.Category
import com.zsz.studyassistant.data.StatsSnapshot
import com.zsz.studyassistant.data.StudyStatistics
import com.zsz.studyassistant.data.SolveRequestLifecycle
import com.zsz.studyassistant.data.continuationContext
import com.zsz.studyassistant.data.messagesForRequest
import com.zsz.studyassistant.data.DeepSeekMessage
import com.zsz.studyassistant.data.gradingInput
import com.zsz.studyassistant.data.reviewGradingInput
import com.zsz.studyassistant.data.KeyManager
import com.zsz.studyassistant.data.Question
import com.zsz.studyassistant.data.SaveSnapshot
import com.zsz.studyassistant.data.SimilarExerciseGeneration
import com.zsz.studyassistant.data.SimilarExerciseOutput
import com.zsz.studyassistant.data.QuestionPreview
import com.zsz.studyassistant.data.SolveSessionPreview
import com.zsz.studyassistant.data.toPreview
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
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
data class ChatItem(val id: Long, val role: String, val content: String, val images: List<String>? = null,
                    val thinking: String? = null, val quote: QuotedFollowUp? = null,
                    val messageKey: String? = null, val gradePart: String? = null,
                    val notebookSuggestions: com.zsz.studyassistant.data.NotebookSuggestions? = null,
                    val answerPending: Boolean = false,
                    val aiContext: com.zsz.studyassistant.data.AiResponseContext? = null,
                    val documents: List<com.zsz.studyassistant.data.ConversationDocument> = emptyList(),
                    val ocrCorrections: List<String?> = emptyList(),
                    /** Optional history label, stored only on the first message. */
                    val conversationTitle: String? = null,
                    /** Per-request exclusions; originals remain attached to their historical messages. */
                    val excludedDocumentHashes: List<String> = emptyList(),
                    val promptInventory: com.zsz.studyassistant.data.PromptInventory? = null,
                    val bookmarks: List<ConversationBookmark> = emptyList())

internal fun withConversationTitle(items: List<ChatItem>, title: String?): List<ChatItem> {
    val label = com.zsz.studyassistant.data.normalizedConversationTitle(title) ?: return items
    val first = items.firstOrNull() ?: return items
    if (!first.conversationTitle.isNullOrBlank()) return items
    return listOf(first.copy(conversationTitle = label)) + items.drop(1)
}

internal fun protectedMessageIndices(items: List<ChatItem>): Set<Int> = buildSet {
    items.forEachIndexed { index, item -> if (item.role == "question") add(index) }
    items.forEachIndexed { index, item -> if (item.gradePart == "answer") add(index) }
}

/** Optional presentation metadata. content still contains the complete legacy/API text. */
@Serializable
data class QuotedFollowUp(val text: String, val question: String, val anchor: QuoteAnchor? = null)

@Serializable
data class QuoteAnchor(val messageKey: String, val scope: String, val start: Int, val end: Int,
                       val sourceHash: String, val visualMath: Boolean = false)

/** Legacy numeric IDs can be reused after deletion. Quote links use independent persistent keys. */
internal fun withStableMessageKeys(items: List<ChatItem>): List<ChatItem> {
    val counts = items.mapNotNull { it.messageKey }.groupingBy { it }.eachCount()
    return items.map { item ->
        if (!item.messageKey.isNullOrBlank() && counts[item.messageKey] == 1) item
        else item.copy(messageKey = java.util.UUID.randomUUID().toString())
    }
}

/** A filtered display order must never be used directly to delete persisted messages. */
internal fun messageIndicesForKeys(items: List<ChatItem>, keys: List<String?>): List<Int?> {
    val unique = items.withIndex().filter { !it.value.messageKey.isNullOrBlank() }
        .groupBy { it.value.messageKey }.mapValues { (_, entries) -> entries.singleOrNull()?.index }
    return keys.map { key -> key?.let(unique::get) }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    internal val notebookPractice by lazy { com.zsz.studyassistant.data.NotebookPracticeStore.get(app) }
    private val documentStore by lazy { com.zsz.studyassistant.data.DocumentStore(getApplication()) }
    val sharedImages = com.zsz.studyassistant.data.SharedImageInbox(app, viewModelScope)

    init { KeyManager.init(app) }
    private val calendarDay = MutableStateFlow(com.zsz.studyassistant.data.CalendarDay.now())
    val currentDay: StateFlow<com.zsz.studyassistant.data.CalendarDay> get() = calendarDay
    fun refreshClock() {
        val day = com.zsz.studyassistant.data.CalendarDay.now()
        if (day == calendarDay.value) return
        calendarDay.value = day
        if (reviewMode) viewModelScope.launch { refreshReviewPlan() }
    }
    // Draft text stays in memory across Activity recreation, never in a Binder saved-state Bundle.
    val customPromptEditor by lazy { com.zsz.studyassistant.ui.SubjectPromptEditor(app, viewModelScope) }

    var reviewOpenRequested by mutableStateOf(false)
        private set
    fun requestReviewNavigation() { reviewOpenRequested = true }
    fun consumeReviewNavigation() { reviewOpenRequested = false }


    private val dao = AppDatabase.get(app).questionDao()
    private val json = Json { ignoreUnknownKeys = true }

    private val categoryOrderPrefs = com.zsz.studyassistant.data.CategoryOrderPrefs(app)
    var customCategoryOrderEnabled by mutableStateOf(categoryOrderPrefs.enabled())
        private set
    var customCategoryOrder by mutableStateOf(categoryOrderPrefs.order())
        private set
    fun updateCustomCategoryOrderEnabled(enabled: Boolean) {
        customCategoryOrderEnabled = enabled
        categoryOrderPrefs.setEnabled(enabled)
    }
    fun updateCustomCategoryOrder(order: List<Long>) {
        customCategoryOrder = order.distinct()
        categoryOrderPrefs.save(customCategoryOrder)
    }

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

    var wallpaperBackground by mutableStateOf(
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("wallpaper_background", false)
    )
        private set
    fun updateWallpaperBackground(enabled: Boolean) {
        wallpaperBackground = enabled
        getApplication<Application>().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("wallpaper_background", enabled).apply()
    }

    var wallpaperShade by mutableStateOf(com.zsz.studyassistant.ui.WallpaperShade.fromId(
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString("wallpaper_shade", null)
    ))
        private set
    fun updateWallpaperShade(shade: com.zsz.studyassistant.ui.WallpaperShade) {
        wallpaperShade = shade
        getApplication<Application>().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("wallpaper_shade", shade.id).apply()
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
        invalidateSimilarSaves(batch.questions)
        solveSessionWriteMutex.withLock {
            // Capture any new IDs before their last notebook/review rows disappear.
            if (batch.questions.isNotEmpty()) {
                try {
                    dailyReviewStore.refresh(dao, onlyIfStarted = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A local progress snapshot must not prevent the confirmed deletion.
                }
            }
            AppDatabase.get(getApplication()).withWriteTransaction {
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
        invalidateSimilarSaves(ids)
        if(savedQuestionId in ids) { stopActiveGeneration(); isDeleted=true; savedToNotebook=false }
        deleteQueue.stage(questions=ids.toSet())
    }

    /** 错题本数据流（A5：按用户选的排序方式排好；排序变化 → 立即重新发射） */
    val notebook: StateFlow<List<QuestionPreview>> =
        kotlinx.coroutines.flow.combine(
            dao.getAll(), dao.allReviews(), notebookSort, deletionState
        ) { allQuestions, rs, mode, deletes ->
            val qs=allQuestions.filterNot { it.id in deletes.questionIds }
            com.zsz.studyassistant.data.NotebookSorting.sort(qs, rs.associateBy { it.questionId }, mode)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * B9 查重：题干（归一化后）相同的已有错题。
     * 只查未删除的题、只取轻字段（不含图片 BLOB），太短的题干不比（见 DuplicateCheck）。
     */
    suspend fun findDuplicateQuestion(text: String, excludeId: QuestionId? = null): Question? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                dao.findDuplicate(text, excludeId)
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
    /** A nullable match map reports an IO failure, not an empty successful search. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun notebookMatches(query: String): Flow<Pair<String,Map<QuestionId,com.zsz.studyassistant.data.SearchSnippet>?>> = dao.questionSearchChanges().mapLatest {
        if(query.isNotBlank()) kotlinx.coroutines.delay(200)
        Pair<String,Map<QuestionId,com.zsz.studyassistant.data.SearchSnippet>?>(query,dao.matchingQuestionSnippets(query))
    }.flowOn(Dispatchers.IO).catch { emit(query to null) }

    val stats: StateFlow<StatsSnapshot> =
        kotlinx.coroutines.flow.combine(
            dao.questionStats().distinctUntilChanged(), dao.allReviews(), dao.categories(), calendarDay, deletionState
        ) { allQuestions, rs, cats, day, deletes ->
            StudyStatistics.calculate(allQuestions, rs, cats, day, deletes.questionIds)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsSnapshot())
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
    private val sessionState = SolveSessionState()
    private val requestLifecycle = SolveRequestLifecycle()
    private val generation = com.zsz.studyassistant.data.ConversationGeneration(
        app, viewModelScope, requestLifecycle, { documentStore }, { usage, calls -> recordUsage(app, usage, calls) }
    )
    var imageBytes by sessionState::imageBytes
    var chatItems by sessionState::chatItems
        private set
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
        private set
    var savedToNotebook by mutableStateOf(false)
        private set
    var savedQuestionId by mutableStateOf<QuestionId?>(null)
        private set
    var isFromNotebook by mutableStateOf(false)
        private set
    var networkError by mutableStateOf(false)
        private set
    private var saving: Boolean
        get() = sessionRecordBinding.saving
        set(value) { sessionRecordBinding.saving = value }
    private var cancelPending: Boolean
        get() = sessionRecordBinding.cancelPending
        set(value) { sessionRecordBinding.cancelPending = value }
    private var isPhoto = false
    /** 本次会话是否来自「图文提问」（用于标题统一为"解题"） */
    var askMode by mutableStateOf(false)
        private set
    /** 供界面判断：当前会话是否来自"拍照搜题"（返回时决定回拍题页还是返回来处） */
    val isPhotoSession: Boolean get() = isPhoto
    private var questionText by sessionState::questionText
    /** 只读访问当前题干（B9 查重等界面逻辑用；questionText 本身保持私有以免被误改） */
    val questionTextForUi: String get() = questionText
    /** 直接提问携带的附图（文字 + 多图） */
    private var directImages by sessionState::directImages
    /** 拍照搜题（多张）携带的图，作为题目一起识别 */
    private var multiImages by sessionState::multiImages

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

    internal var solveInputDraft by mutableStateOf(com.zsz.studyassistant.ui.SolveInputDraft())
        private set
    private var inputDraftObserver: Job? = null
    private var inputDraftRestore: Job? = null
    private var clearingInputDrafts = false
    private val inputDraftFailures = mutableSetOf<String>()
    private val inputDrafts = com.zsz.studyassistant.data.InputDraftPersistence.shared(app)
    init {
        viewModelScope.launch {
            inputDrafts.saveState.collect { state ->
                val failures = state.failedKeys
                val newlyFailed = failures.any { it !in inputDraftFailures }
                inputDraftFailures.clear(); inputDraftFailures.addAll(failures)
                solveInputDraft.saveFailed = solveInputDraft.storageKey in failures
                if (newlyFailed) error = com.zsz.studyassistant.ui.stringsFor(uiLang)["inputDraft.failed"]
            }
        }
    }

    /** Snapshot now, including the last keystroke; IO stays serialized outside the UI thread. */
    internal fun flushInputDraft(): Boolean {
        if (clearingInputDrafts) return true
        val draft = solveInputDraft
        if (!draft.editable) return true // Failed originals remain untouched until explicit discard.
        return try { draft.snapshot()?.let { inputDrafts.save(it, draft.id) } ?: true }
        catch (_: OutOfMemoryError) { draft.saveFailed = true; error = com.zsz.studyassistant.ui.stringsFor(uiLang)["inputDraft.failed"]; false }
    }
    private fun observeInputDraft(draft: com.zsz.studyassistant.ui.SolveInputDraft) {
        inputDraftObserver?.cancel()
        inputDraftObserver = viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { draft.snapshot() }.collectLatest { value ->
                kotlinx.coroutines.delay(350)
                if (!clearingInputDrafts && solveInputDraft === draft && draft.editable && value != null && value == draft.snapshot()) inputDrafts.save(value, draft.id)
            }
        }
    }
    private fun bindInputDraft(key: String) {
        inputDraftObserver?.cancel(); inputDraftRestore?.cancel()
        val draft = com.zsz.studyassistant.ui.SolveInputDraft(key).also { it.restoring = true; it.saveFailed = key in inputDraftFailures }
        inputDrafts.reserve(key, draft.id)
        solveInputDraft = draft
        inputDraftRestore = viewModelScope.launch {
            try {
                val value = inputDrafts.read(key)
                if (solveInputDraft !== draft) return@launch
                value?.let(draft::restore)
                draft.restoreFailed = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (solveInputDraft === draft) draft.restoreFailed = true }
            catch (_: OutOfMemoryError) { if (solveInputDraft === draft) draft.restoreFailed = true }
            finally {
                if (currentCoroutineContext().isActive && solveInputDraft === draft) {
                    draft.restoring = false; if (!draft.restoreFailed) observeInputDraft(draft)
                }
            }
        }
    }
    internal fun retryInputDraft() {
        val draft = solveInputDraft
        if (draft.restoreFailed) draft.storageKey?.let(::bindInputDraft)
        else { inputDrafts.retry(); flushInputDraft() }
    }
    /** Called only by the confirmation dialog, never by failed restoration. */
    internal fun discardInputDraft() {
        val draft = solveInputDraft
        inputDraftRestore?.cancel(); inputDraftObserver?.cancel()
        draft.storageKey?.let { inputDrafts.clear(it, draft.id) }
        draft.clearInput(); draft.restoring = false; draft.restoreFailed = false; draft.saveFailed = false
        observeInputDraft(draft)
    }
    private fun acceptInputDraft(draft: com.zsz.studyassistant.ui.SolveInputDraft) {
        // Submit the tombstone synchronously before any delayed observation can publish old input.
        draft.storageKey?.let { inputDrafts.clear(it, draft.id) }
        draft.clearInput()
    }
    private var sessionRecordBinding = SolveSessionBinding()
    private val solveSessionWriteMutex = kotlinx.coroutines.sync.Mutex()
    private val persistence = com.zsz.studyassistant.data.ConversationPersistence(
        app, viewModelScope, solveSessionWriteMutex, ::historyCutoff,
        isNotebookBlocked = { it in deletionState.value.questionIds || it in deletedNotebookIds },
        isNotebookDeleted = { it in deletedNotebookIds },
        onNotebookSaved = { if (savedQuestionId == it.id) currentNotebookBaseline = it }
    )
    private val historyWriteQueue get() = persistence.historyWriteQueue
    var failedHistoryWrites by persistence::failedHistoryWrites
        private set
    val retryingHistoryWrites get() = persistence.retryingHistoryWrites
    private val conversationDrafts = com.zsz.studyassistant.data.ConversationDraftPersistence(
        app, viewModelScope,
        onSaved = { saved ->
            if (activeDraft?.generation == saved.generation) activeDraft = activeDraft?.copy(baseline = saved.baseline)
        },
        onFailure = { draftError = com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.saveFailed"] },
        refresh = { refreshDrafts() }
    )
    private var activeDraft: com.zsz.studyassistant.data.ConversationDraft? = null
    private var currentNotebookBaseline: com.zsz.studyassistant.data.SavePayload? = null
    internal fun notebookMatchText(id: QuestionId, answer: Boolean): String? =
        currentNotebookBaseline?.takeIf { it.id == id }?.let { if (answer) it.answer else it.text }
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
            val scan=conversationDrafts.summaries()
            draftSummaries=scan.first; draftIssue=scan.second>0
        } catch (_: Exception) { draftIssue=true }
    }
    private suspend fun writeDraft(value: com.zsz.studyassistant.data.ConversationDraft,
        baseline: com.zsz.studyassistant.data.SavePayload?) {
        conversationDrafts.write(value, baseline)
    }
    private fun checkpointDraft(refresh: Boolean = true) {
        val value = activeDraft?.copy(revision = ++draftRevision) ?: return
        conversationDrafts.checkpoint(value, currentNotebookBaseline, refresh)
    }
    private suspend fun persistCompletedConversation() {
        saveSessionOnExit()
        persistence.awaitWrites()
    }
    private suspend fun draftCanBeCleared(value: com.zsz.studyassistant.data.ConversationDraft) =
        persistence.draftCanBeCleared(value)
    /** A later explicit save/retry may finally give an already completed draft a durable home. */
    private suspend fun cleanupPersistedDrafts() {
        try {
            persistence.cleanupCompletedDrafts(conversationDrafts) { value ->
                if (activeDraft?.generation == value.generation) activeDraft = null
            }
            refreshDrafts()
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { draftIssue=true }
    }
    internal suspend fun finishDraftCleanup(value: com.zsz.studyassistant.data.ConversationDraft) {
        try { conversationDrafts.clear(value.id,value.generation) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { draftIssue=true; return }
        if(activeDraft?.generation==value.generation) activeDraft=null
    }
    fun discardDraft(id: QuestionId) {
        viewModelScope.launch {
            try {
                val value=conversationDrafts.read(id) ?: return@launch
                conversationDrafts.clear(id,value.generation)
                if(activeDraft?.generation==value.generation) { activeDraft=null; discardInterrupted() }
                refreshDrafts()
            } catch (_: Exception) { draftError=com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.saveFailed"] }
        }
    }

    fun resumeDraft(id: QuestionId, origin: String) {
        if(busy || reviewBusy || dataBusy) return
        val request = openRequests.begin()
        var expectedBinding = sessionRecordBinding
        var expectedCall = callSeq
        fun current() = openRequests.isCurrent(request) && sessionRecordBinding === expectedBinding && callSeq == expectedCall
        viewModelScope.launch {
            try {
                val value=checkNotNull(conversationDrafts.read(id))
                val row=solveSessionWriteMutex.withLock { dao.heavyOnce(id) }
                if (!current()) return@launch
                if (value.notebookId==null && row!=null) {
                    check(!row.deleted && id !in deletionState.value.questionIds)
                    check(com.zsz.studyassistant.data.ConversationDraftCodec.matchesNotebook(value, row)) { "Draft conflict" }
                }
                if(value.notebookId!=null) {
                    check(row!=null && !row.deleted && id !in deletionState.value.questionIds)
                    val actual=withContext(Dispatchers.IO) { com.zsz.studyassistant.data.PendingSaveCodec.fingerprint(com.zsz.studyassistant.data.SavePayload.notebook(row)) }
                    if(actual!=value.baseline) {
                        val completed=value.completedChats?.let { chats -> com.zsz.studyassistant.data.SavePayload(id,value.createdAt,
                            value.completedQuestion ?: value.question,chats.lastOrNull { it.role=="assistant" }?.content.orEmpty(),json.encodeToString(chats)) }
                        if(completed!=null && actual==com.zsz.studyassistant.data.PendingSaveCodec.fingerprint(completed)) {
                            if (!current()) return@launch
                            conversationDrafts.clear(id,value.generation)
                            if (!current()) return@launch
                            if (!loadQuestion(row, ownsOpen = ::current, openRequest = request)) return@launch
                            expectedBinding = sessionRecordBinding
                            expectedCall = callSeq
                            refreshDrafts()
                            if (current()) { draftReturnOrigin=origin; draftSolveRequested=true }
                            return@launch
                        }
                        error("Draft conflict")
                    }
                }
                // Finish fallible reads before replacing the visible session.
                val history = dao.sessionLightOnce(id)
                val images = if (value.grade) emptyList() else withContext(Dispatchers.IO) {
                    value.images.map { Base64.decode(it, Base64.NO_WRAP) }
                }
                val tags = row?.let { dao.tagIdsForQuestion(it.id) }.orEmpty()
                val baseline = row?.let { withContext(Dispatchers.Default) { com.zsz.studyassistant.data.SavePayload.notebook(it) } }
                if (!current() || busy || reviewBusy || dataBusy) return@launch
                stopActiveGeneration()
                // Old grading never created a history row. Migrate only those legacy drafts;
                // a modern deleted/pruned history must remain missing.
                val legacyGrade = value.grade && !value.gradeHistoryEnabled && !value.historyAllowed &&
                    value.notebookId == null && value.createdAt >= historyCutoff() &&
                    id !in deletionState.value.historyIds && id !in deletionState.value.questionIds
                val prepared = PreparedConversation(id, value.createdAt, value.source,
                    (value.historyAllowed && history != null) || legacyGrade, value.question,
                    value.base, value.images, images, images.firstOrNull(), value.photo,
                    ask = value.ask, grade = value.grade, notebook = row, notebookBaseline = baseline, tagIds = tags)
                if (!applyPreparedConversation(prepared)) return@launch
                expectedCall = callSeq
                expectedBinding = sessionRecordBinding
                pendingRedoFor = value.redoFor
                promptInventoryBinding = sessionRecordBinding
                requestInventory = value.promptInventory ?: chatItems.firstOrNull()?.promptInventory
                conversationDrafts.reserve(value.id,value.generation)
                val recovered = if (legacyGrade) value.copy(historyAllowed=true, gradeHistoryEnabled=true) else value
                activeDraft=recovered
                draftRevision=maxOf(draftRevision,value.revision)
                if (legacyGrade) writeDraft(recovered.copy(revision=++draftRevision),currentNotebookBaseline)
                if (!current()) return@launch
                if(value.complete && value.completedChats!=null) {
                    chatItems=value.completedChats; questionText=value.completedQuestion ?: value.question
                    if (legacyGrade) recordSolveSession("grade", create=true)
                    persistCompletedConversation()
                    if(draftCanBeCleared(value)) finishDraftCleanup(value)
                } else {
                    if (legacyGrade) recordSolveSession("grade", create=true)
                    // Older drafts may contain raw inline reasoning markers. Apply the
                    // same split as live/interrupted output before display and continuation.
                    val partial = com.zsz.studyassistant.data.ThinkingOutput.split(value.raw)
                    streamingText=partial.body; thinkingText=value.thinking ?: partial.thinking; streamInterrupted=true
                    activeDraft=activeDraft?.copy(raw=partial.body, thinking=thinkingText)
                    webEnabled=value.webEnabled
                    requestLifecycle.continuation = SolveRequestLifecycle.Continuation(
                        value.model, value.action, value.webEnabled,
                        continuationContext(value.messages, partial.body, value.requestPrefix, value.webEnabled,
                            value.continuationHistory, value.continuationAnnotations, value.resumableContext)
                    ) { output -> applyRecoveredReply(value.action, output) }
                }
                if (!current()) return@launch
                restoreGradeContext(value.grade)
                refreshDrafts()
                if (current()) { draftReturnOrigin=origin; draftSolveRequested=true }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (current()) draftError=com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.conflict"] }
        }
    }
    private fun applyRecoveredReply(action: String, output: String) {
        when(action) {
            "restore_initial", "restore_grade" -> {
                val result = StudyAssistant.parseVisionOutput(output)
                suggestedCategory = result.category; suggestedTags = result.tags
                chatItems = restoreInitialAnswer(chatItems, ChatItem(0, "assistant",
                    result.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)), thinking = thinkingText,
                    notebookSuggestions = com.zsz.studyassistant.data.NotebookSuggestions(result.category, result.tags),
                    aiContext = completedAiContext))
            }
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

    private val pendingSaveStore get() = persistence.pendingSaveStore
    private val pendingNotebookContent get() = persistence.pendingNotebookContent
    var hasSaveActivity by persistence::hasSaveActivity
        private set
    val saveRecoveryReady get() = persistence.saveRecoveryReady
    val pendingSaveCount get() = persistence.pendingSaveCount
    var saveJournalIssue by persistence::saveJournalIssue
        private set
    val saveOperations get() = persistence.saveOperations
    val saveRevision get() = persistence.saveRevision
    var saveFeedback by mutableStateOf<String?>(null)
        private set
    var saveFeedbackIsError by mutableStateOf(false)
        private set
    fun clearSaveFeedback() { saveFeedback = null }

    init { viewModelScope.launch { persistence.recover() } }

    private fun historyCutoff() = System.currentTimeMillis() - SOLVE_SESSION_KEEP_DAYS * DAY_MS
    private suspend fun refreshPendingSaveState() = persistence.refresh()
    private fun queueNotebookSave(payload: com.zsz.studyassistant.data.SavePayload) = persistence.queueNotebookSave(payload)
    private fun queueNotebookSave(snapshot: SaveSnapshot<com.zsz.studyassistant.data.SavePayload>) = persistence.queueNotebookSave(snapshot)

    fun exportPendingSaves(uri: android.net.Uri) {
        viewModelScope.launch {
            solveSessionWriteMutex.withLock {
                try {
                    val (entries, skippedEntries) = persistence.exportEntries()
                    val (drafts, skippedDrafts) = conversationDrafts.exportable()
                    withContext(Dispatchers.IO) {
                        val resolver = getApplication<Application>().contentResolver
                        checkNotNull(resolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
                            it.write(com.zsz.studyassistant.data.PendingSaveCodec.export(entries, drafts))
                        }
                    }
                    val skipped = skippedEntries + skippedDrafts
                    val strings = com.zsz.studyassistant.ui.stringsFor(uiLang)
                    saveFeedbackIsError = skipped > 0
                    saveFeedback = if (skipped == 0) strings["save.exportDone"] else strings.format(
                        "save.exportPartial", "saved" to (entries.size + drafts.size).toString(), "skipped" to skipped.toString())
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
                db.withWriteTransaction {
                    dao.purgeDeletedQuestions()
                    dao.purgeOrphanReviews()
                    dao.purgeOrphanQuestionTags()
                    dao.purgeOrphanImages()
                }
                repairQuestionImages()
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
            val session = dao.historyImageSource(q.id)
            session?.imageBytes?.let { result.add(it) }
            session?.imagesJson?.let { keys += json.decodeFromString<List<String>>(it) }
            keys += questionImageBase64(session?.conversationJson)
        }
        keys.filter { it.isNotBlank() }.forEach { result.add(Base64.decode(it, Base64.NO_WRAP)) }
        return result
    }

    /** One bounded pass; each question commits separately so normal writes can interleave. */
    private suspend fun repairQuestionImages() {
        val db = AppDatabase.get(getApplication())
        var after: QuestionId = ""
        while (true) {
            val ids = dao.imageRepairIds(after, 32)
            if (ids.isEmpty()) break
            for (id in ids) {
                currentCoroutineContext().ensureActive()
                db.withWriteTransaction<Unit> {
                    val row = dao.questionPreviewOnce(id)?.takeUnless { it.deleted } ?: return@withWriteTransaction
                    val source = dao.questionImageSource(id)
                    val q = Question(id = row.id, text = "", answer = "", imageBytes = source.imageBytes,
                        conversationJson = source.conversationJson)
                    val images = recoverableImages(q, includeSession = true)
                    dao.appendMissingImages(id, images, "repair")
                    if (source.imageBytes == null) images.firstOrNull { it.isNotEmpty() }?.let {
                        dao.setQuestionImage(id, it)
                    }
                }
            }
            after = ids.last()
        }
    }

    /** 原图只按内容去重追加；保留已有主图和顺序，缺图插入失败向上传播。 */
    private suspend fun writeQuestionImages(qid: QuestionId, images: List<ByteArray>, source: String = "save") {
        if (images.isEmpty()) return
        dao.appendMissingImages(qid, images, source)
    }

    /** 推理模型的思考流（浅灰小字展示；非推理模型为 null） */
    var thinkingText by sessionState::thinkingText
        private set
    /** 思考是否已结束（结束后不再自动跟随、并触发跳转到答案开头） */
    var thinkingDone by sessionState::thinkingDone
        private set
    /** 正在做兜底分类请求（C）：防止重复发起 */
    private var classifying = false

    /** 供界面显示的"题目图"（base64）：原题/我的提问附图，统一作为浅绿用户气泡显示。
     *  只用于显示，不写入会话 JSON（避免数据库膨胀），旧题由 loadQuestion 从 imageBytes 注入。 */
    var questionImages by sessionState::questionImages
        private set

    /** 当前这道题是否"拍照题"：是则题目气泡只显示原图，不显示 AI 转译题干（题干仍保存，供错题本/搜索/编辑用） */
    var questionFromPhoto by sessionState::questionFromPhoto
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

    internal val captureFlow: com.zsz.studyassistant.ui.CaptureFlowController by lazy {
        com.zsz.studyassistant.ui.CaptureFlowController(getApplication(), viewModelScope, ::refreshUsage) { images, grade, corrections ->
            if (grade) startGrade(images[0], images.getOrNull(1), correctedTexts = corrections)
            else if (corrections.any { !it.isNullOrBlank() }) solveDirect(ocrCorrectionText(corrections), images, fromCamera = true)
            else if (images.size >= 2) solveWithImages(images) else solveWithImage(images[0])
        }
    }
    internal var attachmentCropFlow by mutableStateOf<com.zsz.studyassistant.ui.CaptureFlowController?>(null)
        private set

    /** Input attachments are transient and must never overwrite the camera recovery queue. */
    internal suspend fun prepareAttachmentCrop(owner: com.zsz.studyassistant.ui.SolveInputDraft,
                                               uris: List<android.net.Uri>): Boolean {
        if (solveInputDraft !== owner || !owner.editable || owner.importingImages) return false
        val batch = uris.distinct().filterNot { it in owner.attachments }.take((3 - owner.attachments.size).coerceAtLeast(0))
        if (batch.isEmpty()) return false
        var files: List<java.io.File>? = null
        var accepted = false
        owner.importingImages = true
        try {
            withContext(Dispatchers.IO) {
                files = com.zsz.studyassistant.ui.urisToTempFiles(getApplication(), batch, "askcrop_")
            }
            val imported = files ?: return false
            if (solveInputDraft !== owner) return false
            attachmentCropFlow?.cancelCropFlow()
            attachmentCropFlow = com.zsz.studyassistant.ui.CaptureFlowController(
                getApplication(), viewModelScope, ::refreshUsage, persistCheckpoints = false
            ) { images, _, corrections ->
                if (solveInputDraft === owner && images.size == batch.size) {
                    if (images.any { it.size > com.zsz.studyassistant.data.InputDraftStore.MAX_IMAGE_BYTES } ||
                        (owner.images.values.sumOf { it.size.toLong() } + images.sumOf { it.size.toLong() }) >
                        com.zsz.studyassistant.data.InputDraftStore.MAX_IMAGES_BYTES) {
                        owner.saveFailed = true
                        error = com.zsz.studyassistant.ui.stringsFor(uiLang)["inputDraft.failed"]
                        return@CaptureFlowController
                    }
                    owner.images.putAll(batch.zip(images))
                    batch.forEachIndexed { index, uri ->
                        corrections.getOrNull(index)?.takeIf { it.isNotBlank() }?.let {
                            owner.correctedTexts[uri] = it
                        } ?: owner.correctedTexts.remove(uri)
                    }
                    owner.attachments = owner.attachments + batch
                }
            }.also { it.startCropFlow(imported.map { file -> file.absolutePath }, grade = false) }
            accepted = true
            return true
        } finally {
            owner.importingImages = false
            if (!accepted) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                files?.forEach { it.delete() }
            }
        }
    }
    private fun ocrCorrectionText(corrections: List<String?>): String {
        val strings = com.zsz.studyassistant.ui.stringsFor(uiLang)
        return corrections.mapIndexedNotNull { index, text -> text?.takeIf { it.isNotBlank() }?.let {
            strings.format("ocr.imageText", "n" to (index + 1).toString()) + "\n" + it
        } }.joinToString("\n\n")
    }

    internal suspend fun importDocument(owner: com.zsz.studyassistant.ui.SolveInputDraft, uri: android.net.Uri): Boolean {
        if (solveInputDraft !== owner || !owner.editable || busy || dataBusy || owner.importingDocuments || owner.documents.size >= 20) return false
        owner.importingDocuments = true
        return try {
            val document = withContext(Dispatchers.IO) { documentStore.importUri(uri) }
            if (solveInputDraft !== owner || busy || dataBusy) false else {
                if (document.name.substringAfterLast('.').lowercase() == "pdf" ||
                    com.zsz.studyassistant.data.LocalDocumentText.supports(document.name)) {
                    owner.documentSelection = document
                } else owner.documents = (owner.documents + document).distinctBy { it.sha256 }
                true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (solveInputDraft === owner) error = com.zsz.studyassistant.ui.stringsFor(uiLang)[
                (failure as? com.zsz.studyassistant.data.DocumentProblem)?.messageKey ?: "document.importFailed"]
            false
        } finally { owner.importingDocuments = false }
    }

    private var captureIdentity: Pair<QuestionId, Long>?
        get() = captureFlow.captureIdentity
        set(value) { captureFlow.captureIdentity = value }
    val cropPaths get() = captureFlow.cropPaths
    val cropIndex get() = captureFlow.cropIndex
    val cropGrade get() = captureFlow.cropGrade
    val cropExpect get() = captureFlow.cropExpect
    val cropNeedsMore get() = captureFlow.cropNeedsMore
    val captureRecoveryNoticeKey get() = captureFlow.captureRecoveryNoticeKey
    val currentCropPath get() = captureFlow.currentCropPath
    internal val cropRevision get() = captureFlow.cropRevision
    val cropHasNext get() = captureFlow.cropHasNext
    val cropTotal get() = captureFlow.cropTotal
    val cropPos get() = captureFlow.cropPos
    fun consumeCaptureRecoveryNotice() = captureFlow.consumeCaptureRecoveryNotice()
    suspend fun restoreCaptureFlowIfNeeded() = captureFlow.restoreCaptureFlowIfNeeded()
    internal suspend fun awaitCaptureCheckpoint() = captureFlow.awaitCaptureCheckpoint()
    internal fun cropSelection(path: String) = captureFlow.cropSelection(path)
    internal fun detectCrop(path: String, bounds: androidx.compose.ui.geometry.Rect, useAi: Boolean,
                            timeout: Long, manual: Boolean = false) = captureFlow.detectCrop(path, bounds, useAi, timeout, manual)
    fun startCropFlow(paths: List<String>, grade: Boolean, expect: Int = paths.size) = captureFlow.startCropFlow(paths, grade, expect)
    fun appendCropPath(path: String) = captureFlow.appendCropPath(path)
    fun submitCropResult(bytes: ByteArray) = captureFlow.submitCropResult(bytes)
    fun skipRemainingCrop(decode: (String) -> ByteArray?): Boolean = captureFlow.skipRemainingCrop(decode)
    fun cropGoBackOne() = captureFlow.cropGoBackOne()
    fun cancelCropFlow() = captureFlow.cancelCropFlow()

    // History is created at initial input, before requesting AI. Both tables share
    // the permanent problem ID. Subsequent calls may only update an existing row.
    // Notebook/review loads explicitly disable history writes.
    private fun conversationSnapshot(): Lazy<String> {
        val chats = chatItems.toList()
        return lazy { json.encodeToString(chats) }
    }

    private fun recordSolveSession(source: String, create: Boolean = false,
                                   conversation: Lazy<String> = conversationSnapshot()) {
        historySnapshot(source, conversation)?.let { persistence.queueHistory(it, create) }
    }

    private fun historySnapshot(source: String, conversation: Lazy<String>): SaveSnapshot<SolveSession>? {
        val binding = sessionRecordBinding
        if (!binding.historyAllowed) return null
        val problemId = binding.id ?: return null
        val fallback = when {
            multiImages.isNotEmpty() -> multiImages.toList()
            directImages.isNotEmpty() -> directImages.toList()
            else -> listOfNotNull(imageBytes)
        }
        val keys = questionImages.toList()
        val q = questionText.ifBlank {
            originalDocumentTitle().ifBlank { if (keys.isEmpty() && fallback.isEmpty()) return null else "（图片题）" }
        }
        val created = binding.createdAt
        val updated = System.currentTimeMillis()
        val origin = binding.source ?: source
        val answer = lastAnswer()
        val notebookId = savedQuestionId
        val snapshot = SaveSnapshot(problemId) {
            SolveSession(id = problemId, createdAt = created, updatedAt = updated, source = origin,
                text = q, answer = answer, conversationJson = conversation.value,
                imageBytes = keys.firstOrNull()?.let { Base64.decode(it, Base64.NO_WRAP) } ?: fallback.firstOrNull(),
                imagesJson = when {
                    keys.size > 1 -> json.encodeToString(keys)
                    keys.isEmpty() && fallback.size > 1 -> json.encodeToString(fallback.map { Base64.encodeToString(it, Base64.NO_WRAP) })
                    else -> null
                }, savedQuestionId = notebookId)
        }
        return snapshot
    }

    fun retryFailedHistoryWrites() = persistence.retryFailedHistoryWrites { cleanupPersistedDrafts() }

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
    private val conversationLoader = com.zsz.studyassistant.data.ConversationLoader(dao)
    private val openRequests = com.zsz.studyassistant.data.ConversationOpenRequests()

    private fun applyPreparedConversation(value: PreparedConversation, keepReview: Boolean = false): Boolean {
        if (!resetSession()) return false
        sessionRecordBinding = SolveSessionBinding(value.id, value.recordCreatedAt, value.source, value.historyAllowed)
        bindInputDraft(value.id)
        exitRedo()
        reviewMode = keepReview
        chatItems = value.messages
        questionText = value.text
        isPhoto = value.photo
        askMode = value.ask
        gradeMode = value.grade
        questionFromPhoto = value.questionFromPhoto
        imageBytes = value.primaryImage
        questionImages = value.imageKeys
        directImages = value.directImages
        currentQuestionCreatedAt = value.createdAt
        savedQuestionId = value.notebook?.id
        savedToNotebook = value.notebook?.deleted == false
        isFromNotebook = value.notebook != null
        isDeleted = value.notebook?.deleted == true
        currentNotebookBaseline = value.notebookBaseline
        currentQuestionCategoryId = value.notebook?.categoryId
        currentQuestionTags = value.tagIds
        suggestedCategory = value.suggestedCategory
        suggestedTags = value.suggestedTags
        showLegacyImageWarn = value.legacyImages
        if (keepReview) currentReviewQuestion = value.notebook
        return true
    }

    suspend fun loadSolveSession(id: QuestionId): Boolean {
        val request = openRequests.begin()
        var seq = callSeq
        val binding = sessionRecordBinding
        fun current() = openRequests.isCurrent(request) && seq == callSeq && binding === sessionRecordBinding
        if (!canOpenSavedQuestion(id, ::current) || !current()) return false
        stopActiveGeneration()
        seq = callSeq
        try {
            persistence.awaitWrites()
            if (!current()) return false
            val (summary, linked, history) = AppDatabase.get(getApplication()).withWriteTransaction<Triple<SolveSessionPreview, Question?, SolveSession?>?> {
                val question = if (dao.questionPreviewOnce(id)?.deleted == false) dao.heavyOnce(id) else null
                val summary = dao.sessionLightOnce(id) ?: return@withWriteTransaction null
                Triple(summary, question, if (question == null) dao.solveSessionOnce(id) else null)
            } ?: run {
                if (current()) error = com.zsz.studyassistant.ui.stringsFor(uiLang)["history.readFailed"]
                return false
            }
            if (!current()) return false
            if (linked != null) return loadQuestion(linked, historySession = summary, ownsOpen = ::current, openRequest = request)
            val s = checkNotNull(history)
            // 对应错题已删除或不存在：记录仍可查看，不再回写失效的错题 id。
            val loaded = conversationLoader.readConversation(s.text, s.answer, s.conversationJson, s.imageBytes, s.source == "grade",
                historyImages = s.imagesJson)
            if (!current()) return false
            if (id in deletionState.value.historyIds) return false
            val prepared = PreparedConversation(s.id, s.createdAt, s.source, true, s.text,
                loaded.messages, loaded.imageKeys, loaded.direct, s.imageBytes,
                photo = s.source == "camera", ask = s.source == "ask", grade = loaded.grading,
                questionFromPhoto = s.source == "camera" && loaded.imageKeys.isNotEmpty())
            stopActiveGeneration()
            return applyPreparedConversation(prepared)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (current()) error = com.zsz.studyassistant.ui.stringsFor(uiLang)["history.readFailed"]
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
    ): Boolean {
        val capture = if (photo && !ask && !grade) captureIdentity else null
        if (!resetSession()) return false
        val firstTime = capture?.second ?: System.currentTimeMillis()
        sessionRecordBinding = SolveSessionBinding(
            id = capture?.first ?: QuestionIdentity.create(firstTime), createdAt = firstTime,
            historyAllowed = !fromNotebook
        )
        bindInputDraft(checkNotNull(sessionRecordBinding.id))
        if (capture != null) captureIdentity = null
        currentQuestionCreatedAt = sessionRecordBinding.createdAt
        isPhoto = photo
        gradeMode = grade
        askMode = ask
        isFromNotebook = fromNotebook
        questionFromPhoto = photo
        return true
    }

    /** 主页「图文提问」入口：开一个空会话并标记为图文提问（标题保持"图文提问"） */
    fun startAskSession() {
        stopActiveGeneration()   // 新会话前先停掉可能在跑的上一轮
        // ★ 2026-10-13 修复（用户报「图文提问不提问直接回退，又回到拍照界面」）：
        //   原来走 `startNewQuestion()`，它**不复位 isPhoto** → 上一轮拍过题时 isPhoto 仍是 true →
        //   SolveScreen 的返回按钮判定 `!vm.isPhotoSession` 为假 → 走"回拍题界面"分支。
        //   改用 prepareSession：它是 startNewQuestion 的超集，且显式 `isPhoto = false`。
        if (prepareSession(ask = true)) bindInputDraft(com.zsz.studyassistant.data.InputDraftStore.NEW_ASK)
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
        { q, categories, tags, images, category, tag ->
            StudyAssistant.classifyQuestion(q, categories, tags, images, category, tag, onUsage = {
                com.zsz.studyassistant.data.UsageStats.add(getApplication(), it)
            })
        }
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
        val encoded = if (gradeMode) questionImages else emptyList()
        if (q.isBlank() && imgs.isEmpty() && encoded.isEmpty()) {
            return
        }
        val owner = Any()
        val binding = sessionRecordBinding
        val categories = categoryNames.toList()
        val tags = tagNames.toList()
        val rawImages = if (encoded.isEmpty()) imgs.map { it.copyOf() } else emptyList()
        val request = classifyRequest
        classificationOwner = owner
        classifying = true
        classificationJob = viewModelScope.launch {
            try {
                val images = if (encoded.isEmpty()) rawImages else try {
                    withContext(Dispatchers.Default) { encoded.map { Base64.decode(it, Base64.NO_WRAP) } }
                } catch (_: IllegalArgumentException) {
                    return@launch // Malformed restored images must not crash an optional classification.
                }
                val (c, t) = request(q, categories, tags, images, needCategory, needTags)
                if (classificationOwner !== owner || sessionRecordBinding !== binding) return@launch
                // 已有分类（来自库/用户选择）优先，AI 只补空的
                if (!c.isNullOrBlank() && suggestedCategory == null) suggestedCategory = c
                if (t.isNotEmpty()) suggestedTags = t
                val firstIndex = chatItems.indexOfFirst { it.role == "assistant" }
                if (firstIndex >= 0) {
                    val first = chatItems[firstIndex]
                    val original = com.zsz.studyassistant.data.firstAnswerSuggestions(listOf(first))
                    val filled = original.copy(
                        category = original.category ?: c?.takeIf { it.isNotBlank() },
                        tags = original.tags.ifEmpty { t.toList() }
                    )
                    if (filled != original) {
                        chatItems = chatItems.toMutableList().apply {
                            this[firstIndex] = first.copy(notebookSuggestions = filled)
                        }
                        persistCompletedConversation()
                    }
                }
            } finally {
                refreshUsage()
                // ★ 必须放 finally：请求异常/被取消时也要复位，否则标志卡死 → 之后所有分类都被跳过
                if (classificationOwner === owner && sessionRecordBinding === binding) {
                    classificationOwner = null
                    classificationJob = null
                    classifying = false
                }
            }
        }
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
    var webEnabled by mutableStateOf(com.zsz.studyassistant.data.CapturePrefs.webEnabled(getApplication()))
        private set
    fun updateWebEnabled(value: Boolean) {
        if (busy || streamInterrupted) return
        webEnabled = value
        com.zsz.studyassistant.data.CapturePrefs.setWebEnabled(getApplication(), value)
    }
    var toolProgress by mutableStateOf<String?>(null)
        private set
    private var completedAiContext: com.zsz.studyassistant.data.AiResponseContext? = null
    val thinkingEnabled: Boolean get() = com.zsz.studyassistant.data.CapturePrefs.solveModel(getApplication()) != "flash"

    fun stopActiveGeneration() {
        cancelImageSubmission()
        classificationOwner = null
        classificationJob?.cancel()
        classificationJob = null
        classifying = false
        checkpointDraft()
        activeDraft=null
        val stopping = requestLifecycle.invalidate()
        busy = false
        streamingText = null
        thinkingText = null
        thinkingDone = false
        streamInterrupted = false
        networkError = false
        requestLifecycle.continuation = null
        toolProgress = null
        completedAiContext = null
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

    fun clearError() { error = null }
    fun showError(msg: String) { error = msg }
    fun hasApiKey(): Boolean = KeyManager.getApiKey().isNotBlank()

    /** 开始新一题：清空当前对话与状态 */
    fun startNewQuestion() {
        saveSessionOnExit()
        stopActiveGeneration()
        if (!resetSession()) return
    }

    /** 从当前 chatItems + 图片来源构建发给模型的对话历史（题目 + 问答） */
    /**
     * 用户自定义要求（设置 → AI 管理）：让 AI 遵守个人偏好（只给思路 / 步骤编号 / 更简洁 …）。
     *
     * ★ 2026-10-13 修复：原先这里叫 `customPromptMessage()` 并且**从来没有被任何地方调用**
     *   → 用户填的「自定义要求」实际上一次都没发给模型。现在改为取原文，由 [buildMessages] 拼进
     *   `languageSystemMessage(aiLang, customPrompt)`（合成一条 system 消息）。
     */
    private fun promptForSubject(categoryId: Long?): String = try {
        com.zsz.studyassistant.data.PromptRulesStore(getApplication()).compile(categories.value, categoryId)
    } catch (_: Exception) {
        throw com.zsz.studyassistant.data.DocumentProblem("prompt.readFailed")
    }

    private fun customPromptText(categoryId: Long? = currentQuestionCategoryId): String? = try {
        promptForSubject(categoryId)
    } catch (_: Exception) {
        // Do not silently discard or resurrect old instructions if the new settings cannot be read.
        error = com.zsz.studyassistant.ui.stringsFor(uiLang)["prompt.readFailed"]
        null
    }

    private var promptInventoryBinding: SolveSessionBinding? = null
    private var requestInventory: com.zsz.studyassistant.data.PromptInventory? = null
    private fun conversationInventory(): com.zsz.studyassistant.data.PromptInventory {
        val persisted = chatItems.firstOrNull()?.promptInventory
        if (promptInventoryBinding !== sessionRecordBinding || requestInventory == null) {
            promptInventoryBinding = sessionRecordBinding
            requestInventory = persisted ?: com.zsz.studyassistant.data.PromptInventory(categoryNames.toList(), tagNames.toList())
        }
        return requireNotNull(requestInventory).also { inventory ->
            if (chatItems.isNotEmpty() && persisted == null)
                chatItems = listOf(chatItems.first().copy(promptInventory = inventory)) + chatItems.drop(1)
        }
    }

    private fun buildMessages(items: List<ChatItem> = chatItems, prompt: String? = customPromptText()): (() -> List<DeepSeekMessage>)? {
        if (prompt == null) return null
        // Capture observable session state before moving CPU work off the UI thread.
        val multiImages = multiImages.toList()
        val directImages = directImages.toList()
        val questionImages = questionImages.toList()
        val imageBytes = imageBytes
        val gradeMode = gradeMode
        val isPhoto = isPhoto
        val questionText = questionText
        val aiLang = aiLang
        val inventory = conversationInventory()
        val categoryNames = inventory.categories
        val tagNames = inventory.tags
        val snapshotItems = items.toList()
        return prepare@{
            val items = snapshotItems
            val msgs = mutableListOf<DeepSeekMessage>()
            // 回答语言 + 用户自定义要求：统一在这里注入，拍题/直接提问/追问/重新生成都走这条路径
            msgs.add(StudyAssistant.languageSystemMessage(aiLang, prompt))
            if (multiImages.isNotEmpty()) {
                // 多张搜题：多图一起作为题目（同一套搜题指令）
                msgs.add(if (questionImages.size == multiImages.size) StudyAssistant.userMessageWithEncodedImages(
                    StudyAssistant.solvePrompt(categoryNames, tagNames), questionImages)
                else StudyAssistant.visionUserMessageMulti(multiImages, categoryNames, tagNames))
            } else if (gradeMode && questionImages.isNotEmpty()) {
                msgs.add(StudyAssistant.userMessageWithEncodedImages(
                    StudyAssistant.solvePrompt(categoryNames, tagNames) + "\n【我的题目/问题】" + questionText, questionImages))
            } else if (directImages.isNotEmpty()) {
                // 直接提问：文字 + 多图一起作为问题
                // ★ 带图的「直接提问」原来用追问提示词（不问分类/知识点）→ 改用搜题指令，保证与拍题一致
                val text = StudyAssistant.solvePrompt(categoryNames, tagNames) + "\n【我的题目/问题】" + questionText
                msgs.add(if (questionImages.size == directImages.size)
                    StudyAssistant.userMessageWithEncodedImages(text, questionImages)
                else StudyAssistant.userMessageWithImages(text, directImages))
            } else if (isPhoto && imageBytes != null) {
                msgs.add(if (questionImages.size == 1) StudyAssistant.userMessageWithEncodedImages(
                    StudyAssistant.solvePrompt(categoryNames, tagNames), questionImages)
                else StudyAssistant.visionUserMessage(imageBytes, categoryNames, tagNames))
            } else if (questionText.isNotBlank()) {
                msgs.add(StudyAssistant.textSolveMessage(questionText, categoryNames, tagNames))
            }
            val originalQuestion = items.firstOrNull { it.role == "question" }
            val originalDocuments = originalQuestion?.documents.orEmpty()
            if (originalDocuments.isNotEmpty()) {
                if (msgs.lastOrNull()?.role != "user") msgs.add(StudyAssistant.textSolveMessage(questionText, categoryNames, tagNames))
            }
            val excluded = items.lastOrNull { it.role == "user" }?.excludedDocumentHashes.orEmpty().toSet()
            if (msgs.lastOrNull()?.role == "user") msgs[msgs.lastIndex] = withQuestionAttachments(msgs.last(),
                originalQuestion?.copy(documents = originalDocuments.filterNot { it.sha256 in excluded }))
            msgs.addAll(chatMessages(items))
            msgs
        }
    }

    private fun withQuestionAttachments(message: DeepSeekMessage,
        question: ChatItem? = chatItems.firstOrNull { it.role == "question" }): DeepSeekMessage {
        val corrected = ocrCorrectionText(question?.ocrCorrections.orEmpty())
        val content = if (corrected.isBlank()) message.content else {
            val parts = (message.content as? kotlinx.serialization.json.JsonArray)?.toList()
                ?: listOf(kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                    put("text", message.content)
                })
            kotlinx.serialization.json.JsonArray(parts + kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                put("text", kotlinx.serialization.json.JsonPrimitive("以下是用户逐张校对后的文字，请以校对结果为准并结合原图：\n" + corrected))
            })
        }
        return message.copy(content = content, localDocuments = question?.documents.orEmpty())
    }

    private fun chatMessages(items: List<ChatItem>): List<DeepSeekMessage> {
        val msgs = mutableListOf<DeepSeekMessage>()
        val latestUserIndex = items.indexOfLast { it.role == "user" }
        val excluded = items.getOrNull(latestUserIndex)?.excludedDocumentHashes.orEmpty().toSet()
        for ((index, item) in items.withIndex()) {
            if (item.answerPending) continue
            when (item.role) {
                "assistant" -> {
                    msgs.addAll(item.aiContext?.continuationHistory.orEmpty())
                    msgs.add(DeepSeekMessage("assistant", JsonPrimitive(item.aiContext?.rawContent ?: item.content),
                        reasoningContent = if (item.aiContext != null) item.aiContext.reasoningContent else item.thinking,
                        serverToolCalls = item.aiContext?.serverToolCalls))
                }
                "user" -> {
                    val imgs = item.images
                    val documents = if (index == latestUserIndex) item.documents
                        else item.documents.filterNot { it.sha256 in excluded }
                    if (imgs.isNullOrEmpty()) {
                        msgs.add(DeepSeekMessage("user", JsonPrimitive(item.content), localDocuments = documents))
                    } else {
                        // 追问带有附图的 user 消息 → 视觉模型（文字 + 多图）
                        msgs.add(StudyAssistant.userMessageWithEncodedImages(item.content, imgs).copy(localDocuments = documents))
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
            e is com.zsz.studyassistant.data.DocumentProblem -> s[e.messageKey]
            e is com.zsz.studyassistant.data.AiRequestException && e.contextTooLong -> s["context.tooLong"]
            e is com.zsz.studyassistant.data.AiRequestException && e.code() in setOf(400, 422) ->
                s.format("err.invalidParameter", "code" to e.code().toString(), "field" to (e.parameter ?: "—"))
            msg.contains("timed out") || msg.contains("timeout") || msg.contains("socket") -> s["err.timeout"]
            msg.contains("failed to connect") || msg.contains("unreachable") || msg.contains("connect") -> s["err.connect"]
            msg.contains("api key") || msg.contains("unauthorized") || msg.contains("401") -> s["err.apikey"]
            msg.contains("返回为空") || msg.contains("returned nothing") -> s["err.emptyReply"]
            else -> e.message ?: fallback
        }
    }

    private fun addItem(role: String, content: String, images: List<String>? = null, thinking: String? = null,
                        quote: QuotedFollowUp? = null, gradePart: String? = null,
                        documents: List<com.zsz.studyassistant.data.ConversationDocument> = emptyList(),
                        excludedDocumentHashes: List<String> = emptyList()) {
        // 助手消息默认带上本轮思考（这样所有调用方都不用改，避免"正文一出思考就没了"）
        val th = thinking ?: if (role == "assistant") thinkingText?.takeIf { it.isNotBlank() } else null
        val suggestions = if (role == "assistant" && chatItems.none { it.role == "assistant" })
            com.zsz.studyassistant.data.NotebookSuggestions(suggestedCategory, suggestedTags.toList()) else null
        chatItems = chatItems + ChatItem(chatItems.size.toLong(), role, content, images, th, quote,
            gradePart = gradePart, notebookSuggestions = suggestions,
            aiContext = if (role == "assistant") completedAiContext else null, documents = documents,
            excludedDocumentHashes = excludedDocumentHashes)
    }

    fun notebookSaveSuggestions() = com.zsz.studyassistant.data.firstAnswerSuggestions(
        chatItems, com.zsz.studyassistant.data.NotebookSuggestions(suggestedCategory, suggestedTags)
    )

    private fun resetSession(preserveInput: Boolean = true): Boolean {
        if (preserveInput && !flushInputDraft()) return false
        cancelImageSubmission()
        inputDraftObserver?.cancel(); inputDraftRestore?.cancel()
        webEnabled = com.zsz.studyassistant.data.CapturePrefs.webEnabled(getApplication())
        attachmentCropFlow?.cancelCropFlow()
        attachmentCropFlow = null
        solveInputDraft = com.zsz.studyassistant.ui.SolveInputDraft()
        currentNotebookBaseline=null
        sessionRecordBinding = SolveSessionBinding()
        askMode = false
        sessionState.clearQuestion()
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
        return true
    }

    /**
     * 流式生成的中间文本：非 null 时界面把它当作「正在生成」的助手气泡实时渲染。
     * null = 当前没有流式（常规整段等待，或已结束）。
     */
    var streamingText by sessionState::streamingText
        private set

    /** 生成被中断（用户点「中止生成」或网络异常），已生成内容保留、可点「继续生成」 */
    var streamInterrupted by sessionState::streamInterrupted
        private set

    private var callJob by requestLifecycle::job
    private var streamOwner by requestLifecycle::owner

    // 「继续生成」所需的上文：原消息 + 已生成片段 + 完成回调

    /** 中止生成：立即关闭响应流（模型思考中也能立刻停）+ 取消协程；已生成内容保留 */
    fun abortGeneration() {
        // ★ 中止时：从已生成的文本里抢救「分类/知识点」，避免中止后存错题本时丢失预选
        harvestSuggestions(streamingText.orEmpty())
        requestLifecycle.cancel()
    }

    /**
     * 丢弃「已中止 / 思考中」的那条流式气泡（用户 2026-10-13 要求：中止后这条气泡要能选中删除）。
     *
     * 背景：中止后那条气泡**只存在于渲染层**（`streamingText` + `thinkingText`），它不在 `chatItems` 里，
     * 所以多选/删除（都按 `chatItems` 下标操作）覆盖不到它，长按还会被守卫拒绝。
     * 用户明确删掉它时调用这里：清掉中断态与「继续生成」的上下文 → 气泡消失。
     * 不把它固化进 `chatItems`：`continueGeneration()` 依赖 已保存的续写上下文 重放，
     * 固化后会重复出现两次（见 FIXES 记录里的取舍说明）。
     */
    fun discardInterrupted() {
        activeDraft?.let { value -> viewModelScope.launch { conversationDrafts.clear(value.id,value.generation); refreshDrafts() } }
        clearInterruptedState()
    }
    private fun clearInterruptedState() {
        activeDraft=null
        streamInterrupted = false
        streamingText = null
        thinkingText = null
        thinkingDone = false
        requestLifecycle.continuation = null
        toolProgress = null
        completedAiContext = null
    }

    /** 继续生成：把已生成部分作为 assistant 上文，要求模型接着往下写（不重复、不重开） */
    fun continueGeneration() {
        val next = requestLifecycle.continuation ?: return
        if (busy || dataBusy || !streamInterrupted) return
        val context = next.context
        streamCall(next.model, context.messagesForRequest(next.webEnabled), context.prefix, next.onDone,
            replyAction = next.action, useWeb = next.webEnabled, webHistory = context.history,
            webAnnotations = context.annotations, prepare = next.prepare,
            plainBase = context.messages.takeUnless { next.webEnabled || next.prepare != null },
            refreshIdleConnections = true)
    }

    private fun runCall(model: String, onDone: (String) -> Unit, prompt: String? = customPromptText()) {
        // 非批改的流式请求（解题/追问/重新生成/继续）：按钮显示「⏸ 中止生成」
        busyIsGrade = false
        val prepare = buildMessages(prompt = prompt)
        streamCall(model, emptyList(), prefix = "", onDone = onDone, prepare = prepare)
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
        replyAction: String = if(chatItems.lastOrNull()?.role=="user") "followup" else if(gradeMode) "grade" else "initial",
        useWeb: Boolean = webEnabled,
        webHistory: List<DeepSeekMessage> = emptyList(),
        webAnnotations: kotlinx.serialization.json.JsonArray? = null,
        requestTitle: Boolean = sessionRecordBinding.historyAllowed &&
            replyAction in setOf("initial", "grade") && prefix.isEmpty() &&
            chatItems.none { it.role == "assistant" || !it.conversationTitle.isNullOrBlank() },
        prepare: (() -> List<DeepSeekMessage>)? = null,
        plainBase: List<DeepSeekMessage>? = null,
        refreshIdleConnections: Boolean = false
    ) {
        if (dataBusy || (messages.isEmpty() && prepare == null)) return
        generation.start(com.zsz.studyassistant.data.ConversationGeneration.Request(
            model, messages, prefix, replyAction, useWeb, requestTitle, prepare, refreshIdleConnections
        )) { owner ->
            val titleBinding = sessionRecordBinding
            val draftId=sessionRecordBinding.id
            val draftTemplate=draftId?.let { com.zsz.studyassistant.data.ConversationDraft(it,sessionRecordBinding.createdAt,
                owner.id,model,replyAction,currentSource(),questionText,isPhoto,askMode,gradeMode,
                sessionRecordBinding.historyAllowed,savedQuestionId,questionImages,chatItems.toList(),messages,raw=prefix,redoFor=pendingRedoFor ?: if(gradeMode) redoQuestionId else null,
                gradeHistoryEnabled=gradeMode, webEnabled=useWeb, requestPrefix=if (useWeb) prefix else "",
                continuationHistory=webHistory, continuationAnnotations=webAnnotations,
                promptInventory=requestInventory.takeIf { promptInventoryBinding === sessionRecordBinding }) }
            val draftBaseline = currentNotebookBaseline
            draftTemplate?.let { conversationDrafts.reserve(it.id, it.generation) }
            activeDraft = null
            object : com.zsz.studyassistant.data.ConversationGeneration.Events {
                private var template: com.zsz.studyassistant.data.ConversationDraft? = null
                override fun isCurrent() = titleBinding === sessionRecordBinding && requestLifecycle.owner === owner
                override fun started() {
                    busy = true
                    error = null
                    networkError = false
                    streamInterrupted = false
                    streamingText = prefix
                    thinkingText = null
                    thinkingDone = false
                    toolProgress = null
                    completedAiContext = null
                }
                override suspend fun prepared(messages: List<DeepSeekMessage>) {
                    template = draftTemplate?.copy(messages = plainBase ?: messages)
                    template?.let {
                        if (isCurrent()) activeDraft = it
                        writeDraft(it.copy(revision = ++draftRevision), draftBaseline)
                        refreshDrafts()
                    }
                }
                override fun body(text: String, thinking: String?, checkpoint: Boolean) {
                    thinking?.let { thinkingText = it }
                    streamingText = text
                    toolProgress = null
                    activeDraft = activeDraft?.copy(raw = text, thinking = thinkingText)
                    if (checkpoint) checkpointDraft(refresh = false)
                }
                override fun reasoning(text: String, checkpoint: Boolean) {
                    thinkingText = text
                    activeDraft = activeDraft?.copy(thinking = text)
                    if (checkpoint) checkpointDraft(refresh = false)
                }
                override fun toolProgress(key: String) { toolProgress = key }
                override suspend fun completed(body: String, thinking: String?) {
                    thinkingText = thinking ?: thinkingText
                    thinkingDone = true
                    completedAiContext = com.zsz.studyassistant.data.completedWebContext(owner.responseContext, webHistory, webAnnotations)
                    // Bind reasoning to the new reply through onDone, never the previous assistant message.
                    onDone(body)
                    if (isCurrent()) conversationInventory()
                    if(isCurrent()) {
                        // Initial photo/text callbacks create the question message here, so attach
                        // the label after onDone and before freezing the completed save/draft.
                        if (requestTitle && sessionRecordBinding === titleBinding)
                            chatItems = withConversationTitle(chatItems, owner.conversationTitle)
                        val completed=activeDraft?.copy(raw=body,thinking=thinkingText,complete=true,completedChats=chatItems.toList(),completedQuestion=questionText)
                        activeDraft=completed
                        val frozenSave = captureConversationSave()
                        val baseline = currentNotebookBaseline
                        // Register before suspending: a later edit must stay newer in both queues.
                        frozenSave?.let(persistence::enqueue)
                        completed?.let { writeDraft(it.copy(revision=++draftRevision), baseline) }
                        withContext(kotlinx.coroutines.NonCancellable) { persistence.awaitWrites() }
                        if(template!=null && completed!=null && draftCanBeCleared(completed)) {
                            finishDraftCleanup(completed)
                        }
                        refreshDrafts()
                    }
                    if (!isCurrent()) return
                    // ★ 分类兜底（C）：正文里没给出「分类：/知识点：」时，解答完成即后台补一次，
                    //   这样等用户去点「存错题本」时建议已经就绪（不必在对话框里干等）
                    val suggestions = notebookSaveSuggestions()
                    if (suggestions.category.isNullOrBlank() && questionText.isNotBlank()) {
                        classifyCurrentQuestion(needTags = suggestions.tags.isEmpty())
                    }
                    // 放在 onDone 之后：万一 onDone 内部抛异常，已生成的内容仍留在界面上（catch 会保留）
                    streamingText = null
                    requestLifecycle.continuation = null
                }
                override suspend fun interrupted(error: Exception, partial: String, thinking: String?,
                    messages: List<DeepSeekMessage>, aborted: Boolean, keepPartial: Boolean, networkFailure: Boolean) {
                    thinkingText = thinking ?: thinkingText
                    activeDraft=activeDraft?.copy(raw=partial,thinking=thinkingText,resumableContext=owner.resumableContext)
                    activeDraft?.let { writeDraft(it.copy(revision=++draftRevision),currentNotebookBaseline) }
                    withContext(kotlinx.coroutines.NonCancellable) { refreshDrafts() }
                    // Disk writes survive cancellation; the visible session may have changed meanwhile.
                    if (!isCurrent()) return
                    if (keepPartial) {
                        streamInterrupted = true
                        requestLifecycle.continuation = SolveRequestLifecycle.Continuation(
                            model, replyAction, useWeb,
                            continuationContext(plainBase ?: messages, partial, if (useWeb) prefix else "",
                                useWeb, webHistory, webAnnotations, owner.resumableContext),
                            prepare.takeIf { messages.isEmpty() }, onDone
                        )
                        // 空内容也保留中断气泡和继续生成入口。
                        streamingText = partial
                    } else {
                        streamingText = null
                    }
                    networkError = networkFailure
                    if (aborted) {
                        this@MainViewModel.error = null
                    } else {
                        this@MainViewModel.error = friendlyError(error, com.zsz.studyassistant.ui.stringsFor(uiLang)["err.requestFailed"])
                    }
                }
                override fun finished(keepPartial: Boolean) {
                    busy = false
                    toolProgress = null
                    completedAiContext = null
                    if (!keepPartial) streamingText = null
                }
            }
        }
    }

    override fun onCleared() {
        attachmentCropFlow?.cancelCropFlow()
        sharedImages.discard()
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
        sessionState.clear()
        super.onCleared()
    }

    // ---- API 用量统计（累计调用次数与 token）----
    var usageCalls by mutableStateOf(0)
        private set
    var usagePromptTokens by mutableStateOf(0)
        private set
    var usageCacheTokens by mutableStateOf(0)
        private set
    var usageSearches by mutableStateOf(0)
        private set
    var usageFetches by mutableStateOf(0)
        private set
    var usageImageSearches by mutableStateOf(0)
        private set
    var usageCompletionTokens by mutableStateOf(0)
        private set

    /** 每次接口调用后：写入统计并刷新界面状态 */
    private fun recordUsage(app: Application, usage: com.zsz.studyassistant.data.DeepSeekUsage?, calls: Int) {
        com.zsz.studyassistant.data.UsageStats.add(app, usage, calls)
        refreshUsage()
    }

    fun refreshUsage() {
        val s = com.zsz.studyassistant.data.UsageStats.load(getApplication())
        usageCalls = s.calls
        usagePromptTokens = s.promptTokens
        usageCompletionTokens = s.completionTokens
        usageCacheTokens = s.cacheTokens
        usageSearches = s.searches
        usageFetches = s.fetches
        usageImageSearches = s.imageSearches
    }

    fun resetUsage() {
        com.zsz.studyassistant.data.UsageStats.reset(getApplication())
        refreshUsage()
    }

    // ---- 数据管理：导出 / 导入 / 清空 ----
    var documentStorageSummary by mutableStateOf<com.zsz.studyassistant.data.DocumentStorageSummary?>(null)
        private set
    var documentStorageMessage by mutableStateOf<String?>(null)
        private set
    val canInspectDocumentStorage: Boolean
        get() = !(dataBusy || busy || reviewBusy || saving || saveOperations > 0 || retryingHistoryWrites ||
            activeDraft != null || solveInputDraft.restoring || solveInputDraft.importingImages || solveInputDraft.importingDocuments ||
            preparedBackup != null || preparedExport != null)

    /** Explicit maintenance only; protect all active references before inspecting private originals. */
    fun inspectDocumentStorage(clean: Boolean = false) {
        if (!canInspectDocumentStorage) return
        dataBusy = true
        documentStorageMessage = null
        val hashes = (chatItems.flatMap { it.documents } + solveInputDraft.documents +
            listOfNotNull(solveInputDraft.documentSelection)).map { it.sha256 }.toSet()
        viewModelScope.launch {
            try {
                val result = dataMaintenanceMutex.withLock {
                    com.zsz.studyassistant.data.BackupJobs.exclusive(getApplication()) {
                        solveSessionWriteMutex.withLock {
                            requireNoPendingSaves()
                            com.zsz.studyassistant.data.DocumentStorageMaintenance.inspect(
                                getApplication(), AppDatabase.get(getApplication()), hashes + inputDrafts.documentHashes(), clean)
                        }
                    }
                }
                documentStorageSummary = result
                if (clean) documentStorageMessage = com.zsz.studyassistant.ui.stringsFor(uiLang).format(
                    "document.cleaned", "n" to result.removedCount.toString(),
                    "size" to android.text.format.Formatter.formatFileSize(getApplication(), result.removedBytes))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                documentStorageMessage = com.zsz.studyassistant.ui.stringsFor(uiLang)["document.cleanupFailed"]
            } finally { dataBusy = false }
        }
    }

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
        AppDatabase.get(getApplication()).getSupportWrapper().version
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

    private val manualBackup = com.zsz.studyassistant.data.ManualBackupState(getApplication())
    var preparedExport by manualBackup::export
        private set
    var preparedBackup by manualBackup::preparedImport
        private set
    var dataProgressKey by manualBackup::progress
        private set
    private var backupReadJob by manualBackup::readJob
    private var backupExportJob by manualBackup::exportJob
    private var backupExportDirectory by manualBackup::exportDirectory
    private fun backupWorkspace() = manualBackup.workspace()
    private suspend fun cleanAbandonedBackupWorkspaces(current: java.io.File) = manualBackup.cleanAbandoned(current)
    private suspend fun removeBackupWorkspace(directory: java.io.File?) = manualBackup.remove(directory)

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

    fun cancelBackupOperation() = manualBackup.cancel()

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
                        val imported = com.zsz.studyassistant.data.BackupRepository(getApplication()).merge(file)
                        // Deletion already removed its pending snapshots. Only IDs actually restored
                        // by this successful import may accept newly captured content again.
                        for (id in deletedNotebookIds.toList()) {
                            if (dao.questionExists(id)) deletedNotebookIds.remove(id)
                        }
                        imported
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
    var openCrashReportPage by mutableStateOf(false)

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
                db.withWriteTransaction {
                    needed = needsImageUpgrade()
                    if (needed) {
                        repairQuestionImages()
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
        clearingInputDrafts = true
        viewModelScope.launch {
            try {
                val stoppingJob=callJob
                stopActiveGeneration()
                stoppingJob?.join()
                com.zsz.studyassistant.data.BackupJobs.exclusive(getApplication()) {
                solveSessionWriteMutex.withLock {
                    requireNoPendingSaves()
                    dailyReviewStore.clear {
                        com.zsz.studyassistant.data.BackupManager.clearAll(AppDatabase.get(getApplication()))
                    }
                    notebookPractice.clear()
                    conversationDrafts.clearAll()
                    inputDraftObserver?.cancel(); inputDraftRestore?.cancel()
                    inputDrafts.clearAll()
                    inputDraftFailures.clear()
                    historyWriteQueue.clear()
                    failedHistoryWrites = 0
                    activeDraft=null
                    resetSession(preserveInput = false)
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
                clearingInputDrafts = false
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

    private var imageSubmission by mutableStateOf<Job?>(null)
    internal val preparingInputImages: Boolean get() = imageSubmission != null

    private fun cancelImageSubmission() {
        imageSubmission?.cancel()
        imageSubmission = null
    }

    /** Admit one send. Keep the editable draft intact until its images are ready. */
    private fun withEncodedImages(images: List<ByteArray>, submit: (List<String>) -> Boolean): Boolean {
        if (preparingInputImages || busy || dataBusy) return false
        if (images.isEmpty()) return submit(emptyList())
        val binding = sessionRecordBinding
        val input = solveInputDraft
        val sequence = callSeq
        fun current() = sessionRecordBinding === binding && solveInputDraft === input && callSeq == sequence
        val sources = images.toList()
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val encoded = try {
                withContext(Dispatchers.Default) {
                    sources.map { bytes ->
                        ensureActive()
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
            } catch (_: OutOfMemoryError) {
                if (current()) error = com.zsz.studyassistant.ui.stringsFor(uiLang)["inputDraft.imageEncodingFailed"]
                return@launch
            } finally {
                if (imageSubmission === coroutineContext[Job]) imageSubmission = null
            }
            if (current() && !busy && !dataBusy) submit(encoded)
        }
        imageSubmission = job
        job.start()
        return true
    }

    fun solveWithImage(bytes: ByteArray) = solveWithImages(listOf(bytes))

    fun solveWithImages(images: List<ByteArray>) {
        if (images.isEmpty()) return
        withEncodedImages(images) { encoded ->
            if (!prepareSession(photo = true)) return@withEncodedImages false
            questionFromPhoto = true
            if (images.size == 1) imageBytes = images.single() else multiImages = images
            questionImages = encoded
            recordSolveSession("camera", create = true)
            runCall(StudyAssistant.MODEL_VISION, onDone = { output ->
                val result = StudyAssistant.parseVisionOutput(output)
                questionText = result.question
                suggestedCategory = result.category
                suggestedTags = result.tags
                addItem("question", result.question, encoded.takeIf { it.size > 1 })
                addItem("assistant", result.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
            })
            true
        }
    }

    /** 文字解答 */
    fun solveText(question: String) {
        if (!prepareSession()) return
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
        })
    }

    /** 直接提问：文字 + 可选多张图，作为一条问题解答 */
    fun solveDirect(text: String, images: List<ByteArray>, documents: List<com.zsz.studyassistant.data.ConversationDocument> = emptyList(),
                    fromCamera: Boolean = false): Boolean {
        if (!fromCamera && !solveInputDraft.editable) return false
        if (busy || dataBusy || (text.isBlank() && images.isEmpty() && documents.isEmpty())) return false
        if (documents.size > 20) { error = com.zsz.studyassistant.ui.stringsFor(uiLang)["document.tooMany"]; return false }
        val prompt = customPromptText(categoryId = null) ?: return false
        val acceptedInput = solveInputDraft.takeIf { !fromCamera &&
            it.storageKey == com.zsz.studyassistant.data.InputDraftStore.NEW_ASK }
        return withEncodedImages(images) { encoded ->
            if (!prepareSession(photo = images.isNotEmpty(), ask = !fromCamera)) return@withEncodedImages false
            questionFromPhoto = false
            questionText = text
            directImages = images
            questionImages = encoded
            addItem("question", text, encoded.ifEmpty { null }, documents = documents)
            recordSolveSession(if (fromCamera) "camera" else "ask", create = true)
            val model = if (images.isNotEmpty()) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
            runCall(model, onDone = { reply ->
                val result = StudyAssistant.parseVisionOutput(reply)
                suggestedCategory = result.category
                suggestedTags = result.tags
                addItem("assistant", result.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
            }, prompt = prompt)
            acceptedInput?.let(::acceptInputDraft)
            true
        }
    }

    /** 接续追问（可附带 1~3 张图，文字 + 图一起发给视觉模型） */
    fun sendFollowUp(text: String, images: List<ByteArray> = emptyList(), quotedText: String? = null,
                     quoteAnchor: QuoteAnchor? = null, documents: List<com.zsz.studyassistant.data.ConversationDocument> = emptyList(),
                     excludedDocumentHashes: Set<String> = emptySet()): Boolean {
        if (busy || dataBusy || !solveInputDraft.editable) return false
        if(streamInterrupted) { error=com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.resolveFirst"]; return false }
        if (text.isBlank() && images.isEmpty() && documents.isEmpty()) return false
        val historicalDocuments = chatItems.firstOrNull { it.role == "question" }?.documents.orEmpty() +
            chatItems.filter { it.role == "user" }.flatMap { it.documents }
        val excluded = excludedDocumentHashes.intersect(historicalDocuments.map { it.sha256 }.toSet())
        if (historicalDocuments.count { it.sha256 !in excluded } + documents.size > 20) {
            error = com.zsz.studyassistant.ui.stringsFor(uiLang)["document.tooMany"]; return false
        }
        val prompt = customPromptText() ?: return false
        val acceptedInput = solveInputDraft
        return withEncodedImages(images) { encoded ->
            // Store the complete quote in the existing message body, so retries, history,
            // notebook saves and backups all retain the same context without a format change.
            val content = quotedText?.takeIf { it.isNotBlank() }?.let { quote ->
                val strings = com.zsz.studyassistant.ui.stringsFor(uiLang)
                strings["solve.quoteTitle"] + ":\n" + quote + "\n\n" + strings["solve.quoteQuestion"] + ":\n" + text
            } ?: text
            addItem("user", content, encoded, quote = quotedText?.takeIf { it.isNotBlank() }?.let { QuotedFollowUp(it, text, quoteAnchor) },
                documents = documents, excludedDocumentHashes = excluded.sorted())
            val model = if (isPhoto || gradeMode || images.isNotEmpty()) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
            runCall(model, onDone = { reply ->
                // 追问也剔掉回显的元信息行（否则答案里会又冒出「题目：…」）
                addItem("assistant", StudyAssistant.stripMetaLines(reply))
            }, prompt = prompt)
            acceptInputDraft(acceptedInput)
            true
        }
    }

    /** 当前会话的来源标签（camera/ask/text/grade）——更新已有记录时沿用，避免来回改来源 */
    private fun currentSource(): String = when {
        gradeMode -> "grade"
        askMode -> "ask"
        isPhoto -> "camera"
        else -> "text"
    }

    /** 重新生成：**保留提问气泡**，只重做回答 */
    fun regenerate() {
        if (initialAnswerPending) { generateMissingInitialAnswer(); return }
        if(busy || streamInterrupted) {
            if(streamInterrupted) error=com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.resolveFirst"]
            return
        }
        val retained=com.zsz.studyassistant.data.regenerationBase(chatItems)
        val followup=retained.lastOrNull()?.role=="user"
        val action=if(followup) "regenerate_followup" else "regenerate_initial"
        val model=if(isPhoto || retained.lastOrNull()?.images?.isNotEmpty()==true) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        streamCall(model,emptyList(),"",prepare=buildMessages(retained),onDone={ output -> applyRecoveredReply(action,output) },
            replyAction=action)
    }

    val initialAnswerPending: Boolean get() = chatItems.any { it.answerPending }
    val restoringInitialAnswer: Boolean get() = streamingText != null && activeDraft?.action in setOf("restore_initial", "restore_grade")

    /** Refill the original slot; later follow-ups are neither sent as the question nor replaced. */
    fun generateMissingInitialAnswer() {
        if (busy || !initialAnswerPending) return
        if (streamInterrupted) { continueGeneration(); return }
        val input = gradingInput(gradeMode, questionText, chatItems, questionImages)
        if (input != null && !input.canGrade) {
            error = com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.conflict"]
            return
        }
        val action = if (input == null) "restore_initial" else "restore_grade"
        val model = if (input != null || questionImages.isNotEmpty() || isPhoto) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        val request = if (input == null && questionImages.isNotEmpty()) listOf(
            StudyAssistant.languageSystemMessage(aiLang, customPromptText() ?: return),
            withQuestionAttachments(StudyAssistant.userMessageWithEncodedImages(StudyAssistant.solvePrompt(conversationInventory().categories, conversationInventory().tags) + "\n" + questionText, questionImages)))
        else if (input == null) emptyList() else listOf(
            StudyAssistant.languageSystemMessage(aiLang, customPromptText() ?: return),
            withQuestionAttachments(StudyAssistant.gradeUserMessage(input, aiLang, conversationInventory().categories, conversationInventory().tags)))
        busyIsGrade = input != null
        streamCall(model, request, "", onDone = { applyRecoveredReply(action, it) },
            replyAction = action,
            prepare = if (input == null && questionImages.isEmpty()) buildMessages(chatItems.filter { it.role == "question" }) else null)
    }

    private fun lastAnswer(): String =
        chatItems.lastOrNull { it.role == "assistant" && !it.answerPending }?.content ?: ""

    /**
     * B9 查重用：**将要落库的题干**（与 [saveToNotebook] 完全一样的取法）。
     * ⚠️ 必须共用同一套取法：之前界面拿的是 `questionText`，而落库时可能在它为空的情况下
     * 回退到"AI 识别出的题干" —— 存的串和查的串不一致，就会永远查不出重复。
     */
    private fun originalDocumentTitle(): String = chatItems.firstOrNull { it.role == "question" }
        ?.documents.orEmpty().joinToString("、") { it.name }

    fun pendingQuestionText(): String = questionText.ifBlank {
        originalDocumentTitle().ifBlank {
            runCatching { StudyAssistant.parseVisionOutput(lastAnswer()).question }
                .getOrNull()?.trim().orEmpty().ifBlank {
                    if (questionImages.isNotEmpty() || imageBytes != null || directImages.isNotEmpty()) "（图片题）" else ""
                }
        }
    }

    /** 保存到错题本（带分类）。name 非空→新建分类；categoryId 为 null→暂不分类 */
    fun saveToNotebook(name: String?, categoryId: Long?, tagNames: List<String> = emptyList(), tagIds: List<Long> = emptyList()) {
        if(dataBusy) return
        if (saving) { cancelPending = true; return }
        if (savedQuestionId != null) return
        val q = pendingQuestionText().ifBlank { return }
        val binding = sessionRecordBinding
        val openRequest = openRequests.snapshot()
        val stableId = binding.id ?: return
        val firstCreatedAt = binding.createdAt
        val conversation = conversationSnapshot()
        val a = lastAnswer()
        val primaryImage = imageBytes
        val imageKeys = questionImages.take(MAX_QUESTION_IMAGES)
        val selectedTagIds = tagIds.toList()
        val selectedTagNames = tagNames.toList()
        val now = System.currentTimeMillis()
        saving = true
        cancelPending = false
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.Default) {
                    val decoded = imageKeys.map {
                        runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
                    }
                    val primary = primaryImage ?: decoded.firstOrNull()
                    val images = decoded.filterNotNull()
                    Triple(conversation.value, primary, images.ifEmpty { listOfNotNull(primary) })
                }
                val (convJson, img, allImgBytes) = prepared
                val db = AppDatabase.get(getApplication())
                var reusedQuestion: Question? = null
                val saved = notebookWriteMutex.withLock { db.withWriteTransaction {
                    if (binding.cancelPending || stableId in deletionState.value.questionIds) return@withWriteTransaction null
                    val existingQuestion = dao.heavyOnce(stableId)
                    if (existingQuestion != null) {
                        // Explicit save can restore this ID, but must not replace a newer notebook copy.
                        if (existingQuestion.deleted) dao.restore(stableId)
                        reusedQuestion = existingQuestion.copy(deleted = false)
                        dao.linkExistingSession(stableId)
                        return@withWriteTransaction Triple(stableId, existingQuestion.categoryId, dao.tagIdsForQuestion(stableId))
                    }
                    val cid = if (!name.isNullOrBlank()) {
                        dao.insertCategory(Category(name = name.trim()))
                    } else categoryId
                    val qid = dao.insertQuestionWithId(Question(id = stableId, text = q, answer = a, imageBytes = img,
                        conversationJson = convJson, categoryId = cid, createdAt = firstCreatedAt))
                    writeQuestionImages(qid, allImgBytes, source = "save")
                    val ids = selectedTagIds.distinct().toMutableList()
                    for (tn in selectedTagNames.take(5)) {
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
                }.also { if (it != null) deletedNotebookIds.remove(stableId) } }
                if (binding === sessionRecordBinding) {
                    val reused = reusedQuestion
                    if (reused != null) {
                        val history = if (binding.historyAllowed) dao.solveSessionOnce(stableId) else null
                        if (binding === sessionRecordBinding && openRequests.isCurrent(openRequest))
                            loadQuestion(reused, historySession = history?.toPreview(), openRequest = openRequest)
                        return@launch
                    }
                    savedQuestionId = saved?.first
                    savedToNotebook = saved != null
                    if (saved != null) isDeleted = false
                    currentQuestionCategoryId = saved?.second
                    currentQuestionTags = saved?.third.orEmpty()
                    if (saved != null) {
                        currentQuestionCreatedAt = firstCreatedAt
                        currentNotebookBaseline=com.zsz.studyassistant.data.SavePayload(stableId,firstCreatedAt,q,a,convJson)
                    }
                    cancelPending = false
                    recordSolveSession(currentSource())
                    if (saved != null) cleanupPersistedDrafts()
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
                AppDatabase.get(getApplication()).withWriteTransaction {
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

    private val dailyReviewStore = com.zsz.studyassistant.data.DailyReviewStore(app)
    internal val dailyReviewPlan: StateFlow<com.zsz.studyassistant.data.DailyReviewPlan?> = dailyReviewStore.state
    private suspend fun refreshReviewPlan(): com.zsz.studyassistant.data.DailyReviewPlan? = try {
        dailyReviewStore.refresh(dao)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        error = com.zsz.studyassistant.ui.stringsFor(uiLang)["review.loadFailed"]
        null
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun dueQuestions(week: Boolean, onlyWeak: Boolean): Flow<List<QuestionPreview>> =
        kotlinx.coroutines.flow.combine(calendarDay.flatMapLatest { day ->
            dao.dueQuestions(if (week) day.weekEnd else day.end, onlyWeak)
                .distinctUntilChanged().onEach { refreshReviewPlan() }
        }, deletionState, dailyReviewPlan) { rows, deletes, plan ->
            rows.filterNot { it.id in deletes.questionIds || it.id in plan?.rated.orEmpty() }
        }

    var reviewBusy by mutableStateOf(false)
        private set
    private var reviewEpoch = 0L
    private val committedReviewIds = mutableSetOf<QuestionId>()
    private suspend fun commitReview(questionId: QuestionId, level: Int) {
        dailyReviewStore.rate(dao, questionId) { day, alreadyRated ->
            AppDatabase.get(getApplication()).withWriteTransaction {
                val question = dao.questionPreviewOnce(questionId) ?: error("Question no longer exists")
                check(!question.deleted && questionId !in deletionState.value.questionIds)
                val before = dao.reviewFor(questionId) ?: Review(questionId).also { dao.insertReview(it) }
                if (!alreadyRated && before.lastReviewedAt !in day.start..day.end) {
                    val (step, days) = com.zsz.studyassistant.data.ReviewPlanning.schedule(level, before.intervalStep)
                    dao.updateReview(questionId, step, day.after(days), System.currentTimeMillis().coerceIn(day.start, day.end))
                }
            }
        }
    }

    /** 复习模式：点进错题查看；详情页底部为 熟悉/模糊/忘记 三按钮 */
    var reviewMode by mutableStateOf(false)
        private set
    internal fun currentReviewGradingInput() = reviewGradingInput(gradeMode, questionText, chatItems, questionImages)

    private var reviewQueue: List<QuestionPreview> = emptyList()
    private var currentReviewQuestion: Question? = null
    val reviewDone: Int get() = dailyReviewPlan.value?.completed ?: 0

    /** 点击的题和当前筛选只决定本轮切题顺序。 */
    fun startReviewSession(candidates: List<QuestionPreview>, index: Int) {
        reviewEpoch++
        committedReviewIds.clear()
        val ids = com.zsz.studyassistant.data.ReviewPlanning.ordered(candidates.map { it.id }, index)
        val byId = candidates.associateBy { it.id }
        reviewQueue = ids.mapNotNull { byId[it] }
        reviewBusy = false
    }

    fun loadForReview(q: QuestionPreview, onReady: () -> Unit = {}) {
        val request = openRequests.begin()
        stopActiveGeneration()
        val seq = callSeq
        val binding = sessionRecordBinding
        fun current() = openRequests.isCurrent(request) && seq == callSeq && binding === sessionRecordBinding
        viewModelScope.launch {
            try {
                if (!canOpenSavedQuestion(q.id, ::current)) return@launch
                val full = dao.heavyOnce(q.id) ?: return@launch
                if (!current()) return@launch
                redoMessage = null
                if (loadQuestion(full, keepReview = true, ownsOpen = ::current, openRequest = request)) onReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current()) {
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
        val request = openRequests.begin()
        reviewBusy = true
        val epoch = reviewEpoch
        val seq = callSeq
        val binding = sessionRecordBinding
        fun ownsSession() = openRequests.isCurrent(request) && epoch == reviewEpoch && seq == callSeq && binding === sessionRecordBinding
        viewModelScope.launch {
            try {
                if(id !in committedReviewIds) withContext(kotlinx.coroutines.NonCancellable) {
                    commitReview(id, level)
                    if(epoch==reviewEpoch) committedReviewIds.add(id)
                }
                if (ownsSession()) advanceReview(id, epoch, request, onNoMore)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if(ownsSession()) error = com.zsz.studyassistant.ui.stringsFor(uiLang)[
                    if(id in committedReviewIds) "review.loadFailed" else "review.saveFailed"]
            }
            finally { if (epoch == reviewEpoch) reviewBusy = false }
        }
    }
    private suspend fun advanceReview(answered: QuestionId?, epoch: Long, request: Long, onNoMore: () -> Unit) {
        val seq = callSeq
        val binding = sessionRecordBinding
        fun ownsSession() = openRequests.isCurrent(request) && epoch == reviewEpoch && seq == callSeq && binding === sessionRecordBinding
        if (!ownsSession()) return
        val remaining = reviewQueue.filter { it.id != answered }
        reviewQueue = remaining
        for (candidate in remaining) {
            if (!ownsSession()) return
            if (candidate.id in dailyReviewPlan.value?.rated.orEmpty()) continue
            if (!canOpenSavedQuestion(candidate.id, ::ownsSession)) return
            if (!ownsSession()) return
            val full = dao.heavyOnce(candidate.id)
            if (!ownsSession()) return
            if (full != null && !full.deleted && full.id !in deletionState.value.questionIds) {
                redoMessage = null
                if (loadQuestion(full, keepReview = true, ownsOpen = ::ownsSession, openRequest = request)) error=null
                return
            }
            reviewQueue = reviewQueue.filter { it.id != candidate.id }
        }
        redoMessage = null
        error=null
        onNoMore()
    }
    fun reviewAdvance(onNoMore: () -> Unit) {
        if (reviewBusy) return
        val request = openRequests.begin()
        reviewBusy = true
        val epoch = reviewEpoch
        val seq = callSeq
        val binding = sessionRecordBinding
        fun ownsSession() = openRequests.isCurrent(request) && epoch == reviewEpoch && seq == callSeq && binding === sessionRecordBinding
        viewModelScope.launch {
            try { if (ownsSession()) advanceReview(savedQuestionId ?: redoQuestionId, epoch, request, onNoMore) }
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
        currentReviewQuestion = null
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
    private var redoQuestionImages: List<String> = emptyList()
    private var redoSubmitting = false

    /** 本次批改是否属于"重做"（一次性）：批改结束时据此自动更新掌握度 */
    private var pendingRedoFor: QuestionId? = null

    /** 复习时点「✏️ 手写重做」：记住当前题，等用户拍/选作答照片 */
    fun startRedo() {
        val q = currentReviewQuestion ?: return
        redoQuestionId = q.id
        redoQuestionText = q.text
        redoQuestionImages = questionImages.toList()
        redoMessage = null
        redoMode = true
    }

    /** 提交手写作答照片 → 走批改链路（批改结束会自动更新掌握度） */
    fun submitRedoAnswer(answerBytes: ByteArray, onReady: () -> Unit, onFailure: (String) -> Unit) {
        if (redoSubmitting) return
        val qid = redoQuestionId ?: return
        val binding = sessionRecordBinding
        val text = redoQuestionText
        val fallback = redoQuestionImages
        val items = chatItems
        val wasGrading = gradeMode
        redoSubmitting = true
        viewModelScope.launch {
            try {
                val (originals, subjectId) = withContext(Dispatchers.IO) {
                    val stored = dao.imagesForQuestion(qid).sortedBy { it.position }
                        .map { Base64.encodeToString(it.bytes, Base64.NO_WRAP) }
                    val keys = mergeImageKeys(stored.firstOrNull(), stored.drop(1) + fallback)
                    // Notebook entries can outlive their history, but use the saved source if available.
                    val grading = wasGrading || dao.sessionLightOnce(qid)?.source == "grade"
                    (gradingInput(grading, text, items, keys)?.questionImages ?: keys) to
                        dao.questionPreviewOnce(qid)?.categoryId
                }
                if (binding !== sessionRecordBinding || redoQuestionId != qid || !redoMode) return@launch
                startGradeText(text, answerBytes, redoFor = qid, questionImageKeys = originals, categoryId = subjectId)
                onReady()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (binding === sessionRecordBinding && redoMode) onFailure(friendlyError(e,
                    com.zsz.studyassistant.ui.stringsFor(uiLang)["err.requestFailed"]))
            } finally { redoSubmitting = false }
        }
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
    /** 同类题的参考答案是否已展开。 */
    var similarRevealed by mutableStateOf(false)
        private set

    fun revealSimilarAnswer() { similarRevealed = true }

    /** 收起同类题答案（左下角椭圆按钮切换） */
    fun hideSimilarAnswer() { similarRevealed = false }

    /** 同类题流式中的文本：出题时 = 只含题目部分（答案不外显）；追问时 = 助手回复 */
    var similarStreamingText by mutableStateOf<String?>(null)
        private set

    val similarCanSave: Boolean get() = !similarQuestion.isNullOrBlank()

    /** 尚未获得完整参考答案，可按题干是否成型恢复或重新出题。 */
    var similarInterrupted by mutableStateOf(false)
        private set
    var similarInterruptionMessage by mutableStateOf<String?>(null)
        private set

    /** 同类题的出题/追问任务（用于「⏸ 中止生成」） */
    private var similarJob: Job? = null
    private var similarStreamOwner: com.zsz.studyassistant.data.StreamOwner? = null
    private var similarSeq = 0L
    private var similarSourceCategoryId: Long? = null
    private class SimilarProgress(val exercise: SimilarExerciseGeneration? = null, val raw: StringBuffer = StringBuffer())
    private var similarProgress: SimilarProgress? = null
    internal var similarQuestionRequest: suspend (Question, AiLang, Boolean, com.zsz.studyassistant.data.StreamOwner, suspend (String) -> Unit) -> StudyAssistant.SimilarResult =
        { question, lang, think, owner, delta -> StudyAssistant.generateSimilarQuestionStream(question, lang, owner, delta,
            customPrompt = promptForSubject(question.categoryId), thinkingEnabled = think) }
    internal var similarAnswerRequest: suspend (List<DeepSeekMessage>, Boolean, com.zsz.studyassistant.data.StreamOwner, suspend (String) -> Unit) -> String =
        { messages, think, owner, delta -> StudyAssistant.chatStream(StudyAssistant.MODEL_TEXT, messages,
            onDelta = delta, owner = owner, thinkingEnabled = think) }

    /** 只有追问往返可以删除，其显示索引从题目之后的 1 开始。 */
    fun deleteSimilarItems(indices: List<Int>) {
        if (similarBusy || indices.isEmpty()) return
        val kill = indices.filter { it in 1..similarMessages.size }.map { it - 1 }.toSet()
        if (kill.isNotEmpty()) {
            similarMessages = similarMessages.filterIndexed { i, _ -> i !in kill }
            persistSimilarConversation()
        }
    }

    fun startSimilar() {
        if (similarBusy) return
        val source = currentReviewQuestion ?: return
        similarCreatedAt = System.currentTimeMillis()
        similarIdentity = QuestionIdentity.create(similarCreatedAt)
        similarSourceCategoryId = source.categoryId
        similarQuestion = null
        similarAnswer = null
        similarMessages = emptyList()
        similarSavedQuestionId = null
        similarRevealed = false
        val request = similarQuestionRequest
        val lang = aiLang
        val think = thinkingEnabled
        generateSimilarExercise(SimilarExerciseGeneration()) { owner, delta -> request(source, lang, think, owner, delta) }
    }

    private fun showSimilarOutput(output: SimilarExerciseOutput) {
        similarQuestion = output.readyQuestion
        similarAnswer = output.answer?.takeIf { it.isNotBlank() }
        similarStreamingText = if (similarQuestion == null) output.questionText.takeIf { it.isNotBlank() } else null
    }

    private fun generateSimilarExercise(exercise: SimilarExerciseGeneration,
        request: suspend (com.zsz.studyassistant.data.StreamOwner, suspend (String) -> Unit) -> StudyAssistant.SimilarResult
    ) {
        val mySeq = ++similarSeq
        val owner = com.zsz.studyassistant.data.StreamOwner()
        similarStreamOwner = owner
        val progress = SimilarProgress(exercise)
        similarProgress = progress
        similarBusy = true
        similarInterrupted = false
        similarInterruptionMessage = null
        similarStreamingText = null
        similarJob = viewModelScope.launch {
            var answerCompleted = false
            try {
                com.zsz.studyassistant.data.AnswerForegroundService.start(getApplication(), owner.id)
                val result = withContext(Dispatchers.IO) {
                    var nextEmit = 0L
                    var questionReady = exercise.question != null
                    request(owner) { delta ->
                        progress.raw.append(delta)
                        val now = SystemClock.elapsedRealtime()
                        // Publish the first complete question even if its delimiter crosses a throttled chunk.
                        val headingPossible = !questionReady && (':' in delta || '：' in delta)
                        if (now >= nextEmit || headingPossible) {
                            nextEmit = now + 180
                            val output = exercise.output(progress.raw.toString())
                            questionReady = output.readyQuestion != null
                            withContext(Dispatchers.Main) {
                                if (mySeq == similarSeq) showSimilarOutput(output)
                            }
                        }
                    }
                }
                if (mySeq == similarSeq) {
                    thinkingDone = true
                    similarQuestion = result.question
                    similarAnswer = result.answer
                    similarStreamingText = null
                    persistSimilarConversation()
                    answerCompleted = true
                }
            } catch (e: Exception) {
                if (mySeq == similarSeq) {
                    showSimilarOutput(exercise.output(progress.raw.toString()))
                    similarInterrupted = true
                    if (e !is CancellationException) similarInterruptionMessage = friendlyError(e, "")
                    persistSimilarConversation()
                }
                if (e is CancellationException) throw e
            } finally {
                finishSimilarRequest(mySeq, owner, answerCompleted && currentCoroutineContext().isActive)
            }
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
        val exercise = progress.exercise
        if (exercise != null) {
            showSimilarOutput(exercise.output(partial))
            similarInterrupted = true
        } else {
            if (similarStreamingText == null) return // 附图仍在准备，尚未提交追问。
            // 追问阶段被中止：把已生成片段保留成正式消息，避免白等
            if (!partial.isNullOrBlank()) {
                similarMessages = similarMessages + ChatItem(similarMessages.size.toLong(), "assistant", partial)
            }
            similarStreamingText = null
        }
        persistSimilarConversation()
    }

    fun continueSimilar() {
        if (similarBusy || !similarInterrupted) return
        val question = similarQuestion ?: return startSimilar()
        val prefix = similarAnswer.orEmpty()
        val exercise = SimilarExerciseGeneration(question, prefix)
        val request = similarAnswerRequest
        val lang = aiLang
        val category = similarSourceCategoryId
        val think = thinkingEnabled
        generateSimilarExercise(exercise) { owner, delta ->
            val messages = exercise.continuationMessages(StudyAssistant.systemMessage(lang, promptForSubject(category)))
            val suffix = request(messages, think, owner, delta)
            if (suffix.isBlank()) throw com.zsz.studyassistant.data.StreamIncompleteException("err.streamInvalid")
            StudyAssistant.SimilarResult(question, prefix + suffix)
        }
    }

    internal var similarChatRequest: suspend (String, List<DeepSeekMessage>, com.zsz.studyassistant.data.StreamOwner, suspend (String) -> Unit) -> String =
        { model, messages, owner, onDelta -> StudyAssistant.chatStream(model, messages, documentStore = documentStore, owner = owner, onDelta = onDelta,
            task = "followup", thinkingEnabled = thinkingEnabled) }

    fun sendSimilar(
        text: String,
        images: List<ByteArray> = emptyList(),
        onAccepted: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ): Boolean {
        if (similarBusy) return false
        val qTitle = similarQuestion ?: return false
        val txt = text.trim()
        if (txt.isEmpty() && images.isEmpty()) return false
        val prompt = customPromptText(similarSourceCategoryId) ?: run {
            error?.let(onFailure)
            return false
        }
        val encodingError = com.zsz.studyassistant.ui.stringsFor(uiLang)["inputDraft.imageEncodingFailed"]
        // The caller still owns its mutable bytes while the dispatcher prepares the request.
        val sources = try { images.map { it.copyOf() } } catch (_: OutOfMemoryError) {
            onFailure(encodingError)
            return false
        }
        val previous = similarMessages.map { it.copy(images = it.images?.toList()) }
        val answer = similarAnswer
        val system = StudyAssistant.systemMessage(aiLang, prompt)
        val mySeq = ++similarSeq
        similarStreamOwner?.cancel()
        similarJob?.cancel()
        val owner = com.zsz.studyassistant.data.StreamOwner()
        similarStreamOwner = owner
        similarBusy = true
        val baseCount = previous.size + 1
        val request = similarChatRequest
        val progress = SimilarProgress()
        similarProgress = progress
        similarJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val sb = progress.raw
            var lastEmit = 0L
            var answerCompleted = false
            try {
                val (history, msgs) = try {
                    withContext(Dispatchers.Default) {
                        val encoded = sources.map { bytes ->
                            ensureActive()
                            Base64.encodeToString(bytes, Base64.NO_WRAP)
                        }
                        val history = previous + ChatItem(previous.size.toLong(), "user", txt.ifBlank { "[图片]" }, encoded)
                        val messages = buildList {
                            add(system)
                            add(DeepSeekMessage("user", JsonPrimitive("题目：$qTitle")))
                            answer?.takeIf { it.isNotBlank() }?.let { add(DeepSeekMessage("assistant", JsonPrimitive(it))) }
                            addAll(chatMessages(history))
                        }
                        history to messages
                    }
                } catch (_: OutOfMemoryError) {
                    if (mySeq == similarSeq) onFailure(encodingError)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (mySeq == similarSeq) onFailure(friendlyError(e, encodingError))
                    return@launch
                }
                if (mySeq != similarSeq) return@launch
                similarMessages = history
                similarStreamingText = ""
                onAccepted()
                com.zsz.studyassistant.data.AnswerForegroundService.start(app, owner.id)
                val reply = withContext(Dispatchers.IO) {
                    val model = if (history.any { !it.images.isNullOrEmpty() }) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
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
                    similarStreamingText = null
                    similarMessages = similarMessages + ChatItem(baseCount.toLong(), "assistant", reply)
                    persistSimilarConversation()
                    answerCompleted = true
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
            } finally {
                finishSimilarRequest(mySeq, owner, answerCompleted && currentCoroutineContext().isActive)
            }
        }
        return true
    }

    private fun finishSimilarRequest(sequence: Long, owner: com.zsz.studyassistant.data.StreamOwner, completed: Boolean) {
        val app = getApplication<Application>()
        val current = sequence == similarSeq
        try {
            if (owner.submittedCalls > 0) recordUsage(app, owner.usage, owner.submittedCalls)
        } finally {
            if (current) { similarBusy = false; similarProgress = null }
            com.zsz.studyassistant.data.AnswerForegroundService.stop(app, owner.id, completed = completed && current)
        }
    }

    // ---- 同类题：存错题本（逻辑与解题页一致：分类 + 标签，可取消保存）----
    internal var similarIdentity = QuestionIdentity.create()
        private set
    private var similarCreatedAt = System.currentTimeMillis()
    var similarSavedQuestionId by mutableStateOf<QuestionId?>(null)
        private set

    // Keep updates with their explicit save request until its insert commits. In particular,
    // a previously deleted ID must stay blocked to ordinary writes during recreation.
    private class SimilarSaveRequest {
        var latest: SaveSnapshot<com.zsz.studyassistant.data.SavePayload>? = null
        var invalidated = false
    }
    private val similarSaveRequests = mutableMapOf<QuestionId, SimilarSaveRequest>()

    private fun invalidateSimilarSaves(ids: Collection<QuestionId>) {
        ids.forEach { id -> similarSaveRequests[id]?.let { it.invalidated = true; it.latest = null } }
    }

    private fun captureSimilarNotebookSnapshot(): SaveSnapshot<com.zsz.studyassistant.data.SavePayload>? {
        val question = similarQuestion?.takeIf { similarCanSave } ?: return null
        return com.zsz.studyassistant.data.similarNotebookSnapshot(
            similarIdentity, similarCreatedAt, question, similarAnswer.orEmpty(), similarMessages, json
        )
    }
    private fun persistSimilarConversation() {
        val id = similarIdentity
        val request = similarSaveRequests[id]
        if (similarSavedQuestionId != id && request == null) return
        if(id in deletionState.value.questionIds) return
        val payload = captureSimilarNotebookSnapshot() ?: return
        if (request != null) {
            if (request.invalidated) return
            if (id in deletedNotebookIds) {
                request.latest = payload
                return
            }
        }
        queueNotebookSave(payload)
    }
    fun saveSimilarToNotebook(
        name: String?, categoryId: Long?, tagNames: List<String> = emptyList(), tagIds: List<Long> = emptyList()
    ) {
        if (dataBusy) return
        val id = similarIdentity
        if (id in deletionState.value.questionIds || id in similarSaveRequests) return
        val seq = similarSeq
        val snapshot = captureSimilarNotebookSnapshot() ?: return
        val selectedTagIds = tagIds.toList()
        val selectedTagNames = tagNames.toList()
        val request = SimilarSaveRequest()
        similarSaveRequests[id] = request
        viewModelScope.launch {
            try {
                val payload = snapshot.value()
                val saved = notebookWriteMutex.withLock {
                    if (request.invalidated || id in deletionState.value.questionIds) return@withLock false
                    AppDatabase.get(getApplication()).withWriteTransaction {
                        if (!dao.questionExists(id)) {
                            val cid = if (!name.isNullOrBlank()) dao.insertCategory(Category(name=name.trim())) else categoryId
                            dao.insertQuestionWithId(Question(id, payload.text, payload.answer, payload.createdAt,
                                categoryId = cid, conversationJson = payload.conversationJson))
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
                            dao.updateAnswerContent(id, payload.text, payload.answer, payload.conversationJson)
                        }
                    }
                    if (request.invalidated || id in deletionState.value.questionIds) return@withLock false
                    deletedNotebookIds.remove(id)
                    request.latest?.let(::queueNotebookSave)
                    true
                }
                if (saved && id == similarIdentity) similarSavedQuestionId = id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == similarSeq) error = com.zsz.studyassistant.ui.stringsFor(uiLang)
                    .format("data.failed", "msg" to tagWriteError(e))
            } finally {
                if (similarSaveRequests[id] === request) similarSaveRequests.remove(id)
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
    /** Question inputs cannot be deleted. Removing the initial reply retains an empty refill slot. */
    fun deleteMessages(indices: List<Int>) {
        if (busy || indices.isEmpty()) return
        chatItems = deleteConversationMessages(chatItems, indices.toSet())
    }

    /** One short-lived undo; never replace a different session or subsequent edits. */
    fun deleteMessagesWithUndo(indices: List<Int>, discardPartial: Boolean): (() -> Unit)? {
        if (busy || indices.any { it in protectedMessageIndices(chatItems) }) return null
        val before = chatItems
        val after = deleteConversationMessages(before, indices.toSet())
        val removePartial = discardPartial && streamInterrupted
        if (before == after && !removePartial) return null
        val owner = sessionRecordBinding
        val sequence = callSeq
        val draft = activeDraft
        val partial = streamingText
        val thinking = thinkingText
        val thoughtDone = thinkingDone
        val interrupted = streamInterrupted
        val continuation = requestLifecycle.continuation
        val aiContext = completedAiContext
        chatItems = after
        if (removePartial) clearInterruptedState()
        val expectedItems = chatItems
        var available = true
        val expiry = android.os.SystemClock.uptimeMillis() + 2_000
        // Keep the interrupted draft recoverable during undo; clear only its exact generation on expiry.
        val cleanup = viewModelScope.launch {
            kotlinx.coroutines.delay(2_000)
            available = false
            if (removePartial && draft != null) {
                conversationDrafts.clear(draft.id, draft.generation)
                refreshDrafts()
            }
        }
        return {
            if (available && android.os.SystemClock.uptimeMillis() < expiry && !busy &&
                owner === sessionRecordBinding && sequence == callSeq && chatItems === expectedItems &&
                (!removePartial || (activeDraft == null && streamingText == null))) {
                available = false; cleanup.cancel()
                chatItems = before
                if (removePartial) {
                    activeDraft = draft; streamingText = partial; thinkingText = thinking
                    thinkingDone = thoughtDone; streamInterrupted = interrupted
                    requestLifecycle.continuation = continuation
                    completedAiContext = aiContext
                }
            }
        }
    }

    fun addBookmark(text: String, anchor: QuoteAnchor?): String {
        if (busy || dataBusy || streamInterrupted) return "solve.quoteUnavailable"
        return when (val result = addConversationBookmark(chatItems, text, anchor)) {
            is BookmarkAddition.Rejected -> result.messageKey
            is BookmarkAddition.Added -> {
                chatItems = result.items
                saveSessionOnExit()
                "bookmark.saved"
            }
        }
    }

    fun removeBookmark(id: String) {
        if (busy || dataBusy || streamInterrupted) return
        chatItems = removeConversationBookmark(chatItems, id) ?: return
        saveSessionOnExit()
    }

    /**
     * 退出页面/应用或更新书签时，按现有会话绑定将快照交给保存队列。
     * 已存错题同步更新；搜题记录仍由 recordSolveSession 决定创建或更新。
     */
    fun saveSessionOnExit() { captureConversationSave()?.let(persistence::enqueue) }

    private fun captureConversationSave(): com.zsz.studyassistant.data.ConversationPersistence.FrozenSave? {
        if (chatItems.isEmpty()) return null
        val id = savedQuestionId
        val conversation = conversationSnapshot()
        val text = questionText
        val answer = lastAnswer()
        val created = currentQuestionCreatedAt
        val snapshot = id?.takeIf { QuestionIdentity.valid(it) && !isDeleted }?.let {
            SaveSnapshot(it) { com.zsz.studyassistant.data.SavePayload(it, created, text, answer, conversation.value) }
        }
        // Register both frozen destinations before returning; encoding happens under the existing queue.
        return com.zsz.studyassistant.data.ConversationPersistence.FrozenSave(
            historySnapshot(currentSource(), conversation), snapshot
        )
    }

    /** 从解题页软删除当前已存的这条错题（可恢复） */
    var isDeleted by mutableStateOf(false)
        private set
    fun deleteSavedQuestion() { savedQuestionId?.let { stageQuestionDeletion(listOf(it)) } }
    fun restoreSavedQuestion() { deletionState.value.pending?.let { undoDelete(it.token) } }
    fun deleteFromNotebook(q: QuestionPreview) { stageQuestionDeletion(listOf(q.id)) }

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
    fun startGrade(questionBytes: ByteArray, answerBytes: ByteArray?, redoFor: QuestionId? = null, correctedTexts: List<String?> = emptyList()) {
        if (!prepareSession(grade = true)) return
        pendingRedoFor = redoFor
        val str = com.zsz.studyassistant.ui.stringsFor(uiLang)
        val imgs = buildList {
            add(questionBytes)
            answerBytes?.let { add(it) }
        }
        questionFromPhoto = false
        questionText = if (answerBytes != null) {
            str["grade.label.question"] + " + " + str["grade.label.answer"]
        } else {
            str["grade.label.question"]
        }
        val encoded = imgs.map { Base64.encodeToString(it, Base64.NO_WRAP) }
        questionImages = encoded
        addItem("question", questionText, encoded)
        chatItems = chatItems.dropLast(1) + chatItems.last().copy(ocrCorrections = correctedTexts)
        recordSolveSession("grade", create = true)
        gradeCall()
    }

    /**
     * 手写重做：原题文字、可选题图和本次作答分别保留。
     */
    fun startGradeText(questionText: String, answerBytes: ByteArray, redoFor: QuestionId? = null,
                       questionImageKeys: List<String> = emptyList(), categoryId: Long? = null) {
        if (!prepareSession(grade = true)) return
        currentQuestionCategoryId = categoryId
        pendingRedoFor = redoFor
        val str = com.zsz.studyassistant.ui.stringsFor(uiLang)
        questionFromPhoto = false
        this.questionText = questionText
        val encoded = listOf(Base64.encodeToString(answerBytes, Base64.NO_WRAP))
        // Keep original question images separate from the student's answer. Each message
        // stays within the existing image limit, including a legacy three-image question.
        questionImages = questionImageKeys
        addItem("question", questionText, questionImageKeys, gradePart = "question")
        addItem("user", str["grade.label.answer"], encoded, gradePart = "answer")
        recordSolveSession("grade", create = true)
        gradeCall()
    }

    private fun restoreGradeContext(grading: Boolean) {
        gradeMode = gradingInput(grading, questionText, chatItems, questionImages) != null
    }

    /** 批改完成的公共收尾：解析分类/知识点 + 落一条助手消息 + 重做时给出待确认的掌握度建议 */
    private fun onGradeReply(reply: String) {
        // ★ 批改同样要解析分类/知识点（与拍题/直接提问一致），否则批改后存错题本无法预选
        val r = StudyAssistant.parseVisionOutput(reply)
        suggestedCategory = r.category
        suggestedTags = r.tags
        addItem("assistant", r.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
        // B6：重做的批改 → 按结论给出待确认的建议（一次性，取完即清）
        val redoFor = pendingRedoFor
        if (redoFor != null) {
            pendingRedoFor = null
            applyRedoResult(redoFor, reply)
        }
    }

    /** 批改请求（流式）。 */
    private fun gradeCall() {
        val input = gradingInput(gradeMode, questionText, chatItems, questionImages) ?: return
        if (!input.canGrade) {
            error = com.zsz.studyassistant.ui.stringsFor(uiLang)["draft.conflict"]
            return
        }
        val msgs = listOf(
            StudyAssistant.languageSystemMessage(aiLang, customPromptText() ?: return),
            withQuestionAttachments(StudyAssistant.gradeUserMessage(input, aiLang, conversationInventory().categories, conversationInventory().tags))
        )
        busyIsGrade = true
        streamCall(
            StudyAssistant.MODEL_VISION, msgs, prefix = "",
            onDone = { onGradeReply(it) },
            replyAction = "grade"
        )
    }

    /** 重新批改（用当前会话的题目/作答图再跑一次） */
    fun regrade() {
        if (busy || streamInterrupted) return
        gradeCall()
    }

    /** 当前这次流式请求是否为「批改」（决定按钮显示「⏸ 中止批改」还是「⏸ 中止生成」） */
    var busyIsGrade by mutableStateOf(false)
        private set

    /** 流式请求序号：用于识别"已被新请求取代的旧请求"，避免旧请求收尾时覆盖新请求的界面状态 */
    private var callSeq by requestLifecycle::sequence

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
        var seq = callSeq
        val request = openRequests.begin()
        fun current() = openRequests.isCurrent(request) && seq == callSeq
        viewModelScope.launch {
            try {
                if (!canOpenSavedQuestion(id, ::current) || !current()) return@launch
                stopActiveGeneration()
                seq = callSeq
                persistence.awaitWrites()
                if (!current()) return@launch
                val full = dao.heavyOnce(id) ?: return@launch
                if (!current() || full.deleted) return@launch
                if (loadQuestion(full, ownsOpen = ::current, openRequest = request)) onReady()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (current()) error = com.zsz.studyassistant.ui.stringsFor(uiLang)["err.requestFailed"] }
        }
    }

    /** Read a single practice card without opening a solve session or recording a review. */
    internal suspend fun readPracticeQuestion(id: QuestionId): List<com.zsz.studyassistant.ui.ChatMsg>? {
        // The undo window may still restore this item. Prune only after a committed deletion.
        deletionState.first { id !in it.questionIds }
        val row = dao.heavyOnce(id)?.takeUnless { it.deleted } ?: return null
        val grade = dao.sessionLightOnce(id)?.source == "grade"
        val loaded = conversationLoader.readConversation(row.text, row.answer, row.conversationJson, row.imageBytes, grade, id,
            decodeImages = false)
        val parts = reviewGradingInput(grade, row.text, loaded.messages, loaded.imageKeys)
        val images = parts?.questionImages ?: loaded.imageKeys
        // The row's answer may be the latest follow-up, while practice asks the original question.
        val solution = loaded.messages.firstOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content ?: row.answer
        return listOf(
            com.zsz.studyassistant.ui.ChatMsg("user", parts?.questionText ?: row.text, images),
            com.zsz.studyassistant.ui.ChatMsg("assistant", solution, listOfNotNull(parts?.answerImage))
        )
    }

    suspend fun loadQuestion(q: Question, keepReview: Boolean = false, historySession: SolveSessionPreview? = null,
                             ownsOpen: () -> Boolean = { true }, openRequest: Long = openRequests.begin()): Boolean {
        val seq = callSeq
        val previousBinding = sessionRecordBinding
        val epoch = reviewEpoch
        val historyGrade = historySession?.source == "grade" || keepReview && dao.sessionLightOnce(q.id)?.source == "grade"
        val loaded = conversationLoader.readConversation(q.text, q.answer, q.conversationJson, q.imageBytes,
            historyGrade, q.id, recognizeLegacyGrading = keepReview)
        val cats = dao.allCategoriesOnce()
        val allTags = dao.getAllTagsOnce()
        val tagIds = dao.tagIdsForQuestion(q.id)
        val baseline = withContext(Dispatchers.Default) { com.zsz.studyassistant.data.SavePayload.notebook(q) }
        if (!openRequests.isCurrent(openRequest) || seq != callSeq || previousBinding !== sessionRecordBinding || !ownsOpen() ||
            (keepReview && epoch != reviewEpoch) || q.id in deletionState.value.questionIds) return false
        val prepared = PreparedConversation(q.id,
            QuestionIdentity.earliest(q.createdAt, historySession?.createdAt ?: 0L), historySession?.source,
            historySession != null, q.text, loaded.messages, loaded.imageKeys, loaded.direct, q.imageBytes,
            photo = q.imageBytes != null, grade = loaded.grading, questionFromPhoto = q.imageBytes != null,
            notebook = q, notebookBaseline = baseline, createdAt = q.createdAt, tagIds = tagIds,
            suggestedCategory = cats.firstOrNull { it.id == q.categoryId }?.name,
            suggestedTags = tagIds.mapNotNull { id -> allTags.firstOrNull { it.id == id }?.name },
            legacyImages = loaded.legacyImages)
        if (keepReview && reviewMode) saveSessionOnExit()
        stopActiveGeneration()
        return applyPreparedConversation(prepared, keepReview)
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
