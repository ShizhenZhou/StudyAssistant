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
import kotlinx.serialization.json.JsonPrimitive

/** 聊天界面显示的一条消息 */
data class ChatItem(val id: Long, val role: String, val content: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    init { KeyManager.init(app) }

    private val dao = AppDatabase.get(app).questionDao()

    /** 错题本数据流 */
    val notebook: StateFlow<List<Question>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 对话流状态 ----
    private val apiMessages = mutableListOf<DeepSeekMessage>()
    var imageBytes by mutableStateOf<ByteArray?>(null)   // 框选出的原图(用于解答页折叠显示)
    private var isPhoto = false
    private var questionText = ""
    private var nextId = 0L

    var chatItems by mutableStateOf<List<ChatItem>>(emptyList())
        private set
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
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
        apiMessages.clear()
        questionText = ""
        imageBytes = null
        error = null
        savedToNotebook = false
        savedQuestionId = null
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
        chatItems = chatItems + ChatItem(nextId++, role, content)
    }

    private fun runCall(model: String, messages: List<DeepSeekMessage>, onDone: (String) -> Unit) {
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

    /** 拍照解答：图片 -> 视觉模型识别并解答 */
    fun solveWithImage(bytes: ByteArray) {
        imageBytes = bytes
        isPhoto = true
        chatItems = emptyList()
        apiMessages.clear()
        val first = StudyAssistant.visionUserMessage(bytes)
        apiMessages.add(first)
        runCall(StudyAssistant.MODEL_VISION, apiMessages.toList()) { output ->
            val r = StudyAssistant.parseVisionOutput(output)
            questionText = r.question
            apiMessages.add(DeepSeekMessage("assistant", JsonPrimitive(r.answer)))
            addItem("question", r.question)
            addItem("assistant", r.answer)
        }
    }

    /** 文字解答 */
    fun solveText(question: String) {
        isPhoto = false
        chatItems = emptyList()
        apiMessages.clear()
        questionText = question
        apiMessages.add(StudyAssistant.systemMessage())
        apiMessages.add(StudyAssistant.textUserMessage(question))
        runCall(StudyAssistant.MODEL_TEXT, apiMessages.toList()) { reply ->
            apiMessages.add(DeepSeekMessage("assistant", JsonPrimitive(reply)))
            addItem("question", question)
            addItem("assistant", reply)
        }
    }

    /** 接续追问 */
    fun sendFollowUp(text: String) {
        if (text.isBlank()) return
        addItem("user", text)
        apiMessages.add(StudyAssistant.textUserMessage(text))
        val model = if (isPhoto) StudyAssistant.MODEL_VISION else StudyAssistant.MODEL_TEXT
        runCall(model, apiMessages.toList()) { reply ->
            apiMessages.add(DeepSeekMessage("assistant", JsonPrimitive(reply)))
            addItem("assistant", reply)
        }
    }

    /** 重新生成：重跑首个问题，刷新解答 */
    fun regenerate() {
        if (isPhoto && imageBytes != null) {
            chatItems = emptyList()
            apiMessages.clear()
            val first = StudyAssistant.visionUserMessage(imageBytes!!)
            apiMessages.add(first)
            runCall(StudyAssistant.MODEL_VISION, apiMessages.toList()) { output ->
                val r = StudyAssistant.parseVisionOutput(output)
                questionText = r.question
                apiMessages.add(DeepSeekMessage("assistant", JsonPrimitive(r.answer)))
                addItem("question", r.question)
                addItem("assistant", r.answer)
            }
        } else {
            chatItems = emptyList()
            apiMessages.clear()
            apiMessages.add(StudyAssistant.systemMessage())
            apiMessages.add(StudyAssistant.textUserMessage(questionText))
            runCall(StudyAssistant.MODEL_TEXT, apiMessages.toList()) { reply ->
                apiMessages.add(DeepSeekMessage("assistant", JsonPrimitive(reply)))
                addItem("question", questionText)
                addItem("assistant", reply)
            }
        }
    }

    /** 加入/取消错题本（切换）：已存则删除，未存则保存 */
    fun toggleSaveNotebook() {
        if (savedToNotebook) {
            savedQuestionId?.let { id -> viewModelScope.launch { dao.deleteById(id) } }
            savedToNotebook = false
            savedQuestionId = null
        } else {
            val items = chatItems
            val q = items.firstOrNull { it.role == "question" }?.content ?: questionText
            val a = items.lastOrNull { it.role == "assistant" }?.content ?: ""
            if (q.isBlank() || a.isBlank()) return
            viewModelScope.launch {
                val id = dao.insert(Question(text = q, answer = a, imageBytes = imageBytes))
                savedQuestionId = id
                savedToNotebook = true
            }
        }
    }

    fun deleteFromNotebook(q: Question) {
        viewModelScope.launch { dao.delete(q) }
    }

    /** 选中某条错题（用于跳转详情页） */
    fun viewQuestion(q: Question) { selectedQuestion = q }

    /** 当前是否已存入错题本 */
    var savedToNotebook by mutableStateOf(false)
        private set
    private var savedQuestionId: Long? = null
    var selectedQuestion by mutableStateOf<Question?>(null)
        private set
}
