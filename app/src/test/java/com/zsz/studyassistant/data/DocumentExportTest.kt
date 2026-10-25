package com.zsz.studyassistant.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentExportTest {
    @Test fun `open write and close run away from the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                assertNotSame(caller, Thread.currentThread()); super.write(bytes, off, len)
            }
            override fun close() { assertNotSame(caller, Thread.currentThread()); closed = true; super.close() }
        }
        writeUtf8Document("synthetic 题目") { assertNotSame(caller, Thread.currentThread()); out }
        assertTrue(closed)
        assertEquals("synthetic 题目", out.toString("UTF-8"))
    }
    @Test fun `missing destination is an error rather than export success`() = runBlocking {
        try { writeUtf8Document("synthetic") { null }; fail("Null output accepted") }
        catch (_: IllegalStateException) { }
    }
    @Test fun `close failure is propagated before a caller can report success`() = runBlocking {
        val out = object : ByteArrayOutputStream() { override fun close() { throw IOException("synthetic full storage") } }
        try { writeUtf8Document("synthetic") { out }; fail("Close error lost") }
        catch (e: IOException) { assertEquals("synthetic full storage", e.message) }
    }
}
