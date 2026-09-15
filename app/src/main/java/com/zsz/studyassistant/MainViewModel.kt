package com.zsz.studyassistant

import android.app.Application
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zsz.studyassistant.data.AiLang
import com.zsz.studyassistant.data.AiLangStore
import com.zsz.studyassistant.data.AppDatabase
import com.zsz.studyassistant.data.Category
import com.zsz.studyassistant.data.DeepSeekMessage
import com.zsz.studyassistant.data.KeyManager
import com.zsz.studyassistant.data.Question
import com.zsz.studyassistant.data.QuestionTag
import com.zsz.studyassistant.data.Review
import com.zsz.studyassistant.data.StudyAssistant
import com.zsz.studyassistant.data.Tag
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** 聊天界面显示的一条消息（可序列化，用于保存对话会话） */
@Serializable
data class ChatItem(val id: Long, val role: String, val content: String, val images: List<String>? = null)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    init { KeyManager.init(app) }

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

    /** 错题本数据流 */
    val notebook: StateFlow<List<Question>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 用户自定义分类列表 */
    val categories: StateFlow<List<Category>> =
        dao.categories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 知识点标签列表 */
    val tags: StateFlow<List<Tag>> =
        dao.tags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 错题↔标签 关联（用于按 tag 筛选） */
    val questionTags: StateFlow<List<QuestionTag>> =
        dao.allQuestionTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 分类名 / 标签名（派生流，供 buildMessages 直接取值，避免每次请求都 map） */
    private val categoryNames: StateFlow<List<String>> =
        categories.map { list -> list.map { it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val tagNames: StateFlow<List<String>> =
        tags.map { list -> list.map { it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 对话流状态 ----
    var imageBytes by mutableStateOf<ByteArray?>(null)
    var chatItems by mutableStateOf<List<ChatItem>>(emptyList())
        private set
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
        private set
    var savedToNotebook by mutableStateOf(false)
        private set
    var savedQuestionId: Long? = null
        private set
    var isFromNotebook by mutableStateOf(false)
        private set
    var networkError by mutableStateOf(false)
        private set
    private var retryAction: (() -> Unit)? = null
    private var saving = false
    private var cancelPending = false
    private var isPhoto = false
    private var questionText = ""
    /** 直接提问携带的附图（文字 + 多图） */
    private var directImages: List<ByteArray> = emptyList()
    /** 拍照搜题（多张）携带的图，作为题目一起识别 */
    private var multiImages: List<ByteArray> = emptyList()

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

    /** 框选用：暂存拍照生成的图片文件路径 */
    var pendingImagePath by mutableStateOf<String?>(null)
        private set
    fun updatePendingImagePath(path: String) { pendingImagePath = path }

    fun clearError() { error = null }
    fun showError(msg: String) { error = msg }
    fun hasApiKey(): Boolean = KeyManager.getApiKey().isNotBlank()

    /** 开始新一题：清空当前对话与状态 */
    fun startNewQuestion() {
        chatItems = emptyList()
        questionText = ""
        imageBytes = null
        error = null
        savedToNotebook = false
        savedQuestionId = null
        isFromNotebook = false
        suggestedCategory = null
        suggestedTags = emptyList()
        currentQuestionCategoryId = null
        currentQuestionTags = emptyList()
        reviewMode = false
        directImages = emptyList()
        multiImages = emptyList()
        questionImages = emptyList()
        questionFromPhoto = false
    }

    /** 从当前 chatItems + 图片来源构建发给模型的对话历史（题目 + 问答） */
    private fun buildMessages(): List<DeepSeekMessage> {
        val msgs = mutableListOf<DeepSeekMessage>()
        // 回答语言：统一在这里注入，拍题/直接提问/追问/重新生成都走这条路径
        msgs.add(StudyAssistant.languageSystemMessage(aiLang))
        if (multiImages.isNotEmpty()) {
            // 多张搜题：多图一起作为题目（同一套搜题指令）
            msgs.add(StudyAssistant.visionUserMessageMulti(multiImages, categoryNames.value, tagNames.value))
        } else if (directImages.isNotEmpty()) {
            // 直接提问：文字 + 多图一起作为问题
            msgs.add(StudyAssistant.userMessageWithImages(questionText, directImages))
        } else if (isPhoto && imageBytes != null) {
            msgs.add(StudyAssistant.visionUserMessage(imageBytes!!, categoryNames.value, tagNames.value))
        } else if (questionText.isNotBlank()) {
            msgs.add(StudyAssistant.textUserMessage(questionText))
        }
        for (item in chatItems) {
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
            msg.contains("timed out") || msg.contains("timeout") || msg.contains("socket") -> s["err.timeout"]
            msg.contains("failed to connect") || msg.contains("unreachable") || msg.contains("connect") -> s["err.connect"]
            msg.contains("api key") || msg.contains("unauthorized") || msg.contains("401") -> s["err.apikey"]
            msg.contains("返回为空") || msg.contains("returned nothing") -> s["err.emptyReply"]
            else -> e.message ?: fallback
        }
    }

    private fun addItem(role: String, content: String, images: List<String>? = null) {
        chatItems = chatItems + ChatItem(chatItems.size.toLong(), role, content, images)
    }

    private fun resetSession() {
        chatItems = emptyList()
        imageBytes = null
        questionText = ""
        error = null
        savedToNotebook = false
        savedQuestionId = null
        suggestedCategory = null
        suggestedTags = emptyList()
        currentQuestionCategoryId = null
        currentQuestionTags = emptyList()
        reviewMode = false
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

    private fun runCall(model: String, onDone: (String) -> Unit, repeat: (() -> Unit)? = null) {
        val messages = buildMessages().toList()
        viewModelScope.launch {
            busy = true
            error = null
            networkError = false
            retryAction = null
            // 前台服务：切后台也不被冻结，保证请求跑完
            val app = getApplication<Application>()
            com.zsz.studyassistant.data.AnswerForegroundService.start(app)
            val sb = StringBuilder()
            var lastEmit = 0L
            var keepPartial = false
            streamingText = ""
            try {
                val reply = withContext(Dispatchers.IO) {
                    StudyAssistant.chatStream(model, messages) { delta ->
                        sb.append(delta)
                        // 节流：最多 ~180ms 刷一次界面，避免 WebView 被逐字重渲染拖垮
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 180) {
                            lastEmit = now
                            val snapshot = sb.toString()
                            withContext(Dispatchers.Main) { streamingText = snapshot }
                        }
                    }
                }
                recordUsage(app)
                onDone(reply)
                // 放在 onDone 之后：万一 onDone 内部抛异常，已生成的内容仍留在界面上（catch 会保留）
                streamingText = null
            } catch (e: Exception) {
                // 已经收到一部分就保留在界面上（不让用户白等一场），并给出「继续生成」入口
                keepPartial = sb.isNotBlank()
                if (!keepPartial) streamingText = null
                error = friendlyError(e, com.zsz.studyassistant.ui.stringsFor(uiLang)["err.requestFailed"])
                val msg = e.message.orEmpty().lowercase()
                if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("connect") ||
                    msg.contains("unreachable") || msg.contains("socket") || msg.contains("network")
                ) {
                    networkError = true
                    retryAction = repeat
                }
            } finally {
                busy = false
                if (!keepPartial) streamingText = null
                com.zsz.studyassistant.data.AnswerForegroundService.stop(app)
            }
        }
    }

    /** 网络断开后点击"继续生成"：用最后一次提问内容重新调用 */
    fun retry() {
        retryAction?.invoke()
    }

    // ---- API 用量统计（累计调用次数与 token）----
    var usageCalls by mutableStateOf(0)
        private set
    var usagePromptTokens by mutableStateOf(0)
        private set
    var usageCompletionTokens by mutableStateOf(0)
        private set

    /** 每次接口调用后：写入统计并刷新界面状态 */
    private fun recordUsage(app: Application) {
        com.zsz.studyassistant.data.UsageStats.add(app, StudyAssistant.lastUsage)
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
    var dataMessage by mutableStateOf<String?>(null)
        private set

    fun clearDataMessage() { dataMessage = null }

    /** 生成备份 JSON（IO 在后台线程）；ok 回调在拿到结果后触发 */
    fun exportBackup(onReady: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            dataBusy = true
            try {
                val json = com.zsz.studyassistant.data.BackupManager.export(dao)
                val n = dao.countQuestions()
                dataMessage = com.zsz.studyassistant.ui.stringsFor(uiLang).format("data.exportDone", "n" to "$n")
                onReady(json)
            } catch (e: Exception) {
                val m = com.zsz.studyassistant.ui.stringsFor(uiLang).format("data.failed", "msg" to (e.message ?: ""))
                dataMessage = m
                onError(m)
            } finally {
                dataBusy = false
            }
        }
    }

    fun importBackup(text: String) {
        viewModelScope.launch {
            dataBusy = true
            try {
                val r = com.zsz.studyassistant.data.BackupManager.import(dao, text)
                dataMessage = com.zsz.studyassistant.ui.stringsFor(uiLang).format(
                    "data.importDone",
                    "q" to "${r.questions}", "c" to "${r.categories}", "t" to "${r.tags}", "s" to "${r.skipped}"
                )
            } catch (e: Exception) {
                dataMessage = com.zsz.studyassistant.ui.stringsFor(uiLang).format("data.failed", "msg" to (e.message ?: ""))
            } finally {
                dataBusy = false
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            dataBusy = true
            try {
                com.zsz.studyassistant.data.BackupManager.clearAll(dao)
                dataMessage = com.zsz.studyassistant.ui.stringsFor(uiLang)["data.clearDone"]
            } catch (e: Exception) {
                dataMessage = com.zsz.studyassistant.ui.stringsFor(uiLang).format("data.failed", "msg" to (e.message ?: ""))
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
        resetSession()
        imageBytes = bytes
        isPhoto = true
        isFromNotebook = false
        questionFromPhoto = true
        questionImages = listOf(Base64.encodeToString(bytes, Base64.NO_WRAP))
        runCall(StudyAssistant.MODEL_VISION, onDone = { output ->
            val r = StudyAssistant.parseVisionOutput(output)
            questionText = r.question
            suggestedCategory = r.category
            suggestedTags = r.tags
            addItem("question", r.question)
            addItem("assistant", r.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
        }, repeat = { solveWithImage(bytes) })
    }

    /** 拍照解答（多张）：两张图作为同一道题目一起识别解答 */
    fun solveWithImages(images: List<ByteArray>) {
        resetSession()
        isPhoto = true
        isFromNotebook = false
        multiImages = images
        questionFromPhoto = true
        questionImages = images.map { Base64.encodeToString(it, Base64.NO_WRAP) }
        runCall(StudyAssistant.MODEL_VISION, onDone = { output ->
            val r = StudyAssistant.parseVisionOutput(output)
            questionText = r.question
            suggestedCategory = r.category
            suggestedTags = r.tags
            addItem("question", r.question)
            addItem("assistant", r.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
        }, repeat = { solveWithImages(images) })
    }

    /** 文字解答 */
    fun solveText(question: String) {
        resetSession()
        isPhoto = false
        isFromNotebook = false
        questionFromPhoto = false
        questionText = question
        suggestedCategory = null
        runCall(StudyAssistant.MODEL_TEXT, onDone = { reply ->
            addItem("question", question)
            addItem("assistant", reply)
        }, repeat = { solveText(question) })
    }

    /** 直接提问：文字 + 可选多张图，作为一条问题解答 */
    fun solveDirect(text: String, images: List<ByteArray>) {
        resetSession()
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
        val model = if (images.isNotEmpty()) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        runCall(model, onDone = { reply ->
            if (images.isNotEmpty()) {
                val r = StudyAssistant.parseVisionOutput(reply)
                suggestedCategory = r.category
                suggestedTags = r.tags
                addItem("assistant", r.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
            } else {
                addItem("assistant", reply)
            }
        }, repeat = { solveDirect(text, images) })
    }

    /** 接续追问（可附带 1~3 张图，文字 + 图一起发给视觉模型） */
    fun sendFollowUp(text: String, images: List<ByteArray> = emptyList()) {
        if (text.isBlank()) return
        val encoded = images.map { Base64.encodeToString(it, Base64.NO_WRAP) }
        addItem("user", text, encoded)
        val model = if (isPhoto || images.isNotEmpty()) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        runCall(model, onDone = { reply -> addItem("assistant", reply) }, repeat = { retryFollowUp(encoded) })
    }

    /** 网络失败后重新发送最后一次追问（不重复添加 user 消息，保留附图） */
    private fun retryFollowUp(images: List<String>? = null) {
        val model = if (isPhoto || !images.isNullOrEmpty()) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        runCall(model, onDone = { reply -> addItem("assistant", reply) }, repeat = { retryFollowUp(images) })
    }

    /** 重新生成 */
    fun regenerate() {
        chatItems = emptyList()
        val model = if (isPhoto) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        if (isPhoto) {
            runCall(model, onDone = { output ->
                val r = StudyAssistant.parseVisionOutput(output)
                questionText = r.question
                suggestedCategory = r.category
                suggestedTags = r.tags
                addItem("question", r.question)
                addItem("assistant", r.withHint(com.zsz.studyassistant.ui.stringsFor(uiLang)))
            }, repeat = { regenerate() })
        } else {
            runCall(model, onDone = { reply ->
                addItem("question", questionText)
                addItem("assistant", reply)
            }, repeat = { regenerate() })
        }
    }

    private fun lastAnswer(): String =
        chatItems.lastOrNull { it.role == "assistant" }?.content ?: ""

    /** 保存到错题本（带分类）。name 非空→新建分类；categoryId 为 null→暂不分类 */
    fun saveToNotebook(name: String?, categoryId: Long?, tagNames: List<String> = emptyList(), tagIds: List<Long> = emptyList()) {
        if (saving) {
            cancelPending = true
            return
        }
        if (savedQuestionId != null) return
        val q = questionText
        if (q.isBlank()) return
        val convJson = json.encodeToString(chatItems)
        val img = imageBytes
        val a = lastAnswer()
        saving = true
        cancelPending = false
        viewModelScope.launch {
            var cid = categoryId
            if (!name.isNullOrBlank()) {
                cid = dao.insertCategory(Category(name = name.trim()))
            }
            currentQuestionCategoryId = cid
            val now = System.currentTimeMillis()
            currentQuestionCreatedAt = now
            val qid = dao.insert(Question(text = q, answer = a, imageBytes = img, conversationJson = convJson, categoryId = cid, createdAt = now))
            // 关联知识点标签（合并已有 id + 新建名，最多 5 个）
            val finalIds = tagIds.toMutableList()
            for (tn in tagNames.take(5)) {
                val name2 = tn.trim()
                if (name2.isBlank()) continue
                val existing = dao.getAllTagsOnce().firstOrNull { it.name == name2 }
                val tid = existing?.id ?: dao.insertTag(Tag(name = name2))
                if (!finalIds.contains(tid)) finalIds += tid
            }
            for (tid in finalIds.take(5)) dao.insertQuestionTag(QuestionTag(qid, tid))
            currentQuestionTags = finalIds.take(5)
            // 新建复习调度（第 0 档，今天到期）
            dao.insertReview(Review(questionId = qid, intervalStep = 0, nextReviewAt = now, reviewCount = 0))
            if (cancelPending) {
                dao.deleteById(qid)
                savedQuestionId = null
                savedToNotebook = false
                cancelPending = false
            } else {
                savedQuestionId = qid
                savedToNotebook = true
            }
            saving = false
        }
    }

    /** 取消保存当前错题（从错题本移除） */
    fun unsaveFromNotebook() {
        val id = savedQuestionId ?: return
        savedQuestionId = null
        savedToNotebook = false
        currentQuestionCategoryId = null
        currentQuestionTags = emptyList()
        viewModelScope.launch { dao.deleteById(id) }
    }

    /** 修改当前题目的分类（详情页用）；name 非空→新建分类，categoryId 为 null→暂不分类 */
    fun changeCurrentCategory(name: String?, categoryId: Long?) {
        viewModelScope.launch {
            var cid = categoryId
            if (!name.isNullOrBlank()) {
                cid = dao.insertCategory(Category(name = name.trim()))
            }
            currentQuestionCategoryId = cid
            val id = savedQuestionId ?: return@launch
            val q = questionText
            val a = lastAnswer()
            val conv = json.encodeToString(chatItems)
            val img = imageBytes
            dao.update(Question(id = id, text = q, answer = a, imageBytes = img, conversationJson = conv, deleted = isDeleted, categoryId = cid, createdAt = currentQuestionCreatedAt.ifZeroToNow()))
        }
    }

    /** 批量改分类：name 非空→新建分类，categoryId 为 null→暂不分类 */
    fun batchSetCategory(ids: List<Long>, name: String?, categoryId: Long?) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var cid = categoryId
            if (!name.isNullOrBlank()) {
                cid = dao.insertCategory(Category(name = name.trim()))
            }
            dao.setCategoryForIds(ids, cid)
        }
    }

    /** 给当前题目加一个标签；name 非空→新建标签。返回是否成功 */
    fun addTagToCurrent(name: String?, tagId: Long?) {
        val id = savedQuestionId ?: return
        viewModelScope.launch {
            var tid = tagId
            if (!name.isNullOrBlank()) {
                val existing = dao.getAllTagsOnce().firstOrNull { it.name == name.trim() }
                tid = existing?.id ?: dao.insertTag(Tag(name = name.trim()))
            }
            if (tid == null) return@launch
            if (currentQuestionTags.contains(tid)) return@launch
            dao.insertQuestionTag(QuestionTag(id, tid))
            currentQuestionTags = currentQuestionTags + tid
        }
    }

    /** 移除当前题目的一个标签 */
    fun removeTagFromCurrent(tagId: Long) {
        val id = savedQuestionId ?: return
        viewModelScope.launch {
            dao.deleteQuestionTag(id, tagId)
            currentQuestionTags = currentQuestionTags - tagId
        }
    }

    /** 重置当前题目的标签（详情页批量改；tagIds + 新建 tagNames，≤5 个） */
    fun setCurrentTags(tagIds: List<Long>, newTagNames: List<String>) {
        val id = savedQuestionId ?: return
        viewModelScope.launch {
            dao.clearQuestionTags(id)
            val finalIds = tagIds.toMutableList()
            for (tn in newTagNames.take(5)) {
                val n = tn.trim()
                if (n.isBlank()) continue
                val ex = dao.getAllTagsOnce().firstOrNull { it.name == n }
                val tid = ex?.id ?: dao.insertTag(Tag(name = n))
                if (!finalIds.contains(tid)) finalIds += tid
            }
            for (tid in finalIds.take(5)) dao.insertQuestionTag(QuestionTag(id, tid))
            currentQuestionTags = finalIds.take(5)
        }
    }

    /** 批量彻底删除 */
    fun batchDelete(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch { dao.deleteByIds(ids) }
    }

    // ---- 复习（艾宾浩斯）----

    /** 今天到期的错题 */
    fun dueQuestionsToday(): Flow<List<Question>> = dao.dueQuestions(endOfToday())
    /** 本周（截止周日）到期的错题 */
    fun dueQuestionsWeek(): Flow<List<Question>> = dao.dueQuestions(endOfWeek())

    /** 三个按钮更新调度：level 0=忘记, 1=模糊, 2=熟悉 */
    fun reviewQuestion(questionId: Long, level: Int) {
        viewModelScope.launch {
            val r = dao.reviewFor(questionId) ?: Review(questionId)
            val base = startOfToday()
            val step = when (level) {
                2 -> (r.intervalStep + 1).coerceIn(0, INTERVAL_DAYS.size - 1) // 熟悉：+1 档
                1 -> r.intervalStep                                            // 模糊：保持档位
                else -> 0                                                      // 忘记：重置第 0 档
            }
            val next = base + INTERVAL_DAYS[step] * DAY_MS
            dao.updateReview(questionId, step, next, System.currentTimeMillis())
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
    fun startReviewSession(total: Int, index: Int) {
        reviewTotal = total
        reviewDone = index.coerceIn(0, maxOf(0, total - 1))
    }

    fun loadForReview(q: Question) {
        loadQuestion(q)
        reviewMode = true
        currentReviewQuestion = q
        viewModelScope.launch {
            reviewQueue = dao.dueQuestions(endOfToday()).first()
        }
    }

    /** 点三按钮后：更新调度并自动跳到下一题；无下一题时回调 onNoMore */
    fun reviewNext(level: Int, onNoMore: () -> Unit) {
        val qid = savedQuestionId ?: return
        reviewQuestion(qid, level)
        viewModelScope.launch {
            val due = dao.dueQuestions(endOfToday()).first()
            val cat = currentQuestionCategoryId
            val next = due.firstOrNull { it.categoryId == cat } ?: due.firstOrNull()
            if (next != null) {
                reviewQueue = due
                reviewDone += 1
                loadForReview(next)
            } else {
                reviewDone += 1
                onNoMore()
            }
        }
    }

    fun exitReviewMode() {
        reviewMode = false
        reviewTotal = 0
        reviewDone = 0
    }

    // ---------------------------------------------------------------------
    // 重做模式（接口预留，暂未接入界面）
    //
    // 目标闭环：复习时只给题目图 → 用拍照/相册提交自己重写的解答 →
    // 复用 gradeWithImages() 批改 → 用批改结果自动更新掌握度（代替人工点熟悉/模糊）。
    // 待办：
    //   1) UI：复习页加「✏️ 重做」入口与答案提交（拍照/相册）
    //   2) submitRedoAnswer() 内调用 StudyAssistant.gradeWithImages(imageBytes, answerBytes, aiLang)
    //   3) 依批改结果（正确/错误）映射到 reviewQuestion(level) 的档位
    // ---------------------------------------------------------------------

    /** 是否处于重做模式 */
    var redoMode by mutableStateOf(false)
        private set

    /** 进入重做模式（预留）。接入界面时由复习页调用。 */
    fun startRedo() {
        // TODO(重做模式)：初始化重做状态（清空已提交答案、记录当前题）
        redoMode = true
    }

    /** 提交重做答案（预留）。answerBytes = 手写作答照片。 */
    fun submitRedoAnswer(answerBytes: ByteArray) {
        // TODO(重做模式)：批改 + 依结果更新复习档位
    }

    fun exitRedo() {
        redoMode = false
    }

    // ---- 练同类题 ----
    var similarQuestion by mutableStateOf<String?>(null)
        private set
    var similarAnswer by mutableStateOf<String?>(null)
        private set
    var similarBusy by mutableStateOf(false)
        private set
    var similarMessages by mutableStateOf<List<ChatItem>>(emptyList())
        private set

    fun startSimilar() {
        val q = currentReviewQuestion ?: return
        viewModelScope.launch {
            similarBusy = true
            similarQuestion = null; similarAnswer = null; similarMessages = emptyList()
            val app = getApplication<Application>()
            com.zsz.studyassistant.data.AnswerForegroundService.start(app)
            try {
                val r = StudyAssistant.generateSimilarQuestion(q, aiLang)
                recordUsage(app)
                similarQuestion = r.question
                similarAnswer = r.answer
            } catch (e: Exception) {
                similarQuestion = com.zsz.studyassistant.ui.stringsFor(uiLang).format("err.similarFailed", "msg" to (e.message ?: ""))
                similarAnswer = null
            }
            similarBusy = false
            com.zsz.studyassistant.data.AnswerForegroundService.stop(app)
        }
    }

    fun sendSimilar(text: String) {
        val qTitle = similarQuestion ?: return
        val txt = text.trim()
        if (txt.isEmpty()) return
        similarMessages = similarMessages + ChatItem(similarMessages.size.toLong(), "user", txt)
        similarBusy = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            com.zsz.studyassistant.data.AnswerForegroundService.start(app)
            try {
                val msgs = mutableListOf<DeepSeekMessage>()
                msgs.add(StudyAssistant.systemMessage(aiLang))
                msgs.add(DeepSeekMessage("user", JsonPrimitive("题目：$qTitle")))
                for (m in similarMessages) msgs.add(DeepSeekMessage(if (m.role == "assistant") "assistant" else "user", JsonPrimitive(m.content)))
                msgs.add(DeepSeekMessage("user", JsonPrimitive(txt)))
                val reply = StudyAssistant.chatOnce(StudyAssistant.MODEL_TEXT, msgs)
                recordUsage(app)
                similarMessages = similarMessages + ChatItem(similarMessages.size.toLong(), "assistant", reply)
            } catch (e: Exception) {
                similarMessages = similarMessages + ChatItem(similarMessages.size.toLong(), "assistant", com.zsz.studyassistant.ui.stringsFor(uiLang).format("err.chatFailed", "msg" to (e.message ?: "")))
            }
            similarBusy = false
            com.zsz.studyassistant.data.AnswerForegroundService.stop(app)
        }
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    private fun endOfToday(): Long = startOfToday() + DAY_MS - 1
    private fun endOfWeek(): Long {
        val c = Calendar.getInstance()
        var add = Calendar.SUNDAY - c.get(Calendar.DAY_OF_WEEK)
        if (add < 0) add += 7
        c.add(Calendar.DAY_OF_MONTH, add)
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999)
        return c.timeInMillis
    }

    /** 删除追问消息（按 chatItems 索引）。删除后构建消息仅基于剩余，AI 接续对话 */
    fun deleteMessages(indices: List<Int>) {
        if (indices.isEmpty()) return
        val toRemove = indices.toSet()
        chatItems = chatItems.filterIndexed { i, _ -> i !in toRemove }
    }

    /** 退出页面/应用时：若已加入错题本，把当前完整对话更新进该条错题 */
    fun saveSessionOnExit() {
        val id = savedQuestionId ?: return
        if (id <= 0) return
        val q = questionText
        val a = lastAnswer()
        val conv = json.encodeToString(chatItems)
        val img = imageBytes
        viewModelScope.launch {
            dao.update(Question(id = id, text = q, answer = a, imageBytes = img, conversationJson = conv, deleted = isDeleted, categoryId = currentQuestionCategoryId, createdAt = currentQuestionCreatedAt.ifZeroToNow()))
        }
    }

    /** 从解题页软删除当前已存的这条错题（可恢复） */
    var isDeleted by mutableStateOf(false)
        private set
    fun deleteSavedQuestion() {
        val id = savedQuestionId ?: return
        isDeleted = true
        savedToNotebook = false
        viewModelScope.launch { dao.softDelete(id) }
    }

    /** 恢复已软删除的错题 */
    fun restoreSavedQuestion() {
        val id = savedQuestionId ?: return
        isDeleted = false
        savedToNotebook = true
        viewModelScope.launch { dao.restore(id) }
    }

    fun deleteFromNotebook(q: Question) {
        viewModelScope.launch { dao.delete(q) }
    }

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
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (deleteQuestions) {
                val qids = dao.questionIdsInCategories(ids)
                if (qids.isNotEmpty()) {
                    dao.deleteQuestionTagsForQuestions(qids)
                    dao.deleteReviewsForQuestions(qids)
                }
                dao.deleteQuestionsInCategories(ids)
            } else {
                dao.clearCategoryForCategories(ids)
            }
            dao.deleteCategories(ids)
        }
    }

    /** 批改题目：单张(题目)或两张(题目+手写答案) */
    var gradeResult by mutableStateOf("")
        private set
    var gradeBusy by mutableStateOf(false)
        private set

    fun grade(questionBytes: ByteArray, answerBytes: ByteArray?) {
        viewModelScope.launch {
            gradeBusy = true
            gradeResult = ""
            val app = getApplication<Application>()
            com.zsz.studyassistant.data.AnswerForegroundService.start(app)
            try {
                gradeResult = StudyAssistant.gradeWithImages(questionBytes, answerBytes, aiLang)
                recordUsage(app)
            } catch (e: Exception) {
                gradeResult = com.zsz.studyassistant.ui.stringsFor(uiLang).format("err.gradeFailed", "msg" to (e.message ?: ""))
            } finally {
                gradeBusy = false
                com.zsz.studyassistant.data.AnswerForegroundService.stop(app)
            }
        }
    }

    fun clearGradeResult() { gradeResult = "" }


    /** 加载某条错题的完整对话会话（用于续答） */
    fun loadQuestion(q: Question) {        chatItems = try {
            json.decodeFromString<List<ChatItem>>(q.conversationJson ?: "")
        } catch (e: Exception) {
            emptyList()
        }
        if (chatItems.isEmpty()) {
            chatItems = listOf(ChatItem(0, "question", q.text), ChatItem(1, "assistant", q.answer))
        }
        questionText = q.text
        imageBytes = q.imageBytes
        isPhoto = q.imageBytes != null
        savedToNotebook = true
        savedQuestionId = q.id
        isFromNotebook = true
        isDeleted = q.deleted
        currentQuestionCategoryId = q.categoryId
        currentQuestionCreatedAt = q.createdAt
        suggestedCategory = null
        currentQuestionTags = emptyList()
        error = null
        // 原题图统一作为浅绿用户气泡显示（旧会话也适用：从 imageBytes 注入）
        questionImages = q.imageBytes?.let { listOf(Base64.encodeToString(it, Base64.NO_WRAP)) } ?: emptyList()
        // 拍照题：气泡只显示原图，不再显示 AI 转译题干（旧会话同样按此处理）
        questionFromPhoto = q.imageBytes != null
        // 异步加载该题的知识点标签
        viewModelScope.launch {
            currentQuestionTags = dao.tagIdsForQuestion(q.id)
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
