package com.zsz.studyassistant.data

import android.content.Context
import com.zsz.studyassistant.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Public notices share update-check opportunities; no extra worker, credentials or learning data. */
object AppNoticeStore {
    const val URL = "https://raw.githubusercontent.com/${UpdateChecker.REPO}/main/config/app-notices.json"
    private const val INTERVAL = 6 * 60 * 60 * 1000L
    private val networkMutex = Mutex()
    private val stateMutex = Mutex()
    private val mutablePending = MutableStateFlow<List<PendingAppNotice>>(emptyList())
    val pending = mutablePending.asStateFlow()
    private val mutableNextChangeAt = MutableStateFlow<Long?>(null)
    val nextChangeAt = mutableNextChangeAt.asStateFlow()
    private val sessionAcknowledged = mutableMapOf<String, Int>()
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
    }
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("app_notices", Context.MODE_PRIVATE)
    private fun read(c: Context): AppNoticeDocument? = runCatching {
        prefs(c).getString("document", null)?.let(AppNotices::parse)
    }.getOrNull()

    private fun publish(c: Context, document: AppNoticeDocument?, fetchedAt: Long) {
        val now = System.currentTimeMillis()
        mutableNextChangeAt.value = document?.let { AppNotices.nextChangeAt(it, fetchedAt, now) }
        if (document == null || !AppNotices.cacheFresh(fetchedAt, now)) {
            mutablePending.value = emptyList()
            return
        }
        val info = runCatching { c.packageManager.getPackageInfo(c.packageName, 0) }.getOrNull()
        val installed = info?.versionName
        if (installed.isNullOrBlank()) { mutablePending.value = emptyList(); return }
        val acknowledged = prefs(c).all.mapNotNull { (key, value) ->
            if (key.startsWith("seen.") && value is Int) key.removePrefix("seen.") to value else null
        }.toMap().toMutableMap()
        sessionAcknowledged.forEach { (key, value) -> acknowledged[key] = maxOf(value, acknowledged[key] ?: 0) }
        mutablePending.value = AppNotices.pending(document, installed, info.longVersionCode,
            BuildConfig.DISTRIBUTION, now, acknowledged)
    }

    suspend fun restore(context: Context) = withContext(Dispatchers.IO) {
        stateMutex.withLock { publish(context, read(context), prefs(context).getLong("fetchedAt", 0)) }
    }

    suspend fun acknowledge(context: Context, item: PendingAppNotice) = withContext(Dispatchers.IO) {
        stateMutex.withLock {
            val revision = maxOf(item.notice.revision, prefs(context).getInt("seen.${item.key}", 0))
            sessionAcknowledged[item.key] = revision
            prefs(context).edit().putInt("seen.${item.key}", revision).commit()
            // A storage failure must not trap the user in a modal during this process.
            mutablePending.value = mutablePending.value.filterNot { it.key == item.key && it.notice.revision <= revision }
        }
    }

    suspend fun refresh(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!networkMutex.tryLock()) return@withContext
        try {
            restore(context)
            val p = prefs(context)
            val now = System.currentTimeMillis()
            val previous = read(context)
            val fetchedAt = p.getLong("fetchedAt", 0)
            val attemptedAt = p.getLong("attemptedAt", 0)
            if (!force && previous != null && now >= fetchedAt && now - fetchedAt < INTERVAL) return@withContext
            val retryAfter = if (force) 15_000L else 15 * 60 * 1000L
            if (now >= attemptedAt && now - attemptedAt < retryAfter) return@withContext
            p.edit().putLong("attemptedAt", now).commit()
            for (url in UpdateTransport.urls(URL, UpdateTransport.load(context))) {
                currentCoroutineContext().ensureActive()
                try {
                    val request = Request.Builder().url(url).header("User-Agent", "StudyAssistant")
                        .header("Accept", "application/json")
                    val etag = p.getString("etag", null)
                    if (previous != null && p.getString("etagUrl", null) == url && !etag.isNullOrBlank())
                        request.header("If-None-Match", etag)
                    client.newCall(request.build()).execute().use { response ->
                        currentCoroutineContext().ensureActive()
                        val raw = if (response.code == 304 && previous != null) p.getString("document", null)!!
                        else {
                            if (!response.isSuccessful) throw IOException("Notice request unavailable")
                            val source = response.body.source()
                            if (source.request(AppNotices.MAX_BYTES + 1L)) throw IOException("Notice document too large")
                            source.readUtf8()
                        }
                        val next = AppNotices.parse(raw)
                        if (!AppNotices.accepts(previous, next)) throw IOException("Stale notice revision")
                        val completedAt = System.currentTimeMillis()
                        stateMutex.withLock {
                            p.edit().putString("document", raw).putLong("fetchedAt", completedAt)
                                .putString("etag", response.header("ETag") ?: if (response.code == 304) etag else null)
                                .putString("etagUrl", url).commit()
                            publish(context, next, completedAt)
                        }
                    }
                    return@withContext
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { /* Try the configured mirror once; retain unexpired cache. */ }
            }
        } finally { networkMutex.unlock() }
    }
}
