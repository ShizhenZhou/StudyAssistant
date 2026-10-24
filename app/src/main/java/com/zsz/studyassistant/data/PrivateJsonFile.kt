package com.zsz.studyassistant.data

import android.util.AtomicFile
import java.io.File

/** Only app-private metadata. Atomic replacement retains the last complete file on write failure. */
internal class PrivateJsonFile(private val path: File, private val maxBytes: Int) {
    companion object { private val lock = Any() }
    fun read(): String? = synchronized(lock) {
        val atomic = AtomicFile(path)
        if (!path.exists() && !File(path.path + ".bak").exists()) return@synchronized null
        atomic.openRead().use { input ->
            val bytes = ByteArray(maxBytes + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            require(count <= maxBytes) { "Local metadata too large" }
            String(bytes, 0, count, Charsets.UTF_8)
        }
    }
    fun write(text: String) = synchronized(lock) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "Local metadata too large" }
        check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs())
        val atomic = AtomicFile(path)
        val stream = atomic.startWrite()
        var finished = false
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
            finished = true
            // Some Android AtomicFile implementations log rename/sync failures instead of throwing.
            // Never acknowledge a saved draft/diagnostic until its complete bytes can be read back.
            check(read() == text) { "Local metadata verification failed" }
        } catch (e: Exception) {
            if (!finished) atomic.failWrite(stream)
            throw e
        }
    }
    fun clear() = synchronized(lock) {
        AtomicFile(path).delete()
        check(!path.exists() && !File(path.path + ".bak").exists() && !File(path.path + ".new").exists())
    }
}
