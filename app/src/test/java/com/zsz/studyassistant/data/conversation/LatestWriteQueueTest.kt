package com.zsz.studyassistant.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LatestWriteQueueTest {
    @Test fun slowWriterKeepsOnlyLatestPendingSnapshotAndFinalWaitsForIt() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        val queue = LatestWriteQueue<String, Int, Int>(this, { "draft" }, write = {
            if (it == 1) release.await()
            writes += it
            it
        })
        queue.submit(1)
        yield()
        (2..99).forEach { queue.submit(it) }
        val final = queue.submit(100, awaitResult = true)!!
        assertFalse(final.isCompleted)
        release.complete(Unit)
        assertEquals(100, final.await().getOrThrow())
        queue.awaitIdle()
        assertEquals(listOf(1, 100), writes)
    }

    @Test fun separateGenerationsArePreservedAndSupersededWaitersFollowNewerWrite() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Pair<String, Int>>()
        val queue = LatestWriteQueue<String, Pair<String, Int>, Int>(this, { it.first }, write = {
            if (it.second == 1) release.await()
            writes += it
            it.second
        })
        queue.submit("old" to 1); yield()
        val waiting = queue.submit("old" to 2, awaitResult = true)!!
        queue.submit("new" to 3)
        queue.submit("old" to 4)
        release.complete(Unit)
        assertEquals(4, waiting.await().getOrThrow())
        queue.awaitIdle()
        assertEquals(listOf("old" to 1, "old" to 4, "new" to 3), writes)
    }

    @Test fun failedWriteDoesNotDropNextSnapshotAndAdmittedFinalSurvivesScopeCancellation() = runBlocking {
        val owner = Job()
        val scope = CoroutineScope(coroutineContext + owner)
        val release = CompletableDeferred<Unit>()
        val queue = LatestWriteQueue<String, Int, Int>(scope, { "draft" }, write = {
            if (it == 1) { release.await(); error("disk") }
            it
        })
        val failed = queue.submit(1, awaitResult = true)!!; yield()
        val final = queue.submit(2, awaitResult = true)!!
        owner.cancel()
        release.complete(Unit)
        assertTrue(failed.await().isFailure)
        assertEquals(2, final.await().getOrThrow())
        queue.awaitIdle()
    }
}
