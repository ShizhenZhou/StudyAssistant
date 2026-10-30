package com.zsz.studyassistant.data

/** Only this bounded text crosses from the database reader to the search UI. */
data class SearchSnippet(val text: String, val answer: Boolean, val highlights: List<IntRange>)

internal fun searchSnippet(source: String, query: String, answer: Boolean): SearchSnippet? {
    if (query.isBlank()) return null
    val hit = source.indexOf(query, ignoreCase = true)
    if (hit < 0) return null
    // Keep enough context to locate the match without retaining a whole answer.
    var start = (hit - 32).coerceAtLeast(0)
    var end = (hit + query.length.coerceAtMost(160) + 64).coerceAtMost(source.length)
    if (start > 0 && source[start].isLowSurrogate() && source[start - 1].isHighSurrogate()) start--
    if (end < source.length && source[end].isLowSurrogate() && source[end - 1].isHighSurrogate()) end++
    val text = (if (start > 0) "…" else "") + source.substring(start, end).replace('\n', ' ').replace('\r', ' ') +
        (if (end < source.length) "…" else "")
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
