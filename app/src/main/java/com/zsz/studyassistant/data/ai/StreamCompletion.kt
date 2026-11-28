package com.zsz.studyassistant.data

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Fixed, localized reasons only: never include raw server data in exceptions or logs. */
class StreamIncompleteException(val messageKey: String) : Exception(messageKey)

internal fun Exception.isConnectionFailure(): Boolean {
    if (this is java.net.SocketException || this is java.net.UnknownHostException ||
        this is java.io.InterruptedIOException || this is java.io.EOFException || this is javax.net.ssl.SSLException) return true
    val detail = message.orEmpty().lowercase()
    return listOf("timed out", "timeout", "connect", "unreachable", "socket", "network").any { it in detail }
}

private enum class StreamStartup { WAITING, FINISHED, TIMED_OUT }

/** Cancel the specific HTTP call on expiry, including when the reader is blocked in socket IO. */
internal suspend fun <T> withStreamStartupDeadline(
    cancelCall: () -> Unit,
    timeoutMillis: Long,
    awaitStart: suspend () -> Unit = {},
    operation: suspend (markProgress: () -> Unit) -> T
): T = coroutineScope {
    val startup = AtomicReference(StreamStartup.WAITING)
    val alarm = launch(Dispatchers.Default) {
        awaitStart()
        delay(timeoutMillis)
        if (startup.compareAndSet(StreamStartup.WAITING, StreamStartup.TIMED_OUT)) cancelCall()
    }
    fun finishWaiting() {
        if (startup.compareAndSet(StreamStartup.WAITING, StreamStartup.FINISHED)) alarm.cancel()
    }
    val result = try {
        operation(::finishWaiting)
    } catch (error: IOException) {
        finishWaiting()
        if (startup.get() == StreamStartup.TIMED_OUT)
            throw SocketTimeoutException("Stream start timeout").apply { initCause(error) }
        throw error
    } finally {
        finishWaiting()
    }
    if (startup.get() == StreamStartup.TIMED_OUT) throw SocketTimeoutException("Stream start timeout")
    result
}

/** SSE heartbeats, role-only chunks and usage metadata do not count as generation. */
internal suspend fun readStreamChunks(
    source: okio.BufferedSource,
    completion: StreamCompletion,
    cancelCall: () -> Unit,
    firstProgressMillis: Long = 45_000,
    onChunk: suspend (StreamChunk) -> Unit
): Unit = withStreamStartupDeadline(cancelCall, firstProgressMillis) { markProgress ->
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = readStreamLine(source) ?: break
        if (!line.startsWith("data:")) continue
        val chunk = completion.accept(line.substring(5).trim())
        if (completion.done) break
        if (chunk == null) continue
        val choice = chunk.choices.firstOrNull()
        val delta = choice?.delta
        if (!delta?.content.isNullOrEmpty() || !delta?.reasoningContent.isNullOrEmpty() ||
            choice?.serverToolEvent?.let(::toolProgressKey) != null) markProgress()
        onChunk(chunk)
    }
}

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
