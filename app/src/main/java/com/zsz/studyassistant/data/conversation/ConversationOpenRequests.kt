package com.zsz.studyassistant.data

/** Main-thread arbitration shared by notebook, history and draft entry points. */
internal class ConversationOpenRequests {
    private var sequence = 0L
    fun begin(): Long = ++sequence
    fun snapshot(): Long = sequence
    fun isCurrent(request: Long): Boolean = sequence == request
}
