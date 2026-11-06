package com.zsz.studyassistant.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReadLimitsTest {
    @Test fun oversizedTextIsRejectedBeforeReading() = runBlocking {
        var reads = 0
        try {
            readChunkedText(Int.MAX_VALUE) { _, _ -> reads++; "x" }
            fail("Oversized field accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, reads)
    }

    @Test fun incompleteChunkCannotBecomePartialSavedContent() = runBlocking {
        try {
            readChunkedText(3) { _, _ -> "ab" }
            fail("Truncated field accepted")
        } catch (_: IllegalStateException) { }
    }

    @Test fun sqliteOffsetsCountCodePointsAcrossChunkBoundary() = runBlocking {
        val first = "😀".repeat(65536)
        val text = readChunkedText(65537) { offset, count ->
            when (offset) {
                1 -> { assertEquals(65536, count); first }
                65537 -> { assertEquals(1, count); "尾" }
                else -> error("Wrong SQLite offset")
            }
        }
        assertEquals(first + "尾", text)
        assertNull(readChunkedText(null) { _, _ -> error("NULL must not be read") })
        assertEquals("", readChunkedText(0) { _, _ -> error("Empty must not be read") })
    }

    @Test fun cancelledReadDoesNotRequestAnotherChunk() = runBlocking {
        var reads = 0
        val job = launch(start = CoroutineStart.LAZY) {
            readChunkedText(65537) { _, count ->
                reads++
                currentCoroutineContext().cancel()
                "x".repeat(count)
            }
        }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, reads)
    }
}
