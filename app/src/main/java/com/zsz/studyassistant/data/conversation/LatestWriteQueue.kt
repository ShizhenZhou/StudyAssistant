package com.zsz.studyassistant.data

import kotlinx.coroutines.*

/** Main-thread confined. One running write, at most one pending value per identity.
 * Periodic submits allocate no waiting coroutine; explicit flushes await the latest replacement.
 * Already admitted writes finish on owner cancellation, as the existing draft writer did.
 */
internal class LatestWriteQueue<K, V, R>(
    private val scope: CoroutineScope,
    private val keyOf: (V) -> K,
    private val merge: (V, V) -> V = { _, latest -> latest },
    private val write: suspend (V) -> R,
    private val onFinished: suspend (V, Result<R>) -> Unit = { _, _ -> }
) {
    private class Pending<V, R>(var value: V) {
        val waiters = mutableListOf<CompletableDeferred<Result<R>>>()
    }
    private val pending = linkedMapOf<K, Pending<V, R>>()
    private var running = false
    private var worker: Job? = null

    fun submit(value: V, awaitResult: Boolean = false): Deferred<Result<R>>? {
        val key = keyOf(value)
        val entry = pending[key]?.also { it.value = merge(it.value, value) }
            ?: Pending<V, R>(value).also { pending[key] = it }
        val waiter = if (awaitResult) CompletableDeferred<Result<R>>().also { entry.waiters += it } else null
        if (!running) {
            running = true
            worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    try {
                        while (pending.isNotEmpty()) {
                            val next = pending.entries.first()
                            val item = next.value
                            pending.remove(next.key)
                            val result = try { Result.success(write(item.value)) }
                                catch (e: Exception) { Result.failure(e) }
                            try { onFinished(item.value, result) }
                            finally { item.waiters.forEach { it.complete(result) } }
                        }
                    } finally { running = false }
                }
            }
        }
        return waiter
    }

    suspend fun awaitIdle() { worker?.join() }
}
