package com.zsz.studyassistant.data

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryWriteQueueTest {
    private val id = QuestionIdentity.create(10000)
    private fun row(answer: String = "answer") = SolveSession(id, createdAt=10000, updatedAt=20000, text="question", answer=answer)
    private class Store {
        val rows = mutableMapOf<String, SolveSession>()
        val notebook = mutableSetOf<String>()
        var failWrite = false
        val dao = Proxy.newProxyInstance(QuestionDao::class.java.classLoader, arrayOf(QuestionDao::class.java)) { _, method, args ->
            val a = args.orEmpty()
            when (method.name) {
                "solveSessionOnce" -> rows[a[0]]
                "questionExists" -> a[0] in notebook
                "insertSolveSession" -> {
                    check(!failWrite) { "simulated storage failure" }
                    val r = a[0] as SolveSession; check(r.id !in rows); rows[r.id] = r; 1L
                }
                "updateSolveSession" -> {
                    check(!failWrite) { "simulated storage failure" }
                    val r = a[0] as SolveSession; if (r.id in rows) rows[r.id] = r; Unit
                }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as QuestionDao
    }
    @Test fun failedInitialSaveRetriesWithLatestAnswerAndOriginalIdentity() = runBlocking {
        val store = Store(); val queue = HistoryWriteQueue()
        val first = queue.enqueue(row("old"), true)
        store.failWrite = true
        try { persistHistoryWrite(store.dao,first.row,queue.canCreate(first),0); fail() }
        catch (_: IllegalStateException) { queue.fail(first) }
        assertEquals(1, queue.failedTickets().size)
        val latest = queue.enqueue(row("new"), false)
        assertTrue(queue.canCreate(latest)); assertFalse(queue.isCurrent(first))
        store.failWrite = false
        persistHistoryWrite(store.dao,latest.row,queue.canCreate(latest),0); queue.complete(latest)
        assertEquals(1, store.rows.size); assertEquals("new", store.rows.getValue(id).answer)
        assertEquals(10000L, store.rows.getValue(id).createdAt); assertTrue(queue.failedTickets().isEmpty())
    }
    @Test fun staleFailuresCannotReplaceOrClearNewerFailures() {
        val q = HistoryWriteQueue(); val old=q.enqueue(row("old"),true); val newest=q.enqueue(row("new"),false)
        q.fail(newest); q.fail(old); q.complete(old)
        assertEquals(listOf(newest),q.failedTickets())
    }
    @Test fun deletedUpdateIsNeverRecreated() = runBlocking {
        val s=Store(); persistHistoryWrite(s.dao,row(),false,0); assertTrue(s.rows.isEmpty())
    }
    @Test fun expiredFailedInitialSaveIsNotRecreated() = runBlocking {
        val s=Store(); persistHistoryWrite(s.dao,row(),true,10001); assertTrue(s.rows.isEmpty())
    }
    @Test fun retryUpdatesExistingRecordAndLinksOnlyCanonicalNotebookId() = runBlocking {
        val s=Store(); s.rows[id]=row("old"); s.notebook+=id
        persistHistoryWrite(s.dao,row("new"),true,0)
        assertEquals(1,s.rows.size); assertEquals("new",s.rows.getValue(id).answer)
        assertEquals(id,s.rows.getValue(id).savedQuestionId); assertEquals(10000L,s.rows.getValue(id).createdAt)
    }
    @Test fun deletionAndClearInvalidateCapturedRetryTickets() {
        val q=HistoryWriteQueue(); val t=q.enqueue(row(),true); q.fail(t); q.forget(listOf(id))
        assertFalse(q.isCurrent(t)); assertFalse(q.canCreate(t)); assertTrue(q.failedTickets().isEmpty())
        val next=q.enqueue(row(),true); q.fail(next); q.clear()
        assertFalse(q.isCurrent(next)); assertTrue(q.failedTickets().isEmpty())
    }
    @Test fun differentProblemsRetainIndependentFailures() {
        val q=HistoryWriteQueue(); val a=q.enqueue(row(),true)
        val b=q.enqueue(row().copy(id=QuestionIdentity.create(10000)),true)
        q.fail(a); q.fail(b); q.complete(a)
        assertEquals(listOf(b),q.failedTickets())
    }
}
