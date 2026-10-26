package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupStreamCopyTest {
    @Test fun verifiesExactBytesAcrossChunks() {
        val bytes = ByteArray(200_123) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        BackupStreamCopy.writeAndVerify({ ByteArrayInputStream(bytes) }, { out }, { ByteArrayInputStream(out.toByteArray()) })
        assertArrayEquals(bytes, out.toByteArray())
    }
    @Test fun refusesReadbackCorruption() {
        try {
            BackupStreamCopy.writeAndVerify({ ByteArrayInputStream(byteArrayOf(1)) }, { ByteArrayOutputStream() }, { ByteArrayInputStream(byteArrayOf(2)) })
            fail("corrupt provider accepted")
        } catch (e: BackupWriteException) { assertEquals("data.autoBackup.verifyFailed", e.messageKey) }
    }
    @Test fun checksCancellationDuringReadAndWrite() {
        var calls = 0
        try {
            BackupStreamCopy.writeAndVerify({ ByteArrayInputStream(ByteArray(200_000)) }, { ByteArrayOutputStream() }, { ByteArrayInputStream(byteArrayOf()) }) {
                if (++calls == 3) throw IllegalStateException("stop")
            }
            fail("not canceled")
        } catch (e: IllegalStateException) { assertEquals("stop", e.message) }
    }
}
