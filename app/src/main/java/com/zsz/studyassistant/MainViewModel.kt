package com.zsz.studyassistant

import android.app.Application
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zsz.studyassistant.data.AppDatabase
import com.zsz.studyassistant.data.Category
import com.zsz.studyassistant.data.DeepSeekMessage
import com.zsz.studyassistant.data.KeyManager
import com.zsz.studyassistant.data.Question
import com.zsz.studyassistant.data.StudyAssistant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    /** 错题本数据流 */
    val notebook: StateFlow<List<Question>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 用户自定义分类列表 */
    val categories: StateFlow<List<Category>> =
        dao.categories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    /** AI 推测的科目（存题时作为默认分类建议，可改/可暂不分类） */
    var suggestedCategory by mutableStateOf<String?>(null)
        private set
    /** 当前题目所属分类 id（存题/加载/详情页改分类用；null = 暂不分类） */
    var currentQuestionCategoryId by mutableStateOf<Long?>(null)
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
        currentQuestionCategoryId = null
    }

    /** 从当前 chatItems + 图片来源构建发给模型的对话历史（题目 + 问答） */
    private fun buildMessages(): List<DeepSeekMessage> {
        val msgs = mutableListOf<DeepSeekMessage>()
        if (isPhoto && imageBytes != null) {
            msgs.add(StudyAssistant.visionUserMessage(imageBytes!!, categories.value.map { it.name }))
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
        val msg = e.message.orEmpty().lowercase()
        return when {
            msg.contains("timed out") || msg.contains("timeout") || msg.contains("socket") -> "请求超时了，请检查网络后重试"
            msg.contains("failed to connect") || msg.contains("unreachable") || msg.contains("connect") -> "无法连接到服务器，请检查网络"
            msg.contains("api key") || msg.contains("unauthorized") || msg.contains("401") -> "API Key 无效或未配置，请到⚙️设置检查"
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
        currentQuestionCategoryId = null
    }

    private fun runCall(model: String, onDone: (String) -> Unit, repeat: (() -> Unit)? = null) {
        val messages = buildMessages().toList()
        viewModelScope.launch {
            busy = true
            error = null
            networkError = false
            retryAction = null
            try {
                val reply = StudyAssistant.chatOnce(model, messages)
                onDone(reply)
            } catch (e: Exception) {
                error = friendlyError(e, "请求失败")
                val msg = e.message.orEmpty().lowercase()
                if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("connect") ||
                    msg.contains("unreachable") || msg.contains("socket") || msg.contains("network")) {
                    networkError = true
                    retryAction = repeat
                }
            } finally {
                busy = false
            }
        }
    }

    /** 网络断开后点击"继续生成"：用最后一次提问内容重新调用 */
    fun retry() {
        retryAction?.invoke()
    }

    /** 拍照解答 */
    fun solveWithImage(bytes: ByteArray) {
        resetSession()
        imageBytes = bytes
        isPhoto = true
        isFromNotebook = false
        runCall(StudyAssistant.MODEL_VISION, onDone = { output ->
            val r = StudyAssistant.parseVisionOutput(output)
            questionText = r.question
            suggestedCategory = r.category
            addItem("question", r.question)
            addItem("assistant", r.answer)
        }, repeat = { solveWithImage(bytes) })
    }

    /** 文字解答 */
    fun solveText(question: String) {
        resetSession()
        isPhoto = false
        isFromNotebook = false
        questionText = question
        suggestedCategory = null
        runCall(StudyAssistant.MODEL_TEXT, onDone = { reply ->
            addItem("question", question)
            addItem("assistant", reply)
        }, repeat = { solveText(question) })
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
                addItem("question", r.question)
                addItem("assistant", r.answer)
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
    fun saveToNotebook(name: String?, categoryId: Long?) {
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
            val id = dao.insert(Question(text = q, answer = a, imageBytes = img, conversationJson = convJson, categoryId = cid))
            if (cancelPending) {
                dao.deleteById(id)
                savedQuestionId = null
                savedToNotebook = false
                cancelPending = false
            } else {
                savedQuestionId = id
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
            dao.update(Question(id = id, text = q, answer = a, imageBytes = img, conversationJson = conv, deleted = isDeleted, categoryId = cid))
        }
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
            dao.update(Question(id = id, text = q, answer = a, imageBytes = img, conversationJson = conv, deleted = isDeleted, categoryId = currentQuestionCategoryId))
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

    /** 批改题目：单张(题目)或两张(题目+手写答案) */
    var gradeResult by mutableStateOf("")
        private set
    var gradeBusy by mutableStateOf(false)
        private set

    fun grade(questionBytes: ByteArray, answerBytes: ByteArray?) {
        viewModelScope.launch {
            gradeBusy = true
            gradeResult = ""
            try {
                gradeResult = StudyAssistant.gradeWithImages(questionBytes, answerBytes)
            } catch (e: Exception) {
                gradeResult = "批改失败：${e.message}"
            } finally {
                gradeBusy = false
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
        suggestedCategory = null
        error = null
    }
}
