package com.zsz.studyassistant.data

/** Originals must be validated by the caller before looking up derived text. */
internal class DocumentTextCache(private val maxCharacters: Int = 2_000_000) {
    private data class Key(val hash: String, val name: String, val sheets: List<String>?)
    private val entries = LinkedHashMap<Key, String>(8, .75f, true)
    private var characters = 0

    @Synchronized fun get(document: ConversationDocument, extract: () -> String): String {
        val key = Key(document.sha256, document.name, document.sheets?.toList())
        entries[key]?.let { return it }
        val text = extract()
        if (text.length <= maxCharacters) {
            while (entries.isNotEmpty() && (characters + text.length > maxCharacters || entries.size >= 8)) {
                characters -= entries.remove(entries.keys.first())!!.length
            }
            entries[key] = text
            characters += text.length
        }
        return text
    }
    @Synchronized fun clear() { entries.clear(); characters = 0 }
}
