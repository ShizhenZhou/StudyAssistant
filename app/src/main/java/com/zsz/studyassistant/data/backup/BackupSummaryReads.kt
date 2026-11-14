package com.zsz.studyassistant.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shares only overlapping reads. Completed inventories are never cached across refreshes. */
internal class BackupSummaryReads<K, V> {
    private class Flight<K, V>(val key: K) {
        val result = CompletableDeferred<Result<V>>()
    }
    private var pending: Flight<K, V>? = null

    suspend fun read(key: K, load: suspend () -> V): V {
        while (true) {
            currentCoroutineContext().ensureActive()
            var owner = false
            val flight = synchronized(this) {
                pending?.takeIf { it.key == key } ?: Flight<K, V>(key).also {
                    pending = it
                    owner = true
                }
            }
            if (!owner) {
                val result = flight.result.await()
                currentCoroutineContext().ensureActive()
                // A canceled producer must not cancel another screen's still-active read.
                if (result.exceptionOrNull() is CancellationException) continue
                return result.getOrThrow()
            }
            val result = try {
                Result.success(load().also { currentCoroutineContext().ensureActive() })
            } catch (error: Throwable) { Result.failure(error) }
            synchronized(this) { if (pending === flight) pending = null }
            flight.result.complete(result)
            return result.getOrThrow()
        }
    }
}
