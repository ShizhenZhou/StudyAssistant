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

internal sealed interface BookmarkAddition {
    data class Added(val items: List<ChatItem>) : BookmarkAddition
    data class Rejected(val messageKey: String) : BookmarkAddition
}

/** Validates a selection and returns a new snapshot; the caller owns state and persistence. */
internal fun addConversationBookmark(
    items: List<ChatItem>,
    text: String,
    anchor: QuoteAnchor?
): BookmarkAddition {
    if (anchor == null || items.isEmpty()) return BookmarkAddition.Rejected("solve.quoteUnavailable")
    val bookmark = ConversationBookmark(java.util.UUID.randomUUID().toString(), text, anchor)
    if (text.length > MAX_BOOKMARK_TEXT) return BookmarkAddition.Rejected("bookmark.tooLong")
    if (!bookmark.isValid() || !bookmarkSourceAvailable(items, anchor)) {
        return BookmarkAddition.Rejected("solve.quoteUnavailable")
    }
    val existing = conversationBookmarks(items)
    if (existing.any { it.anchor == anchor && it.text == text }) return BookmarkAddition.Rejected("bookmark.exists")
    if (existing.size >= MAX_BOOKMARKS) return BookmarkAddition.Rejected("bookmark.limit")
    return BookmarkAddition.Added(withConversationBookmarks(items, existing + bookmark))
}

/** Returns a normalized snapshot even when the requested ID is absent; null only for an empty conversation. */
internal fun removeConversationBookmark(items: List<ChatItem>, id: String): List<ChatItem>? {
    if (items.isEmpty()) return null
    return withConversationBookmarks(items, conversationBookmarks(items).filterNot { it.id == id })
}

private fun withConversationBookmarks(items: List<ChatItem>, bookmarks: List<ConversationBookmark>): List<ChatItem> =
    listOf(items.first().copy(bookmarks = bookmarks)) + items.drop(1)

internal fun bookmarkSourceAvailable(items: List<ChatItem>, anchor: QuoteAnchor): Boolean {
    val source = items.singleOrNull { it.messageKey == anchor.messageKey && it.role == "assistant" } ?: return false
    val text = when (anchor.scope) { "body" -> source.content; "thinking" -> source.thinking; else -> null } ?: return false
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return hash == anchor.sourceHash
}
