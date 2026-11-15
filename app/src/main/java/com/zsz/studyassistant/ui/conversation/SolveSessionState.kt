package com.zsz.studyassistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One activity-scoped owner for the current conversation's presentation and in-memory images.
 * Generation jobs and durable saves remain owned by MainViewModel; this object performs no IO.
 */
internal class SolveSessionState {
    var imageBytes by mutableStateOf<ByteArray?>(null)
    private var messages by mutableStateOf<List<ChatItem>>(emptyList())
    var chatItems: List<ChatItem>
        get() = messages
        set(value) { messages = withStableMessageKeys(value) }
    var questionText = ""
    var directImages: List<ByteArray> = emptyList()
    var multiImages: List<ByteArray> = emptyList()
    var questionImages by mutableStateOf<List<String>>(emptyList())
    var questionFromPhoto by mutableStateOf(false)
    var thinkingText by mutableStateOf<String?>(null)
    var thinkingDone by mutableStateOf(false)
    var streamingText by mutableStateOf<String?>(null)
    var streamInterrupted by mutableStateOf(false)

    fun clearQuestion() {
        chatItems = emptyList()
        imageBytes = null
        questionText = ""
        directImages = emptyList()
        multiImages = emptyList()
        questionImages = emptyList()
        questionFromPhoto = false
    }

    fun clear() {
        clearQuestion()
        thinkingText = null
        thinkingDone = false
        streamingText = null
        streamInterrupted = false
    }
}
