package com.zsz.studyassistant.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SaveSnapshotTest {
    @Test fun preparationIsDeferredOffCallerThreadAndReused() = runBlocking {
        val caller = Thread.currentThread()
        var preparations = 0
        val snapshot = SaveSnapshot("id") {
            assertNotSame(caller, Thread.currentThread())
            preparations++
            Any()
        }
        assertEquals(0, preparations)
        val results = coroutineScope { List(4) { async { snapshot.value() } }.awaitAll() }
        assertEquals(1, preparations)
        results.forEach { assertSame(results.first(), it) }
    }

    @Test fun failedPreparationCanRetryItsFrozenInputs() = runBlocking {
        var attempts = 0
        val snapshot = SaveSnapshot("id") { if (++attempts == 1) error("temporary"); "saved" }
        try { snapshot.value(); fail() } catch (_: IllegalStateException) { }
        assertEquals("saved", snapshot.value())
        assertEquals("saved", snapshot.value())
        assertEquals(2, attempts)
    }

    @Test fun delayedPreparationCannotRestoreADeletedTicket() = runBlocking {
        val queue = HistoryWriteQueue()
        val id = QuestionIdentity.create(10000)
        var prepared = false
        val ticket = queue.enqueue(SaveSnapshot(id) {
            prepared = true
            SolveSession(id, createdAt = 10000)
        }, true)
        assertFalse(prepared)
        queue.forget(listOf(id))
        ticket.row()
        assertFalse(queue.isCurrent(ticket))
        assertFalse(queue.canCreate(ticket))
        queue.fail(ticket)
        assertTrue(queue.pendingTickets().isEmpty())
    }
}
