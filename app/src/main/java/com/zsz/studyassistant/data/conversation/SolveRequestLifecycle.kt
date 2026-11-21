package com.zsz.studyassistant.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Main-thread owner of the active generation and its resumable request, independent of UI/persistence. */
internal class SolveRequestLifecycle {
    var job: Job? = null
    var owner: StreamOwner? = null
    var sequence = 0L
    data class Continuation(
        val model: String,
        val action: String,
        val webEnabled: Boolean,
        val context: ContinuationContext,
        val prepare: (() -> List<DeepSeekMessage>)? = null,
        val onDone: (String) -> Unit
    )

    var continuation: Continuation? = null
    fun begin(): StreamOwner {
        owner?.cancel()
        job?.cancel()
        sequence++
        continuation = null
        return StreamOwner().also { owner = it }
    }

    fun cancel() { owner?.cancel(); job?.cancel() }

    fun owns(candidate: StreamOwner, expectedSequence: Long): Boolean =
        owner === candidate && sequence == expectedSequence

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
