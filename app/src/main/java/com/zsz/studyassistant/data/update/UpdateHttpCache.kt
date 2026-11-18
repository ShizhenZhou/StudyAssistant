package com.zsz.studyassistant.data

import android.content.Context
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Public HTTP representations only. OkHttp isolates URL/Vary and merges validated 304 responses. */
internal object UpdateHttpCache {
    private var cache: Cache? = null
    private val clients = java.util.IdentityHashMap<OkHttpClient, OkHttpClient>()
    val revalidate = CacheControl.Builder().maxAge(0, TimeUnit.SECONDS).build()

    @Synchronized fun client(context: Context?, base: OkHttpClient): OkHttpClient {
        if (context == null) return base
        if (cache == null) cache = Cache(File(context.applicationContext.cacheDir, "update-http-v1"), 8L * 1024 * 1024)
        return clients.getOrPut(base) { base.newBuilder().cache(cache).build() }
    }
}
