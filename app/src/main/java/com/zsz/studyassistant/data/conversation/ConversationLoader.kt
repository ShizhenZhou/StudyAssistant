package com.zsz.studyassistant.data

import android.util.Base64
import com.zsz.studyassistant.ChatItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Reads legacy/current messages and images without changing the visible conversation. */
internal class ConversationLoader(private val dao: QuestionDao) {
    private val json = Json { ignoreUnknownKeys = true }
    data class LoadedConversation(
        val messages: List<ChatItem>,
        val imageKeys: List<String>,
        val direct: List<ByteArray>,
        val grading: Boolean,
        val legacyImages: Boolean
    )

    suspend fun readConversation(
        text: String, answer: String, conversation: String?, primary: ByteArray?, grading: Boolean,
        notebookId: QuestionId? = null, historyImages: String? = null, decodeImages: Boolean = true,
        recognizeLegacyGrading: Boolean = false
    ): LoadedConversation = withContext(Dispatchers.Default) {
        val parsed = runCatching { json.decodeFromString<List<ChatItem>>(conversation.orEmpty()) }.getOrNull()
        val messages = parsed.orEmpty().ifEmpty { listOf(ChatItem(0, "question", text), ChatItem(1, "assistant", answer)) }
        val embedded = parsed?.firstOrNull { it.role == "question" }?.images
            ?: if (parsed == null) questionImageBase64(conversation) else emptyList()
        val extras = historyImages?.let { json.decodeFromString<List<String>>(it) }.orEmpty()
        val primaryKey = primary?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        val fallback = mergeImageKeys(primaryKey, extras + embedded)
        val stored = notebookId?.let { dao.imagesForQuestion(it) }.orEmpty().associate {
            Base64.encodeToString(it.bytes, Base64.NO_WRAP) to it.bytes
        }
        val keys = mergeImageKeys(null, stored.keys.toList() + fallback)
        val isGrade = if (recognizeLegacyGrading) reviewGradingInput(grading, text, messages, keys) != null
            else gradingInput(grading, text, messages, keys) != null
        val bytes = if (isGrade || !decodeImages) emptyList() else keys.mapNotNull { key ->
            stored[key] ?: if (key == primaryKey) primary else if (notebookId == null) Base64.decode(key, Base64.NO_WRAP)
            else runCatching { Base64.decode(key, Base64.NO_WRAP) }.getOrNull()
        }
        LoadedConversation(messages, keys, bytes, isGrade,
            notebookId != null && stored.size < MAX_QUESTION_IMAGES && fallback.any { it !in stored })
    }

}
