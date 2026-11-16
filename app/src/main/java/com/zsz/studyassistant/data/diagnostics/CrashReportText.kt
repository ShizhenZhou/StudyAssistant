package com.zsz.studyassistant.data

/** Read actual Throwable fields; never try to distinguish stack frames from multiline messages. */
internal object CrashReportText {
    private val version = Regex("[0-9]+(?:\\.[0-9]+){1,4}(?:[_-][A-Za-z0-9.-]+)?")

    fun fromThrowable(error: Throwable): String = buildString {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && seen.size < 8 && length < 16 * 1024 && seen.add(current)) {
            appendLine("Exception: ${current.javaClass.name}")
            for (frame in current.stackTrace.take(80)) {
                if (length > 16 * 1024) break
                // Basename only, even when a compiler embeds an absolute source path.
                val source = frame.fileName?.substringAfterLast('/')?.substringAfterLast('\\')
                appendLine("at ${frame.className}.${frame.methodName}(${source ?: "Unknown Source"}:${frame.lineNumber})")
            }
            current = current.cause
        }
    }.trim()

    /** Legacy raw logs have no reliable message/frame boundary: export header metadata only. */
    fun extract(raw: String): String = buildString {
        raw.take(1024).lineSequence().take(2).forEach { line ->
            val text = line.trim()
            when {
                text.startsWith("应用版本：") -> {
                    val value = text.substringAfter('：')
                    if (version.matches(value)) appendLine("Crash app version: $value")
                }
                text.startsWith("时间：") -> {
                    val value = text.substringAfter('：')
                    if (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}").matches(value))
                        appendLine("Crash time: $value")
                }
            }
        }
    }.trim()
}
