package com.zsz.studyassistant

import kotlinx.serialization.Serializable

/** Local presentation metadata, stored on the protected first message. */
@Serializable
data class ConversationBookmark(val id: String, val text: String, val anchor: QuoteAnchor)

internal const val MAX_BOOKMARKS = 100
internal const val MAX_BOOKMARK_TEXT = 4000

internal fun ConversationBookmark.isValid(): Boolean = id.isNotBlank() && id.length <= 100 &&
    text.isNotBlank() && text.length <= MAX_BOOKMARK_TEXT && anchor.messageKey.isNotBlank() &&
    anchor.messageKey.length <= 100 && anchor.scope in setOf("body", "thinking") &&
    anchor.start >= 0 && anchor.end > anchor.start && anchor.sourceHash.matches(Regex("[0-9a-f]{64}"))

internal fun conversationBookmarks(items: List<ChatItem>): List<ConversationBookmark> =
    items.firstOrNull()?.bookmarks.orEmpty().filter { it.isValid() }.distinctBy { it.id }.take(MAX_BOOKMARKS)

internal fun bookmarkSourceAvailable(items: List<ChatItem>, anchor: QuoteAnchor): Boolean {
    val source = items.singleOrNull { it.messageKey == anchor.messageKey && it.role == "assistant" } ?: return false
    val text = when (anchor.scope) { "body" -> source.content; "thinking" -> source.thinking; else -> null } ?: return false
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return hash == anchor.sourceHash
}
