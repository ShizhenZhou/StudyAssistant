package com.zsz.studyassistant.data

import kotlinx.serialization.json.Json

/** Fixed, localized reasons only: never include raw server data in exceptions or logs. */
class StreamIncompleteException(val messageKey: String) : Exception(messageKey)

/** Pure protocol state shared by production reads and synthetic SSE regression tests. */
class StreamCompletion {
    private val json = Json { ignoreUnknownKeys = true }
    var done = false
        private set
    private var finishReason: String? = null
    fun accept(payload: String): StreamChunk? {
        if (payload.isBlank()) return null
        if (payload == "[DONE]") { done = true; return null }
        val chunk = try { json.decodeFromString<StreamChunk>(payload) }
        catch (_: Exception) { throw StreamIncompleteException("err.streamInvalid") }
        chunk.choices.firstOrNull()?.finishReason?.let { finishReason = it }
        return chunk
    }
    fun requireComplete() {
        when (finishReason) {
            "stop" -> return // Explicit completion is authoritative even if the [DONE] footer was lost.
            "length" -> throw StreamIncompleteException("err.streamLength")
            "content_filter" -> throw StreamIncompleteException("err.streamFiltered")
            else -> throw StreamIncompleteException("err.streamInterrupted")
        }
    }
}
