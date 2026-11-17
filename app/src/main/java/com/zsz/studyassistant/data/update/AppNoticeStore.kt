package com.zsz.studyassistant.data

import android.content.Context
import com.zsz.studyassistant.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Public notices share update-check opportunities; no extra worker, credentials or learning data. */
object AppNoticeStore {
    const val URL = "https://raw.githubusercontent.com/${UpdateChecker.REPO}/main/config/app-notices.json"
    private val refreshSession = AppNoticeRefreshSession(CoroutineScope(SupervisorJob() + Dispatchers.IO))
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

    suspend fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        refreshSession.run {
            restore(app)
            val p = prefs(app)
            val now = System.currentTimeMillis()
            val previous = read(app)
            val fetchedAt = p.getLong("fetchedAt", 0)
            val attemptedAt = p.getLong("attemptedAt", 0)
            if (!AppNoticeRefreshPolicy.shouldRefresh(now, fetchedAt, attemptedAt, previous != null, force)) return@run
            p.edit().putLong("attemptedAt", now).commit()
            val result = AppNoticeFetcher(client).fetch(UpdateTransport.urls(URL, UpdateTransport.load(app)),
                p.getString("document", null), p.getString("etagUrl", null), p.getString("etag", null)) ?: return@run
            val completedAt = System.currentTimeMillis()
            stateMutex.withLock {
                p.edit().putString("document", result.raw).putLong("fetchedAt", completedAt)
                    .putString("etag", result.etag).putString("etagUrl", result.url).commit()
                publish(app, result.document, completedAt)
            }
        }
    }
}
