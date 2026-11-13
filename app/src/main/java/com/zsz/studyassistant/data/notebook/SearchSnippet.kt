package com.zsz.studyassistant.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only this bounded text crosses from the database reader to the search UI. */
data class SearchSnippet(val text: String, val answer: Boolean, val highlights: List<IntRange>)

internal const val SEARCH_PROJECTION_CHARS = 2048

/** Projection includes one extra code point to detect truncation without scanning field lengths in SQL. */
internal suspend fun searchQuestionProjection(
    text: String,
    answer: String,
    query: String,
    readCurrentQuestion: suspend () -> SearchSnippet?
): SearchSnippet? {
    currentCoroutineContext().ensureActive()
    if (query.isBlank()) return null
    val complete = text.codePointCount(0, text.length) <= SEARCH_PROJECTION_CHARS &&
        answer.codePointCount(0, answer.length) <= SEARCH_PROJECTION_CHARS
    // Never combine an old batch prefix/answer with chunks from a newer single-question snapshot.
    val hit = if (complete) searchSnippet(text, query, answer = false) ?: searchSnippet(answer, query, answer = true)
        else readCurrentQuestion()
    currentCoroutineContext().ensureActive()
    return hit
}

internal fun searchSnippet(source: String, query: String, answer: Boolean,
                           hasPrefix: Boolean = false, hasSuffix: Boolean = false): SearchSnippet? {
    if (query.isBlank()) return null
    val hit = source.indexOf(query, ignoreCase = true)
    if (hit < 0) return null
    // Keep enough context to locate the match without retaining a whole answer.
    var start = (hit - 32).coerceAtLeast(0)
    var end = (hit + query.length.coerceAtMost(160) + 64).coerceAtMost(source.length)
    if (start > 0 && source[start].isLowSurrogate() && source[start - 1].isHighSurrogate()) start--
    if (end < source.length && source[end].isLowSurrogate() && source[end - 1].isHighSurrogate()) end++
    val text = (if (hasPrefix || start > 0) "…" else "") + source.substring(start, end).replace('\n', ' ').replace('\r', ' ') +
        (if (hasSuffix || end < source.length) "…" else "")
    val visibleQuery = query.take(160).replace('\n', ' ').replace('\r', ' ')
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (from < text.length) {
        val next = text.indexOf(visibleQuery, from, ignoreCase = true)
        if (next < 0) break
        ranges += next until next + visibleQuery.length
        from = next + visibleQuery.length
    }
    return SearchSnippet(text, answer, ranges)
}

/** SQLite offsets count Unicode code points. Keep a match plus its left context across reads. */
internal suspend fun searchChunks(query: String, answer: Boolean, read: suspend (Int, Int) -> String?): SearchSnippet? {
    if (query.isBlank()) return null
    val chunkSize = 8192
    var position = 1
    var tail = ""
    var discarded = false
    while (true) {
        currentCoroutineContext().ensureActive()
        val chunk = read(position, chunkSize).orEmpty()
        currentCoroutineContext().ensureActive()
        val window = tail + chunk
        val count = chunk.codePointCount(0, chunk.length)
        val more = count == chunkSize
        val hit = window.indexOf(query, ignoreCase = true)
        if (hit >= 0) {
            // One short lookahead supplies the snippet context and distinguishes an exact-size final chunk.
            val next = if (more && window.length - hit - query.length < 65) read(position + count, 65).orEmpty() else ""
            currentCoroutineContext().ensureActive()
            return searchSnippet(window + next, query, answer, hasPrefix = discarded,
                hasSuffix = more && next.codePointCount(0, next.length) == 65)
        }
        if (!more) return null
        val keep = (window.length - query.length - 34).coerceAtLeast(0)
        val start = if (keep > 0 && window[keep].isLowSurrogate()) keep - 1 else keep
        discarded = discarded || start > 0
        tail = window.substring(start)
        position += count
    }
}
