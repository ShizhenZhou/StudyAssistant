package com.zsz.studyassistant.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** API 28-compatible bounded read. Callers may request limit + 1 to detect oversized input. */
internal fun InputStream.readAtMost(limit: Int): ByteArray {
    require(limit >= 0)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(minOf(limit, 8192))
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        if (count == 0) {
            val byte = read()
            if (byte < 0) break
            output.write(byte)
        } else output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
