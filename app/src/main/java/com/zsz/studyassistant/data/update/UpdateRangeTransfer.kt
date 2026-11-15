package com.zsz.studyassistant.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** Strict byte-range handling, independent of Android and of the chosen transport host. */
internal object UpdateRangeTransfer {
    class Rejected(val errorKey: String = "update.resumeUnsupported") : IOException()

    /** Called before opening the output: a server ignoring Range must never corrupt retained bytes. */
    fun responseLength(code: Int, range: String?, length: Long, encoding: String?, offset: Long, total: Long): Long {
        if (total <= 0 || offset !in 0..total || (!encoding.isNullOrBlank() && !encoding.equals("identity", true)))
            throw Rejected()
        var remaining = total - offset
        when (code) {
            200 -> if (offset != 0L || (length >= 0 && length != total)) throw Rejected()
            206 -> {
                val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(range.orEmpty()) ?: throw Rejected()
                val start = match.groupValues[1].toLongOrNull() ?: throw Rejected()
                val end = match.groupValues[2].toLongOrNull() ?: throw Rejected()
                val size = match.groupValues[3].toLongOrNull() ?: throw Rejected()
                // A server may cap a range response; the next request starts after this exact slice.
                if (start != offset || size != total || end >= total || start > end) throw Rejected()
                remaining = end - start + 1
                if (length >= 0 && length != remaining) throw Rejected()
            }
            else -> throw IOException("Update response unavailable")
        }
        return remaining
    }

    /** guard serializes file operations with cancellation/removal; it must check the task generation. */
    fun append(code: Int, range: String?, length: Long, encoding: String?, input: InputStream,
        file: File, total: Long, guard: (() -> Unit) -> Unit, check: () -> Unit = {}) {
        var offset = 0L
        guard { offset = file.length() }
        val remaining = responseLength(code, range, length, encoding, offset, total)
        var output: RandomAccessFile? = null
        try {
            guard {
                if (file.length() != offset) throw IOException("Update partial changed")
                output = RandomAccessFile(file, "rw").apply { seek(offset) }
            }
            val buffer = ByteArray(64 * 1024)
            var received = 0L
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count.toLong() > remaining - received) throw Rejected("update.verifyFailed")
                guard { check(); checkNotNull(output).write(buffer, 0, count) }
                received += count
            }
            if (received != remaining) throw IOException("Update response interrupted")
        } finally {
            output?.use { it.fd.sync() }
        }
    }
}
