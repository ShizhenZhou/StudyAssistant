package com.zsz.studyassistant

/** Reject the entire delete if it includes a question; never silently delete a subset. */
internal fun deleteConversationMessages(items: List<ChatItem>, selected: Set<Int>): List<ChatItem> {
    if (selected.any { it in protectedMessageIndices(items) }) return items
    val initialAnswer = items.indexOfFirst { it.role == "assistant" }
    return items.mapIndexedNotNull { index, item ->
        when {
            index !in selected || item.answerPending -> item
            index == initialAnswer -> item.copy(content = "", images = null, thinking = null, quote = null,
                messageKey = java.util.UUID.randomUUID().toString(), answerPending = true, aiContext = null)
            else -> null
        }
    }
}

internal fun restoreInitialAnswer(items: List<ChatItem>, reply: ChatItem): List<ChatItem> {
    val slot = items.indexOfFirst { it.role == "assistant" && it.answerPending }
    if (slot < 0) return items
    return items.mapIndexed { index, item ->
        if (index == slot) reply.copy(id = item.id, messageKey = java.util.UUID.randomUUID().toString(), answerPending = false)
        else item
    }
}
