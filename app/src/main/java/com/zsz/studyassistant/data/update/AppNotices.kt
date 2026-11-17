package com.zsz.studyassistant.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppNotice(
    val id: String,
    val revision: Int,
    val distributions: List<String>,
    val channels: List<String>,
    val versions: List<String> = emptyList(),
    val minVersion: String? = null,
    val maxVersion: String? = null,
    val minVersionCode: Long? = null,
    val maxVersionCode: Long? = null,
    val startsAt: String? = null,
    val expiresAt: String,
    val title: Map<String, String>,
    val body: Map<String, String>
) {
    fun text(values: Map<String, String>, language: String): String =
        values[language] ?: values[if (language == "zh-TW") "zh-CN" else "en"] ?: values.getValue("zh-CN")
}

@Serializable
data class AppNoticeDocument(val schema: Int, val revision: Long,
    val announcements: List<AppNotice>, val unstableVersions: List<AppNotice>)

data class PendingAppNotice(val notice: AppNotice, val unstable: Boolean) {
    val key: String get() = (if (unstable) "unstable:" else "notice:") + notice.id
    val identity: String get() = "$key:${notice.revision}"
}

/** Pure, bounded policy. A notice can inform but can never impose an update lock. */
object AppNotices {
    const val MAX_BYTES = 128 * 1024
    const val CACHE_MAX_AGE = 7 * 24 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }
    private val version = Regex("v?(0|[1-9][0-9]{0,8})(\\.(0|[1-9][0-9]{0,8})){1,3}(_beta([1-9][0-9]{0,8})?)?")

    fun parse(text: String): AppNoticeDocument {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val document = json.decodeFromString<AppNoticeDocument>(text.removePrefix("\uFEFF"))
        require(document.schema == 1 && document.revision > 0)
        require(document.announcements.size + document.unstableVersions.size <= 64)
        val all = document.announcements + document.unstableVersions
        require(all.map { it.id }.distinct().size == all.size)
        for (group in listOf(document.announcements, document.unstableVersions)) {
            require(group.map { it.id }.distinct().size == group.size)
            for (n in group) {
                require(Regex("[0-9]{8}-[0-9]{6}-[0-9]{2}").matches(n.id) && n.revision > 0)
                LocalDateTime.parse(n.id.take(15), DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
                    .withResolverStyle(ResolverStyle.STRICT))
                require(n.distributions.isNotEmpty() && n.distributions.all { it in setOf("main", "compat") })
                require(n.channels.isNotEmpty() && n.channels.all { it in setOf("stable", "beta") })
                require(n.versions.size <= 32 && n.versions.all(version::matches))
                require(n.minVersion == null || version.matches(n.minVersion))
                require(n.maxVersion == null || version.matches(n.maxVersion))
                // Explicit exact versions OR an inclusive range; never silently target everyone.
                require(if (n.versions.isNotEmpty()) n.minVersion == null && n.maxVersion == null
                    else n.minVersion != null || n.maxVersion != null)
                require(n.minVersion == null || n.maxVersion == null ||
                    UpdateChecker.compareVersions(n.minVersion, n.maxVersion) <= 0)
                require(n.minVersionCode == null || n.minVersionCode > 0)
                require(n.maxVersionCode == null || n.maxVersionCode > 0)
                require(n.minVersionCode == null || n.maxVersionCode == null || n.minVersionCode <= n.maxVersionCode)
                val end = Instant.parse(n.expiresAt).toEpochMilli()
                require(n.startsAt == null || Instant.parse(n.startsAt).toEpochMilli() < end)
                for ((values, limit) in listOf(n.title to 120, n.body to 6000)) {
                    require(!values["zh-CN"].isNullOrBlank())
                    require(values.keys.all { it in setOf("zh-CN", "zh-TW", "en", "ja", "ko") })
                    require(values.values.all { it.isNotBlank() && it.length <= limit })
                }
            }
        }
        return document
    }

    fun matches(n: AppNotice, installed: String, code: Long, distribution: String, now: Long): Boolean {
        if (distribution !in n.distributions ||
            (if (installed.contains("_beta")) "beta" else "stable") !in n.channels) return false
        if (now >= Instant.parse(n.expiresAt).toEpochMilli() ||
            n.startsAt?.let { now < Instant.parse(it).toEpochMilli() } == true) return false
        if (n.versions.isNotEmpty() && n.versions.none { UpdateChecker.compareVersions(installed, it) == 0 }) return false
        if (n.minVersion?.let { UpdateChecker.compareVersions(installed, it) < 0 } == true ||
            n.maxVersion?.let { UpdateChecker.compareVersions(installed, it) > 0 } == true) return false
        return (n.minVersionCode == null || code >= n.minVersionCode) &&
            (n.maxVersionCode == null || code <= n.maxVersionCode)
    }

    fun pending(document: AppNoticeDocument, installed: String, code: Long, distribution: String,
        now: Long, acknowledged: Map<String, Int>): List<PendingAppNotice> =
        (document.unstableVersions.map { PendingAppNotice(it, true) } +
            document.announcements.map { PendingAppNotice(it, false) }).filter {
            it.notice.revision > (acknowledged[it.key] ?: 0) && matches(it.notice, installed, code, distribution, now)
        }

    fun cacheFresh(fetchedAt: Long, now: Long): Boolean = fetchedAt > 0 && now >= fetchedAt && now - fetchedAt < CACHE_MAX_AGE

    /** Re-evaluate scheduled starts, expiry edits and cache expiry without polling the network. */
    fun nextChangeAt(document: AppNoticeDocument, fetchedAt: Long, now: Long): Long? {
        if (!cacheFresh(fetchedAt, now)) return null
        val cacheEnd = if (fetchedAt > Long.MAX_VALUE - CACHE_MAX_AGE) Long.MAX_VALUE else fetchedAt + CACHE_MAX_AGE
        return (document.announcements + document.unstableVersions).asSequence()
            .flatMap { sequenceOf(it.startsAt, it.expiresAt).filterNotNull() }
            .map { Instant.parse(it).toEpochMilli() }.plus(cacheEnd).filter { it > now }.minOrNull()
    }

    fun accepts(previous: AppNoticeDocument?, next: AppNoticeDocument): Boolean = previous == null ||
        next.revision > previous.revision || (next.revision == previous.revision && next == previous)
}
