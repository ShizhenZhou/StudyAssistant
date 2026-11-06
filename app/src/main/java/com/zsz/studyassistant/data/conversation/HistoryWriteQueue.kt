package com.zsz.studyassistant.data

/** Keeps only the newest snapshot per problem; retry never replays an older answer. Main-thread owned. */
internal class HistoryWriteQueue {
    class Ticket(private val snapshot: SaveSnapshot<SolveSession>, val sequence: Long) {
        val id: QuestionId get() = snapshot.id
        suspend fun row(): SolveSession = snapshot.value()
    }
    private var sequence = 0L
    private val latest = linkedMapOf<QuestionId, Ticket>()
    private val failed = linkedMapOf<QuestionId, Ticket>()
    private val creationPending = mutableSetOf<QuestionId>()

    fun enqueue(row: SolveSession, create: Boolean): Ticket = enqueue(SaveSnapshot(row.id) { row }, create)
    fun enqueue(snapshot: SaveSnapshot<SolveSession>, create: Boolean): Ticket {
        if (create) creationPending += snapshot.id
        return Ticket(snapshot, ++sequence).also { latest[snapshot.id] = it; failed.remove(snapshot.id) }
    }
    fun isCurrent(ticket: Ticket): Boolean = latest[ticket.id] === ticket
    fun canCreate(ticket: Ticket): Boolean = ticket.id in creationPending
    fun fail(ticket: Ticket) { if (isCurrent(ticket)) failed[ticket.id] = ticket }
    fun complete(ticket: Ticket) { if (isCurrent(ticket)) forget(listOf(ticket.id)) }
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
