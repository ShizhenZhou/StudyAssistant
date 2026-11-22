package com.zsz.studyassistant.data

import com.zsz.studyassistant.ChatItem
import kotlinx.serialization.json.Json

/** Explicit and queued saves must index the same messages they serialize. */
internal fun similarNotebookSnapshot(
    id: QuestionId,
    createdAt: Long,
    question: String,
    referenceAnswer: String,
    followups: List<ChatItem>,
    json: Json
): SaveSnapshot<SavePayload> {
    val messages = buildList {
        add(ChatItem(0, "question", question))
        add(ChatItem(1, "assistant", referenceAnswer))
        followups.forEach { add(it.copy(id = size.toLong(), images = it.images?.toList())) }
    }
    return SaveSnapshot(id) {
        val searchable = buildString {
            append(referenceAnswer)
            for (index in 2 until messages.size) {
                val message = messages[index]
                if (isNotEmpty()) append("\n\n")
                append(if (message.role == "assistant") "AI：" else "我：")
                append(message.content)
            }
        }
        SavePayload(id, createdAt, question, searchable, json.encodeToString(messages))
    }
}
