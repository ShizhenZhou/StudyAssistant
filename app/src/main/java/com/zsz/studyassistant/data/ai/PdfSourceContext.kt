package com.zsz.studyassistant.data

import com.zsz.studyassistant.ChatItem

/** Resolve attachments at the selected message, never from a later follow-up. */
internal fun pdfDocumentsAtMessage(items: List<ChatItem>, messageKey: String): List<ConversationDocument> {
    val index = items.indexOfFirst { it.messageKey == messageKey }
    if (index < 0) return emptyList()
    val history = items.take(index + 1)
    val lastUser = history.indexOfLast { it.role == "user" }
    val excluded = history.getOrNull(lastUser)?.excludedDocumentHashes.orEmpty().toSet()
    return history.flatMapIndexed { position, item ->
        if (item.role != "question" && item.role != "user") emptyList()
        else item.documents.filter { doc ->
            doc.name.endsWith(".pdf", ignoreCase = true) && (position == lastUser || doc.sha256 !in excluded)
        }
    }.distinctBy { it.requestKey }
}
