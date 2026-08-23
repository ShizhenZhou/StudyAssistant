package com.zsz.studyassistant

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zsz.studyassistant.data.AppDatabase
import com.zsz.studyassistant.data.Question
import com.zsz.studyassistant.data.StudyAssistant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).questionDao()

    /** 错题本数据流 */
    val notebook: StateFlow<List<Question>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 搜题/解答状态 ----
    var questionText by mutableStateOf("")
        private set
    var answer by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
        private set

    fun updateQuestionText(text: String) { questionText = text }
    fun clearError() { error = null }
    fun showError(msg: String) { error = msg }

    /** 拍照后：直接发送图片给 DeepSeek 视觉模型识别并解答 */
    fun solveWithImage(imageBytes: ByteArray) {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val result = StudyAssistant.solveWithImage(imageBytes)
                questionText = result.question
                answer = result.answer
            } catch (e: Exception) {
                error = e.message ?: "识别/解答失败"
            } finally {
                busy = false
            }
        }
    }

    /** 手动输入题目的 AI 解答（文本模型） */
    fun solve() {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                answer = StudyAssistant.solveText(questionText)
            } catch (e: Exception) {
                error = e.message ?: "解答失败"
            } finally {
                busy = false
            }
        }
    }

    /** 存入错题本 */
    fun saveToNotebook() {
        viewModelScope.launch {
            dao.insert(Question(text = questionText, answer = answer))
        }
    }

    /** 删除错题 */
    fun deleteFromNotebook(q: Question) {
        viewModelScope.launch { dao.delete(q) }
    }
}
