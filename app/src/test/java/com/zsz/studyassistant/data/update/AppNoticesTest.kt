package com.zsz.studyassistant.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppNoticesTest {
    private val now = Instant.parse("2026-11-17T00:00:00Z").toEpochMilli()
    private val rule = AppNotice("20261117-173000-01", 1, listOf("main"), listOf("stable"),
        versions = listOf("v0.7.4.2"), expiresAt = "2026-12-01T00:00:00Z",
        title = mapOf("zh-CN" to "测试公告"), body = mapOf("zh-CN" to "人工数据"))

    @Test fun historyRetainsExpiredButRejectsWithdrawnDisabledFutureAndOtherScopes() {
        val expired = rule.copy(expiresAt = "2026-11-17T00:00:00Z")
        val doc = AppNoticeDocument(1, 1, listOf(expired), emptyList())
        fun history(d: AppNoticeDocument = doc, version: String = "0.7.4.2", distribution: String = "main") =
            AppNotices.history(d, version, 136, distribution, now)
        assertEquals(expired, history().single().notice)
        assertFalse(AppNotices.matches(expired, "0.7.4.2", 136, "main", now))
        assertTrue(history(version = "0.7.4.3").isEmpty())
        assertTrue(history(distribution = "compat").isEmpty())
        assertTrue(history(version = "0.7.4.2_beta1").isEmpty())
        assertTrue(history(doc.copy(announcements = emptyList())).isEmpty())
        assertTrue(history(doc.copy(announcements = listOf(rule.copy(enabled = false)))).isEmpty())
        assertFalse(AppNotices.matches(rule.copy(enabled = false), "0.7.4.2", 136, "main", now))
        assertTrue(history(doc.copy(announcements = listOf(rule.copy(startsAt = "2026-11-18T00:00:00Z")))).isEmpty())
        assertTrue(history(doc.copy(announcements = listOf(rule.copy(minVersionCode = 137)))).isEmpty())
    }

    @Test fun historyFetchesEveryOpenWithoutValidatorsAndNeverReturnsOldBodyAfterFailure() = runBlocking {
        var requests = 0
        val document = AppNoticeDocument(1, 1, listOf(rule), emptyList())
        val body = kotlinx.serialization.json.Json.encodeToString(AppNoticeDocument.serializer(), document)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertNull(chain.request().header("If-None-Match"))
            assertEquals("no-cache", chain.request().header("Cache-Control"))
            if (requests == 2) throw IOException("Artificial offline state")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("ETag", "ignored").body((if (requests == 1) body else
                    """{"schema":1,"revision":2,"announcements":[],"unstableVersions":[]}""").toResponseBody()).build()
        }.build()
        val history = AppNoticeHistoryFetcher(client)
        val urls = listOf(AppNoticeStore.URL)
        assertEquals(rule, history.fetch(urls)!!.announcements.single())
        assertNull(history.fetch(urls))
        assertTrue(history.fetch(urls)!!.announcements.isEmpty())
        assertEquals(3, requests)
        assertNull(client.cache)
    }

    @Test fun foregroundRefreshUsesFifteenMinutesAndRetriesFailuresAfterOneMinute() {
        val minute = 60_000L
        assertFalse(AppNoticeRefreshPolicy.shouldRefresh(now + 14 * minute, now, now - 1, true, false))
        assertTrue(AppNoticeRefreshPolicy.shouldRefresh(now + 15 * minute, now, now - 1, true, false))
        assertTrue(AppNoticeRefreshPolicy.shouldRefresh(now + minute, now, now - 1, true, true))
        assertFalse(AppNoticeRefreshPolicy.shouldRefresh(now + 14_999, 0, now, false, true))
        assertFalse(AppNoticeRefreshPolicy.shouldRefresh(now + minute - 1, now - minute, now, true, false))
        assertTrue(AppNoticeRefreshPolicy.shouldRefresh(now + minute, now - minute, now, true, false))
        assertTrue(AppNoticeRefreshPolicy.shouldRefresh(now, now + minute, now + minute, true, false))
    }

    @Test fun leavingUpdatePageDoesNotCancelOfficialFailureMirrorFallback() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val unblock = CountDownLatch(1)
        val finished = CompletableDeferred<AppNoticeFetchResult>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = AppNoticeRefreshSession(appScope)
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val body = """{"schema":1,"revision":2,"announcements":[],"unstableVersions":[]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.toString()
            assertEquals("no-cache", request.header("Cache-Control"))
            if (request.url.host == "raw.githubusercontent.com") {
                assertEquals("old-etag", request.header("If-None-Match"))
                started.complete(Unit)
                check(unblock.await(5, TimeUnit.SECONDS))
                throw IOException("Artificial official endpoint failure")
            }
            assertNull(request.header("If-None-Match"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("ETag", "mirror-etag").body(body.toResponseBody()).build()
        }.build()
        try {
            val page = launch {
                session.run {
                    val result = AppNoticeFetcher(client).fetch(
                        UpdateTransport.urls(AppNoticeStore.URL, UpdateTransport.Config()),
                        body.replace("\"revision\":2", "\"revision\":1"), AppNoticeStore.URL, "old-etag")
                    finished.complete(result!!)
                }
            }
            withTimeout(5_000) { started.await() }
            page.cancelAndJoin()
            unblock.countDown()
            val result = withTimeout(5_000) { finished.await() }
            assertEquals(2L, result.document.revision)
            assertEquals("mirror-etag", result.etag)
            assertEquals(UpdateTransport.urls(AppNoticeStore.URL, UpdateTransport.Config()), requests)
        } finally { unblock.countDown(); appScope.cancel() }
    }

    @Test fun disabledMirrorDoesNotSendFallbackAndInvalidOfficialContentDoesFallBack() = runBlocking {
        var count = 0
        val valid = """{"schema":1,"revision":2,"announcements":[],"unstableVersions":[]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (chain.request().url.host == "raw.githubusercontent.com") "<html>unavailable</html>" else valid)
                    .toResponseBody()).build()
        }.build()
        val fetcher = AppNoticeFetcher(client)
        assertNull(fetcher.fetch(UpdateTransport.urls(AppNoticeStore.URL, UpdateTransport.Config(enabled = false)), null, null, null))
        assertEquals(1, count)
        count = 0
        assertEquals(2L, fetcher.fetch(UpdateTransport.urls(AppNoticeStore.URL, UpdateTransport.Config()), null, null, null)!!.document.revision)
        assertEquals(2, count)
    }

    @Test fun exactVersionChannelAndDistributionStayIndependent() {
        assertTrue(AppNotices.matches(rule, "0.7.4.2", 136, "main", now))
        assertFalse(AppNotices.matches(rule, "0.7.4.3", 137, "main", now))
        assertFalse(AppNotices.matches(rule, "0.7.4.2_beta1", 135, "main", now))
        assertFalse(AppNotices.matches(rule, "0.7.4.2", 136, "compat", now))
        assertFalse(AppNotices.matches(rule.copy(minVersionCode = 137), "0.7.4.2", 136, "main", now))
        val beta = rule.copy(versions = listOf("0.7.4.2_beta1"), channels = listOf("beta"))
        assertTrue(AppNotices.matches(beta, "0.7.4.2_beta1", 135, "main", now))
    }

    @Test fun inclusiveRangeAndTimeWindowUseNumericVersionOrder() {
        val range = rule.copy(versions = emptyList(), minVersion = "0.7.4.2", maxVersion = "0.7.4.10")
        assertTrue(AppNotices.matches(range, "0.7.4.10", 150, "main", now))
        assertFalse(AppNotices.matches(range, "0.7.4.11", 151, "main", now))
        assertTrue(AppNotices.matches(range.copy(maxVersion = null), "0.8.0", 160, "main", now))
        assertFalse(AppNotices.matches(rule.copy(startsAt = "2026-11-18T00:00:00Z"), "0.7.4.2", 136, "main", now))
        assertFalse(AppNotices.matches(rule.copy(expiresAt = "2026-11-17T00:00:00Z"), "0.7.4.2", 136, "main", now))
    }

    @Test fun acknowledgementRevisionWithdrawalAndPriority() {
        val doc = AppNoticeDocument(1, 2, listOf(rule.copy(id = "20261117-173000-02")), listOf(rule))
        val items = AppNotices.pending(doc, "0.7.4.2", 136, "main", now, emptyMap())
        assertTrue(items.first().unstable)
        val seen = mapOf(items.first().key to 1)
        assertEquals(1, AppNotices.pending(doc, "0.7.4.2", 136, "main", now, seen).size)
        assertEquals(2, AppNotices.pending(doc.copy(unstableVersions = listOf(rule.copy(revision = 2))),
            "0.7.4.2", 136, "main", now, seen).size)
        val withdrawn = AppNoticeDocument(1, 3, emptyList(), emptyList())
        assertTrue(AppNotices.accepts(doc, withdrawn))
        assertTrue(AppNotices.pending(withdrawn, "0.7.4.2", 136, "main", now, emptyMap()).isEmpty())
        assertFalse(AppNotices.accepts(withdrawn, doc))
        assertFalse(AppNotices.accepts(doc, doc.copy(announcements = emptyList())))
    }

    @Test fun rejectsAmbiguousOrInvalidConfigurationAndSupportsEmptyReservation() {
        val json = kotlinx.serialization.json.Json
        fun encoded(n: AppNotice) = json.encodeToString(AppNoticeDocument.serializer(), AppNoticeDocument(1, 1, listOf(n), emptyList()))
        assertEquals(rule, AppNotices.parse(encoded(rule)).announcements.single())
        for (bad in listOf(rule.copy(id = "20260230-173000-01"), rule.copy(distributions = emptyList()), rule.copy(versions = emptyList()),
            rule.copy(minVersion = "0.7.0"), rule.copy(expiresAt = "invalid"),
            rule.copy(versions = listOf("latest")), rule.copy(title = mapOf("en" to "Only English")))) {
            assertThrows(RuntimeException::class.java) { AppNotices.parse(encoded(bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppNotices.parse(json.encodeToString(AppNoticeDocument.serializer(), AppNoticeDocument(1, 1, listOf(rule), listOf(rule))))
        }
        assertTrue(AppNotices.parse("""{"schema":1,"revision":1,"announcements":[],"unstableVersions":[]}""").announcements.isEmpty())
    }

    @Test fun nextBoundaryTracksScheduledStartsExpiryEditsAndCacheAge() {
        val start = "2026-11-18T00:00:00Z"
        val scheduled = AppNoticeDocument(1, 1, listOf(rule.copy(startsAt = start)), emptyList())
        val startTime = Instant.parse(start).toEpochMilli()
        assertEquals(startTime, AppNotices.nextChangeAt(scheduled, now, now))
        assertEquals(now + AppNotices.CACHE_MAX_AGE, AppNotices.nextChangeAt(scheduled, now, startTime))
        val end = startTime + 1_000
        val shortened = scheduled.copy(revision = 2, announcements = listOf(rule.copy(expiresAt = Instant.ofEpochMilli(end).toString())))
        assertEquals(end, AppNotices.nextChangeAt(shortened, now, now))
        assertEquals(now + AppNotices.CACHE_MAX_AGE, AppNotices.nextChangeAt(shortened, now, end))
        assertNull(AppNotices.nextChangeAt(shortened, now, now + AppNotices.CACHE_MAX_AGE))
    }

    @Test fun cacheLanguageFallbackAndTransportAreBounded() {
        assertTrue(AppNotices.cacheFresh(now, now + 1))
        assertFalse(AppNotices.cacheFresh(now, now + AppNotices.CACHE_MAX_AGE))
        assertFalse(AppNotices.cacheFresh(now, now - 1))
        assertEquals("人工数据", rule.text(rule.body, "ja"))
        assertEquals("English", rule.text(mapOf("zh-CN" to "中文", "en" to "English"), "ko"))
        assertTrue(UpdateTransport.isCanonicalUrl(AppNoticeStore.URL))
        assertFalse(UpdateTransport.isCanonicalUrl(AppNoticeStore.URL.replace("/main/", "/other/")))
        assertFalse(UpdateTransport.isCanonicalUrl(AppNoticeStore.URL + "?key=private"))
    }
}
