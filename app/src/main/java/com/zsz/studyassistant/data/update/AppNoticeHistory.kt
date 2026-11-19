package com.zsz.studyassistant.data

import android.content.Context
import com.zsz.studyassistant.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** No preferences, disk cache, validators or process-held bodies: every open is a fresh request. */
internal class AppNoticeHistoryFetcher(private val client: OkHttpClient) {
    suspend fun fetch(urls: List<String>): AppNoticeDocument? =
        AppNoticeFetcher(client).fetch(urls, null, null, null)?.document
}

object AppNoticeHistory {
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
    }

    suspend fun load(context: Context): List<PendingAppNotice>? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val document = AppNoticeHistoryFetcher(client)
            .fetch(UpdateTransport.urls(AppNoticeStore.URL, UpdateTransport.load(app))) ?: return@withContext null
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val version = info.versionName ?: return@withContext null
        AppNotices.history(document, version, info.longVersionCode, BuildConfig.DISTRIBUTION, System.currentTimeMillis())
    }
}
