package com.zsz.studyassistant.data

/** Keeps only the newest snapshot per problem; retry never replays an older answer. Main-thread owned. */
internal class HistoryWriteQueue {
    data class Ticket(val row: SolveSession, val sequence: Long)
    private var sequence = 0L
    private val latest = linkedMapOf<QuestionId, Ticket>()
    private val failed = linkedMapOf<QuestionId, Ticket>()
    private val creationPending = mutableSetOf<QuestionId>()

    fun enqueue(row: SolveSession, create: Boolean): Ticket {
        if (create) creationPending += row.id
        return Ticket(row, ++sequence).also { latest[row.id] = it; failed.remove(row.id) }
    }
    fun isCurrent(ticket: Ticket): Boolean = latest[ticket.row.id] === ticket
    fun canCreate(ticket: Ticket): Boolean = ticket.row.id in creationPending
    fun fail(ticket: Ticket) { if (isCurrent(ticket)) failed[ticket.row.id] = ticket }
    fun complete(ticket: Ticket) { if (isCurrent(ticket)) forget(listOf(ticket.row.id)) }
    fun pendingTickets(): List<Ticket> = latest.values.toList()
    fun failedTickets(): List<Ticket> = failed.values.toList()
    fun clear() { latest.clear(); failed.clear(); creationPending.clear() }
    fun forget(ids: Collection<QuestionId>) {
        ids.forEach { latest.remove(it); failed.remove(it); creationPending.remove(it) }
    }
}

/** Caller holds a database transaction and the history write lock. A missing UPDATE stays missing. */
internal suspend fun persistHistoryWrite(dao: QuestionDao, row: SolveSession, allowCreate: Boolean, cutoff: Long) {
    val previous = dao.solveSessionOnce(row.id)
    val linked = row.copy(savedQuestionId = row.id.takeIf { dao.questionExists(it) })
    if (previous != null) {
        dao.updateSolveSession(linked.copy(createdAt = QuestionIdentity.earliest(previous.createdAt, row.createdAt)))
    } else if (allowCreate && row.createdAt >= cutoff) {
        dao.insertSolveSession(linked)
    }
}
