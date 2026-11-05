package com.zsz.studyassistant.data

import java.util.UUID

/** One request owns its cancellation hook and usage. Finishing another request cannot clear either. */
class StreamOwner {
    val id: String = UUID.randomUUID().toString()
    private val lock = Any()
    private var cancelled = false
    private var cancelAction: (() -> Unit)? = null
    @Volatile var usage: DeepSeekUsage? = null
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
