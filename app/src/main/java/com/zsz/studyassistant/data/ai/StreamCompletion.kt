package com.zsz.studyassistant.data

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** Fixed, localized reasons only: never include raw server data in exceptions or logs. */
class StreamIncompleteException(val messageKey: String) : Exception(messageKey)

internal fun Exception.isConnectionFailure(): Boolean {
    if (this is java.net.SocketException || this is java.net.UnknownHostException ||
        this is java.io.InterruptedIOException || this is java.io.EOFException || this is javax.net.ssl.SSLException) return true
    val detail = message.orEmpty().lowercase()
    return listOf("timed out", "timeout", "connect", "unreachable", "socket", "network").any { it in detail }
}

private enum class StreamReadState { WAITING, FINISHED, TIMED_OUT }
private class StreamReadDeadlineExceeded : SocketTimeoutException("Stream read timeout")

/** Bound connection/upload and response headers separately. Awaiting headers must be cancellable. */
internal suspend fun <T : Any> awaitStreamHeaders(
    sent: Deferred<Unit>,
    sendMillis: Long = 30_000,
    headersMillis: Long = 20_000,
    operation: suspend () -> T
): T = coroutineScope {
    val response = async { operation() }
    val awaitingHeaders = withTimeoutOrNull(sendMillis) {
        select<Boolean> {
            response.onAwait { false }
            sent.onAwait { true }
        }
    } ?: throw SocketTimeoutException("Stream request send timeout")
    if (!awaitingHeaders) response.await()
    else withTimeoutOrNull(headersMillis) { response.await() }
        ?: throw SocketTimeoutException("Stream response headers timeout")
}

/** Cancel the specific HTTP call on expiry, including when the reader is blocked in socket IO. */
private suspend fun <T> withStreamReadDeadline(
    cancelCall: () -> Unit,
    timeoutMillis: Long,
    operation: suspend (markProgress: () -> Unit) -> T
): T = coroutineScope {
    val state = AtomicReference(StreamReadState.WAITING)
    val alarm = launch(Dispatchers.Default) {
        delay(timeoutMillis)
        if (state.compareAndSet(StreamReadState.WAITING, StreamReadState.TIMED_OUT)) cancelCall()
    }
    fun finishWaiting() {
        if (state.compareAndSet(StreamReadState.WAITING, StreamReadState.FINISHED)) alarm.cancel()
    }
    val result = try {
        operation(::finishWaiting)
    } catch (error: IOException) {
        finishWaiting()
        if (state.get() == StreamReadState.TIMED_OUT)
            throw StreamReadDeadlineExceeded().apply { initCause(error) }
        throw error
    } finally {
        finishWaiting()
    }
    if (state.get() == StreamReadState.TIMED_OUT) throw StreamReadDeadlineExceeded()
    result
}

/** SSE heartbeats, role-only chunks and usage metadata do not count as generation. */
internal suspend fun readStreamChunks(
    source: okio.BufferedSource,
    completion: StreamCompletion,
    cancelCall: () -> Unit,
    firstProgressMillis: Long = 45_000,
    terminalGraceMillis: Long = 2_000,
    onChunk: suspend (StreamChunk) -> Unit
) {
    suspend fun nextChunk(): StreamChunk? {
        while (!completion.done) {
            currentCoroutineContext().ensureActive()
            val line = readStreamLine(source) ?: break
            if (line.startsWith("data:")) completion.accept(line.substring(5).trim())?.let { return it }
        }
        return null
    }
    withStreamReadDeadline(cancelCall, firstProgressMillis) { markProgress ->
        while (!completion.hasTerminalStatus) {
            val chunk = nextChunk() ?: break
            val choice = chunk.choices.firstOrNull()
            val delta = choice?.delta
            if (completion.hasTerminalStatus || !delta?.content.isNullOrEmpty() || !delta?.reasoningContent.isNullOrEmpty() ||
                choice?.serverToolEvent?.let(::toolProgressKey) != null) markProgress()
            onChunk(chunk)
        }
    }
    if (!completion.hasTerminalStatus) return
    // DeepSeek can send usage in a separate frame. Give it a short window after finish_reason.
    try {
        withStreamReadDeadline(cancelCall, terminalGraceMillis) {
            while (true) {
                val chunk = try {
                    nextChunk()
                } catch (_: IOException) {
                    currentCoroutineContext().ensureActive()
                    break
                } ?: break
                onChunk(chunk)
            }
        }
    } catch (_: StreamReadDeadlineExceeded) {
        currentCoroutineContext().ensureActive()
        // The terminal status already determines success or the reason for interruption.
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
    val hasTerminalStatus: Boolean get() = finishReason != null || serverError
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
