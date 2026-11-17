package com.zsz.studyassistant.data

import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/** Storage failures must not consume network retries, mirrors or a larger full-package fallback. */
internal object UpdateStorage {
    const val ERROR_KEY = "update.storageInsufficient"
    const val RESERVE = 4L * 1024 * 1024
    class InsufficientSpace : IOException(ERROR_KEY)

    fun requireSpace(path: File, bytes: Long, available: (File) -> Long = ::usableSpace) {
        require(bytes >= 0)
        if (available(path) < bytes.coerceAtMost(Long.MAX_VALUE - RESERVE) + RESERVE)
            throw InsufficientSpace()
    }

    /** Partial + external handoff + private verified copy coexist until verification succeeds. */
    fun requireDownloadSpace(partial: File, destination: File, size: Long, sharedStorage: Boolean,
        available: (File) -> Long = ::usableSpace) {
        require(size in 1..UpdateDelta.MAX_APK_SIZE)
        val remaining = (size - partial.length()).coerceAtLeast(0)
        requireSpace(partial, remaining + size + if (sharedStorage) size else 0, available)
        requireSpace(destination, if (sharedStorage) remaining + size * 2 else size, available)
    }

    private fun usableSpace(path: File): Long {
        var existing = path.absoluteFile
        while (!existing.exists()) existing = existing.parentFile ?: return 0
        return existing.usableSpace
    }

    fun isInsufficient(error: Throwable): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        fun matches(current: Throwable): Boolean {
            if (!visited.add(current)) return false
            if (current is InsufficientSpace ||
                (current is ErrnoException && current.errno == OsConstants.ENOSPC)) return true
            // Some stream implementations expose errno only in the IOException message.
            if (current is IOException && Regex("\\bENOSPC\\b|no space left on device", RegexOption.IGNORE_CASE)
                    .containsMatchIn(current.message.orEmpty())) return true
            return current.cause?.let(::matches) == true || current.suppressed.any(::matches)
        }
        return matches(error)
    }
}
