package com.zsz.studyassistant.data

import kotlinx.serialization.json.Json

/** Fixed, localized reasons only: never include raw server data in exceptions or logs. */
class StreamIncompleteException(val messageKey: String) : Exception(messageKey)

/** Pure protocol state shared by production reads and synthetic SSE regression tests. */
class StreamCompletion(private val maxCharacters: Int = 4_000_000) {
    private val json = Json { ignoreUnknownKeys = true }
    var done = false
        private set
    private var finishReason: String? = null
    private var received = 0L
    private var serverError = false
    val canContinueLength: Boolean get() = !serverError && finishReason == "length"
    fun accept(payload: String): StreamChunk? {
        if (payload.isBlank()) return null
        if (payload == "[DONE]") { done = true; return null }
        val chunk = try { json.decodeFromString<StreamChunk>(payload) }
        catch (_: Exception) { throw StreamIncompleteException("err.streamInvalid") }
        if (chunk.error != null) serverError = true
        val delta = chunk.choices.firstOrNull()?.delta
        received += (delta?.content?.length ?: 0).toLong() + (delta?.reasoningContent?.length ?: 0)
        if (received > maxCharacters) throw StreamIncompleteException("err.streamLength")
        chunk.choices.firstOrNull()?.finishReason?.let { finishReason = it }
        return chunk
    }
    fun requireComplete() {
        if (serverError) throw StreamIncompleteException("err.streamInterrupted")
        when (finishReason) {
            "stop" -> return // Explicit completion is authoritative even if the [DONE] footer was lost.
            "length" -> throw StreamIncompleteException("err.streamLength")
            "content_filter" -> throw StreamIncompleteException("err.streamFiltered")
            else -> throw StreamIncompleteException("err.streamInterrupted")
        }
    }
}

/** Bound even a never-terminated SSE line, while returning each normal line immediately. */
internal fun readStreamLine(source: okio.BufferedSource, limit: Long = 2_097_152): String? {
    val newline = source.indexOf('\n'.code.toByte(), 0, limit + 1)
    if (newline >= 0) return source.readUtf8LineStrict(limit)
    if (source.buffer.size > limit || source.request(limit + 1)) throw StreamIncompleteException("err.streamLength")
    return if (source.buffer.size == 0L) null else source.readUtf8()
}
