package com.zsz.studyassistant.data

/** Conversation-only text cache. Never owns files, PDF documents, or preview bitmaps. */
internal class PdfSourceSession(
    private val maxChars: Int = 2_000_000,
    private val maxPages: Int = 128
) {
    internal data class Page(val text: String, val extractedChars: Int)
    private val lock = Any()
    private val pages = LinkedHashMap<Pair<String, Int>, Page>(16, 0.75f, true)
    private val counts = LinkedHashMap<String, Int>(16, 0.75f, true)
    private var chars = 0
    private var disposed = false

    init { require(maxChars > 0 && maxPages > 0) }

    fun pageCount(sha256: String): Int? = synchronized(lock) { counts[sha256] }
    fun page(sha256: String, index: Int): Page? = synchronized(lock) { pages[sha256 to index] }

    /** Last document using each already-cached page. A scan retains keys, never a second text copy. */
    fun retainedPages(documents: List<ConversationDocument>): MutableMap<Pair<String, Int>, Int> = synchronized(lock) {
        val retained = mutableMapOf<Pair<String, Int>, Int>()
        documents.forEachIndexed { index, document ->
            val selection = document.pages?.toHashSet()
            for (key in pages.keys) {
                if (key.first == document.sha256 && (selection == null || key.second in selection)) retained[key] = index
            }
        }
        retained
    }

    // Call only after verifying the original and its PDF extraction permission.
    fun rememberPageCount(sha256: String, count: Int) = synchronized(lock) {
        if (!disposed) {
            require(count in 1..1000)
            counts[sha256] = count
            while (counts.size > maxPages) counts.remove(counts.keys.first())
        }
    }

    fun rememberPage(sha256: String, index: Int, page: Page,
        retained: Set<Pair<String, Int>> = emptySet()) = synchronized(lock) {
        if (!disposed) {
            require(index in 0..999 && page.extractedChars in 0..200_000)
            val key = sha256 to index
            pages.remove(key)?.let { chars -= it.text.length }
            if (page.text.length <= maxChars) {
                while (pages.size >= maxPages || chars + page.text.length > maxChars) {
                    val victim = pages.keys.firstOrNull { it !in retained } ?: return@synchronized
                    chars -= pages.remove(victim)!!.text.length
                }
                pages[key] = page
                chars += page.text.length
            }
        }
    }

    /** Disposal and inserts share this short lock, so a cancelled worker cannot repopulate it. */
    fun dispose() = synchronized(lock) {
        disposed = true
        pages.clear()
        counts.clear()
        chars = 0
    }
}
