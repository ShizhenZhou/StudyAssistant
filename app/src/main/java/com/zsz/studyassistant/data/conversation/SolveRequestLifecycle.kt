package com.zsz.studyassistant.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray

/** Main-thread owner of the active generation and its resumable request, independent of UI/persistence. */
internal class SolveRequestLifecycle {
    var job: Job? = null
    var owner: StreamOwner? = null
    var sequence = 0L
    var prepare: (() -> List<DeepSeekMessage>)? = null
    var messages: List<DeepSeekMessage>? = null
    var model = ""
    var prefix = ""
    var action = "initial"
    var onDone: ((String) -> Unit)? = null
    var webEnabled = false
    var webHistory: List<DeepSeekMessage> = emptyList()
    var webAnnotations: JsonArray? = null

    fun begin(): StreamOwner {
        owner?.cancel()
        job?.cancel()
        sequence++
        return StreamOwner().also { owner = it }
    }

    fun cancel() { owner?.cancel(); job?.cancel() }

    /** Only bounded local preparation/storage belongs here, never a network request.
     * A user can stop generation while encoding; retain the request before honouring that stop. */
    suspend fun <T> prepareRetainingOnCancel(prepare: suspend () -> T, retain: suspend (T) -> Unit) {
        withContext(NonCancellable) { retain(prepare()) }
        currentCoroutineContext().ensureActive()
    }

    fun invalidate(): StreamOwner? {
        val previous = owner
        cancel()
        owner = null
        job = null
        sequence++
        return previous
    }
}
