package com.zsz.studyassistant.data

import java.util.UUID

/** One request owns its cancellation hook and usage. Finishing another request cannot clear either. */
class StreamOwner {
    val id: String = UUID.randomUUID().toString()
    private val lock = Any()
    private var cancelled = false
    private var cancelAction: (() -> Unit)? = null
    @Volatile var usage: DeepSeekUsage? = null
    @Volatile var conversationTitle: String? = null
    @Volatile var responseContext: AiResponseContext? = null
    /** Only a terminal length response with complete tool metadata can be replayed safely. */
    @Volatile var resumableContext: AiResponseContext? = null
    @Volatile var usedWeb: Boolean = false
    fun attach(action: () -> Unit) {
        val runNow = synchronized(lock) { if (cancelled) true else { cancelAction = action; false } }
        if (runNow) action()
    }
    fun cancel() {
        val action = synchronized(lock) { cancelled = true; cancelAction.also { cancelAction = null } }
        action?.invoke()
    }
    fun finish() { synchronized(lock) { cancelAction = null } }
}

/** A bounded, plain list label. Never use this to replace the original question. */
internal fun normalizedConversationTitle(value: String?): String? {
    if (value == null) return null
    var count = 0
    var spacePending = false
    val points = value.codePoints().iterator()
    return buildString {
        while (points.hasNext() && count < 120) {
            val point = points.nextInt()
            when {
                Character.isWhitespace(point) || Character.isSpaceChar(point) -> spacePending = isNotEmpty()
                Character.isISOControl(point) || Character.getType(point) == Character.FORMAT.toInt() -> Unit
                else -> {
                    if (spacePending) {
                        if (count >= 119) break
                        append(' '); count++
                    }
                    spacePending = false
                    appendCodePoint(point)
                    count++
                }
            }
        }
    }.takeIf { it.isNotBlank() }
}
