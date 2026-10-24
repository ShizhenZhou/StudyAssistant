package com.zsz.studyassistant.data

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** UTC build identity, independent of phone installation time and attachment re-upload time. */
object BuildIdentity {
    fun parseStamp(value: String): Long {
        if (!value.matches(Regex("[0-9]{12}([0-9]{2})?"))) return 0
        return try {
            val pattern = if (value.length == 12) "uuuuMMddHHmm" else "uuuuMMddHHmmss"
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT))
                .toInstant(ZoneOffset.UTC).toEpochMilli().takeIf { it > 0 } ?: 0
        } catch (_: Exception) { 0 }
    }
    fun assetTime(name: String, version: String): Long {
        val prefix = "StudyAssistant-$version-"
        if (!name.startsWith(prefix) || !name.endsWith(".apk")) return 0
        return parseStamp(name.removePrefix(prefix).removeSuffix(".apk"))
    }
    fun isReplacement(installedAt: Long, publishedAt: Long, installedSha: String?, publishedSha: String?): Boolean {
        val hashesKnown = installedSha != null && publishedSha != null
        if (hashesKnown && installedSha.equals(publishedSha, ignoreCase = true)) return false
        if (installedAt > 0 && publishedAt > 0) {
            if (publishedAt < installedAt) return false
            if (publishedAt > installedAt) return true
        }
        // Legacy APKs have no embedded timestamp. Compare exact bytes when both digests exist.
        return hashesKnown && !installedSha.equals(publishedSha, ignoreCase = true)
    }
}
