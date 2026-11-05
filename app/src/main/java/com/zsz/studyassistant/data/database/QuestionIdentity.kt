package com.zsz.studyassistant.data

import java.util.UUID

/** Permanent identity, shared by notebook, original search record and backup v3. */
typealias QuestionId = String

object QuestionIdentity {
    private val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}-[0-9]+$")
    fun create(createdAt: Long = System.currentTimeMillis()): QuestionId {
        require(createdAt > 0)
        return "${UUID.randomUUID()}-$createdAt"
    }
    fun valid(id: QuestionId): Boolean = pattern.matches(id) && time(id) > 0
    fun time(id: QuestionId): Long = id.substringAfterLast('-').toLongOrNull() ?: 0L
    fun earliest(vararg times: Long): Long = times.filter { it > 0L }.minOrNull() ?: 0L
}
