package com.zsz.studyassistant.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class VerifiedBackupWriterTest {
    @Test fun utf8RoundTripClosesWriterBeforeReading() {
        val text = "x" + "题目😀".repeat(5000) // Split a surrogate pair across an 8192-character write boundary.
        var closed = false
        val out = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        VerifiedBackupWriter.writeAndVerify(text, { out }, {
            assertTrue(closed)
            assertArrayEquals(text.toByteArray(Charsets.UTF_8), out.toByteArray())
            ByteArrayInputStream(out.toByteArray())
        })
    }
    @Test fun silentTruncationFailsInsteadOfAllowingRetention() {
        val out = ByteArrayOutputStream()
        try {
            VerifiedBackupWriter.writeAndVerify("full backup", { out }, { ByteArrayInputStream(out.toByteArray().dropLast(1).toByteArray()) })
            fail()
        } catch (e: BackupWriteException) { assertEquals("data.autoBackup.verifyFailed", e.messageKey) }
    }
    @Test fun sameLengthCorruptionIsDetected() {
        try {
            VerifiedBackupWriter.writeAndVerify("abc", { ByteArrayOutputStream() }, { ByteArrayInputStream("abd".toByteArray()) })
            fail()
        } catch (e: BackupWriteException) { assertEquals("data.autoBackup.verifyFailed", e.messageKey) }
    }
    @Test fun writeFailureNeverAttemptsVerification() {
        var read = false
        try {
            VerifiedBackupWriter.writeAndVerify("abc", { object : OutputStream() {
                override fun write(b: Int) { throw IOException("simulated full storage") }
            } }, { read = true; ByteArrayInputStream(byteArrayOf()) })
            fail()
        } catch (_: IOException) { assertFalse(read) }
    }
    @Test fun missingReadHandleFailsVerification() {
        try { VerifiedBackupWriter.writeAndVerify("abc", { ByteArrayOutputStream() }, { null }); fail() }
        catch (e: BackupWriteException) { assertEquals("data.autoBackup.verifyFailed", e.messageKey) }
    }
    @Test fun missingWriteHandleFailsWithoutReading() {
        var read = false
        try { VerifiedBackupWriter.writeAndVerify("abc", { null }, { read = true; null }); fail() }
        catch (e: BackupWriteException) { assertEquals("data.autoBackup.writeFailed", e.messageKey); assertFalse(read) }
    }
    @Test fun cancellationIsNotReportedAsSuccess() {
        try {
            VerifiedBackupWriter.writeAndVerify("abc", { ByteArrayOutputStream() }, { ByteArrayInputStream(byteArrayOf()) },
                checkActive = { throw kotlinx.coroutines.CancellationException("cancelled") })
            fail()
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }
}
