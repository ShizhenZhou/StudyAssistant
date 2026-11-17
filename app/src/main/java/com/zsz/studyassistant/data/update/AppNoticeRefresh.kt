package com.zsz.studyassistant.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

internal object AppNoticeRefreshPolicy {
    const val FOREGROUND_TICK = 60_000L
    private const val INTERVAL = 15 * 60_000L

    fun shouldRefresh(now: Long, fetchedAt: Long, attemptedAt: Long, hasDocument: Boolean, force: Boolean): Boolean {
        fun recent(time: Long, interval: Long) = time > 0 && now >= time && now - time < interval
        // A failed manual request must be retryable even if the preceding success was recent.
        if (!force && hasDocument && attemptedAt <= fetchedAt && recent(fetchedAt, INTERVAL)) return false
        return !recent(attemptedAt, if (force) 15_000L else FOREGROUND_TICK)
    }
}

/** Only the waiter belongs to the screen/worker. A bounded, serial fetch belongs to the process. */
internal class AppNoticeRefreshSession(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    suspend fun run(block: suspend () -> Unit) {
        scope.async { mutex.withLock { block() } }.await()
    }
}

internal data class AppNoticeFetchResult(
    val raw: String, val document: AppNoticeDocument, val url: String, val etag: String?
)

internal class AppNoticeFetcher(private val client: OkHttpClient) {
    suspend fun fetch(urls: List<String>, previousRaw: String?, etagUrl: String?, etag: String?): AppNoticeFetchResult? {
        val previous = previousRaw?.let { runCatching { AppNotices.parse(it) }.getOrNull() }
        for (url in urls) {
            currentCoroutineContext().ensureActive()
            try {
                val validator = etag?.takeIf { previous != null && etagUrl == url && it.isNotBlank() }
                val request = Request.Builder().url(url).header("User-Agent", "StudyAssistant")
                    .header("Accept", "application/json").header("Cache-Control", "no-cache")
                if (validator != null) request.header("If-None-Match", validator)
                client.newCall(request.build()).execute().use { response ->
                    currentCoroutineContext().ensureActive()
                    val raw = if (response.code == 304 && validator != null) previousRaw!! else {
                        if (!response.isSuccessful) throw IOException("Notice request unavailable")
                        val source = response.body.source()
                        if (source.request(AppNotices.MAX_BYTES + 1L)) throw IOException("Notice document too large")
                        source.readUtf8()
                    }
                    val next = AppNotices.parse(raw)
                    if (!AppNotices.accepts(previous, next)) throw IOException("Stale notice revision")
                    return AppNoticeFetchResult(raw, next, url,
                        response.header("ETag") ?: if (response.code == 304) validator else null)
                }
            } catch (error: CancellationException) { throw error }
              catch (_: Exception) { /* Try the configured mirror; retain unexpired cache if both fail. */ }
        }
        return null
    }
}
