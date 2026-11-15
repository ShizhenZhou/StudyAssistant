package com.zsz.studyassistant.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Synthetic byte fixtures cover corruption and retained-progress risks without Android/network IO. */
class UpdateRangeTransferTest {
    private fun fixture(block: (File) -> Unit) {
        val file = File.createTempFile("update-range-", ".part")
        try { block(file) } finally { file.delete() }
    }
    private fun append(file: File, code: Int, range: String?, body: String, total: Long = 6) =
        UpdateRangeTransfer.append(code, range, body.length.toLong(), null, body.byteInputStream(), file, total, { it() })

    @Test fun exactRangeAppendsOnlyTheMissingSuffix() = fixture { file ->
        file.writeText("abc")
        append(file, 206, "bytes 3-5/6", "def")
        assertEquals("abcdef", file.readText())
    }

    @Test fun cappedRangeCanContinueInAnotherResponse() = fixture { file ->
        file.writeText("ab")
        append(file, 206, "bytes 2-3/6", "cd")
        assertEquals("abcd", file.readText())
        append(file, 206, "bytes 4-5/6", "ef")
        assertEquals("abcdef", file.readText())
    }

    @Test fun ignoredRangeNeverTruncatesOrAppendsRetainedBytes() = fixture { file ->
        file.writeText("abc")
        val error = assertThrows(UpdateRangeTransfer.Rejected::class.java) { append(file, 200, null, "abcdef") }
        assertEquals("update.resumeUnsupported", error.errorKey)
        assertEquals("abc", file.readText())
    }

    @Test fun wrongRangeOrPublishedLengthCannotModifyThePartial() = fixture { file ->
        file.writeText("abc")
        for (range in listOf("bytes 2-4/6", "bytes 3-5/7", "bytes 3-7/6", "bytes 3-5/*", "bytes 3-5/6 ")) {
            assertThrows(UpdateRangeTransfer.Rejected::class.java) { append(file, 206, range, "def") }
            assertEquals("abc", file.readText())
        }
        assertThrows(UpdateRangeTransfer.Rejected::class.java) { append(file, 206, "bytes 3-5/6", "de") }
        assertEquals("abc", file.readText())
    }

    @Test fun interruptedResponseRetainsBytesForAnotherRange() = fixture { file ->
        file.writeText("ab")
        assertThrows(IOException::class.java) {
            UpdateRangeTransfer.append(206, "bytes 2-5/6", 4, null, "cd".byteInputStream(), file, 6, { it() })
        }
        assertEquals("abcd", file.readText())
        append(file, 206, "bytes 4-5/6", "ef")
        assertEquals("abcdef", file.readText())
    }

    @Test fun cancellationBetweenReadAndWriteCannotResurrectRemovedBytes() = fixture { file ->
        file.writeText("abc")
        var cancelled = false
        val input = object : ByteArrayInputStream("def".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                cancelled = true
                file.delete()
                return count
            }
        }
        assertThrows(IOException::class.java) {
            UpdateRangeTransfer.append(206, "bytes 3-5/6", 3, null, input, file, 6,
                { action -> if (cancelled) throw IOException("cancelled") else action() })
        }
        // Android can unlink an open descriptor; Windows may defer deletion until it is closed.
        if (file.exists()) assertEquals("abc", file.readText())
    }
}
