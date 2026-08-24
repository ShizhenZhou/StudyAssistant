package com.zsz.studyassistant

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zsz.studyassistant.data.AppDatabase
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
data class ChatItem(val id: Long, val role: String, val content: String)

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
    private var saving = false
    private var cancelPending = false
    private var isPhoto = false
    private var questionText = ""

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
    }

    /** 从当前 chatItems + 图片来源构建发给模型的对话历史（题目 + 问答） */
    private fun buildMessages(): List<DeepSeekMessage> {
        val msgs = mutableListOf<DeepSeekMessage>()
        if (isPhoto && imageBytes != null) {
            msgs.add(StudyAssistant.visionUserMessage(imageBytes!!))
        } else if (questionText.isNotBlank()) {
            msgs.add(StudyAssistant.textUserMessage(questionText))
        }
        for (item in chatItems) {
            when (item.role) {
                "assistant" -> msgs.add(DeepSeekMessage("assistant", JsonPrimitive(item.content)))
                "user" -> msgs.add(DeepSeekMessage("user", JsonPrimitive(item.content)))
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

    private fun addItem(role: String, content: String) {
        chatItems = chatItems + ChatItem(chatItems.size.toLong(), role, content)
    }

    private fun resetSession() {
        chatItems = emptyList()
        imageBytes = null
        questionText = ""
        error = null
        savedToNotebook = false
        savedQuestionId = null
    }

    private fun runCall(model: String, onDone: (String) -> Unit) {
        val messages = buildMessages().toList()
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val reply = StudyAssistant.chatOnce(model, messages)
                onDone(reply)
            } catch (e: Exception) {
                error = friendlyError(e, "请求失败")
            } finally {
                busy = false
            }
        }
    }

    /** 拍照解答 */
    fun solveWithImage(bytes: ByteArray) {
        resetSession()
        imageBytes = bytes
        isPhoto = true
        runCall(StudyAssistant.MODEL_VISION) { output ->
            val r = StudyAssistant.parseVisionOutput(output)
            questionText = r.question
            addItem("question", r.question)
            addItem("assistant", r.answer)
        }
    }

    /** 文字解答 */
    fun solveText(question: String) {
        resetSession()
        isPhoto = false
        questionText = question
        runCall(StudyAssistant.MODEL_TEXT) { reply ->
            addItem("question", question)
            addItem("assistant", reply)
        }
    }

    /** 接续追问 */
    fun sendFollowUp(text: String) {
        if (text.isBlank()) return
        addItem("user", text)
        val model = if (isPhoto) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        runCall(model) { reply -> addItem("assistant", reply) }
    }

    /** 重新生成 */
    fun regenerate() {
        chatItems = emptyList()
        val model = if (isPhoto) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        if (isPhoto) {
            runCall(model) { output ->
                val r = StudyAssistant.parseVisionOutput(output)
                questionText = r.question
                addItem("question", r.question)
                addItem("assistant", r.answer)
            }
        } else {
            runCall(model) { reply ->
                addItem("question", questionText)
                addItem("assistant", reply)
            }
        }
    }

    private fun lastAnswer(): String =
        chatItems.lastOrNull { it.role == "assistant" }?.content ?: ""

    /** 加入/取消错题本（切换），保存完整对话会话；带去重保护 */
    fun toggleSaveNotebook() {
        if (saving) {
            // 正在保存中又点击 → 取消此次保存（避免重复）
            cancelPending = true
            return
        }
        if (savedQuestionId != null) {
            val id = savedQuestionId
            savedQuestionId = null
            savedToNotebook = false
            viewModelScope.launch { dao.deleteById(id!!) }
        } else {
            val q = questionText
            if (q.isBlank()) return
            val convJson = json.encodeToString(chatItems)
            val img = imageBytes
            val a = lastAnswer()
            saving = true
            cancelPending = false
            viewModelScope.launch {
                val id = dao.insert(Question(text = q, answer = a, imageBytes = img, conversationJson = convJson))
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
            dao.update(Question(id = id, text = q, answer = a, imageBytes = img, conversationJson = conv))
        }
    }

    /** 从解题页删除当前已存的这条错题（带确认提示由 UI 处理） */
    fun deleteSavedQuestion() {
        val id = savedQuestionId ?: return
        savedQuestionId = null
        savedToNotebook = false
        viewModelScope.launch { dao.deleteById(id) }
    }

    fun deleteFromNotebook(q: Question) {
        viewModelScope.launch { dao.delete(q) }
    }

    /** 加载某条错题的完整对话会话（用于续答） */
    fun loadQuestion(q: Question) {
        chatItems = try {
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
        error = null
    }
}
