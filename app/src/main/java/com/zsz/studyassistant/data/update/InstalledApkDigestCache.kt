package com.zsz.studyassistant.data

import java.io.File

/** Process-local, single-install cache. Failed reads are retried, never remembered as a digest. */
internal class InstalledApkDigestCache {
    private data class Identity(val path: String, val length: Long, val modifiedAt: Long, val installedAt: Long)
    private var cachedIdentity: Identity? = null
    private var cachedDigest: String? = null

    @Synchronized
    fun read(file: File, installedAt: Long, calculate: (File) -> String?): String? {
        val identity = Identity(file.canonicalPath, file.length(), file.lastModified(), installedAt)
        if (cachedIdentity == identity && cachedDigest != null) return cachedDigest
        val digest = calculate(file)
        if (digest != null) {
            cachedIdentity = identity
            cachedDigest = digest
        }
        return digest
    }
}
