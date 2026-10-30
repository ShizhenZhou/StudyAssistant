package com.zsz.studyassistant.data

import androidx.core.app.NotificationCompat
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zsz.studyassistant.ui.UiLangStore
import com.zsz.studyassistant.ui.stringsFor
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadNotificationsIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName == "com.zsz.studyassistant.integration")
    }
    private val rel = UpdateChecker.ReleaseInfo("v9.0", "9.0", "", "fixture.apk",
        "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v9.0/fixture.apk", null, 100, 123, minSdk = 28)
    private fun task(phase: UpdateDownloads.Phase) = UpdateDownloads.Snapshot(
        "11111111-2222-4333-8444-555555555555", rel, phase, 37, appNotification = true)

    @Test fun actualForegroundNotificationIsReadyBeforeHidingNativeAndStopsForNativeFallback() = kotlinx.coroutines.runBlocking {
        if (android.os.Build.VERSION.SDK_INT >= 33) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        var request: UpdateDownloadMonitor.Request? = null
        try {
            androidx.test.core.app.ActivityScenario.launch<com.zsz.studyassistant.MainActivity>(
                android.content.Intent(context, com.zsz.studyassistant.MainActivity::class.java)).use { scenario ->
                scenario.onActivity { request = UpdateDownloadMonitor.request(it) }
                assertNotNull(request)
                assertTrue(kotlinx.coroutines.withTimeout(4000) { request!!.ready.await() })
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                val postedDeadline = android.os.SystemClock.uptimeMillis() + 4000
                while (manager.activeNotifications.none { it.id == UpdateDownloadNotifications.NOTIFICATION_ID } &&
                    android.os.SystemClock.uptimeMillis() < postedDeadline) kotlinx.coroutines.delay(80)
                assertTrue(manager.activeNotifications.any { it.id == UpdateDownloadNotifications.NOTIFICATION_ID })
                request!!.bound(task(UpdateDownloads.Phase.DOWNLOADING).copy(appNotification = false))
                val deadline = android.os.SystemClock.uptimeMillis() + 4000
                while (manager.activeNotifications.any { it.id == UpdateDownloadNotifications.NOTIFICATION_ID } &&
                    android.os.SystemClock.uptimeMillis() < deadline) kotlinx.coroutines.delay(80)
                assertFalse(manager.activeNotifications.any { it.id == UpdateDownloadNotifications.NOTIFICATION_ID })
            }
        } finally {
            request?.aborted()
            context.stopService(android.content.Intent(context, UpdateDownloadMonitor::class.java))
            UpdateDownloadNotifications.clear(context)
        }
    }
    @Test fun runningServicePublishesChangingProgressAfterPageLeaves() = kotlinx.coroutines.runBlocking {
        if (android.os.Build.VERSION.SDK_INT >= 33) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val backend = UpdateDownloadsIntegrationTest.FakeTransfers()
        val downloads = UpdateDownloads(context, backend)
        val original = UpdateDownloadMonitor.downloadsFactory
        var request: UpdateDownloadMonitor.Request? = null
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val hold = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("notificationProbeHoldMs")?.toLongOrNull()?.coerceIn(0, 60_000) ?: 0
        suspend fun awaitProgress(percent: Int) {
            kotlinx.coroutines.withTimeout(6000) {
                while (manager.activeNotifications.none { it.id == UpdateDownloadNotifications.NOTIFICATION_ID &&
                    it.notification.extras.getInt(Notification.EXTRA_PROGRESS, -1) == percent &&
                    NotificationCompat.getShortCriticalText(it.notification) == "$percent%" }) kotlinx.coroutines.delay(100)
            }
            if (hold > 0) kotlinx.coroutines.delay(hold)
        }
        try {
            downloads.cancel()
            UpdateDownloadMonitor.downloadsFactory = { downloads }
            androidx.test.core.app.ActivityScenario.launch<com.zsz.studyassistant.MainActivity>(
                android.content.Intent(context, com.zsz.studyassistant.MainActivity::class.java)).use { scenario ->
                scenario.onActivity { request = UpdateDownloadMonitor.request(it) }
                assertNotNull(request)
                assertTrue(kotlinx.coroutines.withTimeout(4000) { request!!.ready.await() })
                val started = downloads.start(rel, appNotification = true)
                backend.current = backend.current!!.copy(bytes = 1, total = 100)
                request!!.bound(started)
                awaitProgress(1)
                backend.current = backend.current!!.copy(bytes = 37)
                awaitProgress(37)
                val stablePost = manager.activeNotifications.single { it.id == UpdateDownloadNotifications.NOTIFICATION_ID }.postTime
                kotlinx.coroutines.delay(2300)
                assertEquals("Unchanged progress must not repost", stablePost,
                    manager.activeNotifications.single { it.id == UpdateDownloadNotifications.NOTIFICATION_ID }.postTime)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                backend.current = backend.current!!.copy(bytes = 82)
                awaitProgress(82)
            }
        } finally {
            request?.aborted()
            context.stopService(android.content.Intent(context, UpdateDownloadMonitor::class.java))
            UpdateDownloadNotifications.clear(context)
            UpdateDownloadMonitor.downloadsFactory = original
            downloads.cancel()
        }
    }
    @Test fun notificationRefreshIdentityIncludesOwnershipAppearanceAndWaitingState() {
        val base = task(UpdateDownloads.Phase.DOWNLOADING)
        val key = UpdateDownloadNotifications.presentationKey(context, base)
        assertEquals(key, UpdateDownloadNotifications.presentationKey(context, base.copy()))
        for (changed in listOf(base.copy(token = "another-token"), base.copy(progress = 38),
            base.copy(phase = UpdateDownloads.Phase.WAITING), base.copy(errorKey = "update.downloadUnavailable"))) {
            assertNotEquals(key, UpdateDownloadNotifications.presentationKey(context, changed))
        }
        val old = UiLangStore.load(context)
        try {
            UiLangStore.save(context, if (old == com.zsz.studyassistant.ui.UiLang.EN) com.zsz.studyassistant.ui.UiLang.JA else com.zsz.studyassistant.ui.UiLang.EN)
            assertNotEquals(key, UpdateDownloadNotifications.presentationKey(context, base))
        } finally { UiLangStore.save(context, old) }
    }

    @Test fun activeNotificationShowsActualProgressAndRequestsOptionalPromotion() {
        val note = UpdateDownloadNotifications.build(context, task(UpdateDownloads.Phase.DOWNLOADING))
        assertTrue(note.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(37, note.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, note.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(NotificationCompat.isRequestPromotedOngoing(note))
        assertEquals("37%", NotificationCompat.getShortCriticalText(note))
        assertEquals(Notification.VISIBILITY_PRIVATE, note.visibility)
        assertNotNull(note.contentIntent)
        assertEquals(1, note.actions.size)
        assertEquals(stringsFor(UiLangStore.load(context))["update.cancelDownload"], note.actions[0].title.toString())
    }
    @Test fun progressTemplateMatchesSystemAndKeepsUnknownTotalsIndeterminate() {
        for (percent in listOf(-1, 0, 37, 82, 100)) {
            val note = UpdateDownloadNotifications.build(context, task(UpdateDownloads.Phase.DOWNLOADING).copy(progress = percent))
            val style = NotificationCompat.Style.extractStyleFromNotification(note)
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                assertTrue(style is NotificationCompat.ProgressStyle)
                val progress = style as NotificationCompat.ProgressStyle
                assertEquals(percent < 0, progress.isProgressIndeterminate)
                assertEquals(100, progress.progressMax)
                if (percent >= 0) assertEquals(percent, progress.progress)
                assertEquals(note.color, progress.progressSegments.single().color)
                assertEquals("android.app.Notification$" + "ProgressStyle", note.extras.getString(Notification.EXTRA_TEMPLATE))
            } else {
                assertNull(style)
                assertEquals(100, note.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
                assertEquals(percent.coerceAtLeast(0), note.extras.getInt(Notification.EXTRA_PROGRESS))
                assertEquals(percent < 0, note.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            }
            assertEquals(if (percent >= 0) "$percent%" else null, NotificationCompat.getShortCriticalText(note))
        }
    }
    @Test fun waitingKeepsProgressWithoutClaimingActiveCapsule() {
        val note = UpdateDownloadNotifications.build(context, task(UpdateDownloads.Phase.WAITING))
        assertTrue(note.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(NotificationCompat.isRequestPromotedOngoing(note))
        assertEquals(37, note.extras.getInt(Notification.EXTRA_PROGRESS))
    }
    @Test fun notificationActionCancelsTheSameBackendAsProgressMonitoring() = kotlinx.coroutines.runBlocking {
        val backend = UpdateDownloadsIntegrationTest.FakeTransfers()
        val downloads = UpdateDownloads(context, backend)
        val original = UpdateDownloadMonitor.downloadsFactory
        try {
            downloads.cancel()
            UpdateDownloadMonitor.downloadsFactory = { downloads }
            val started = downloads.start(rel, appNotification = true)
            val note = UpdateDownloadNotifications.build(context, started)
            note.actions.single().actionIntent.send()
            kotlinx.coroutines.withTimeout(5000) {
                while (downloads.snapshot() != null) kotlinx.coroutines.delay(50)
            }
            assertNull(backend.current)
        } finally {
            UpdateDownloadMonitor.downloadsFactory = original
            downloads.cancel()
        }
    }
    @Test fun completionChipHasTextInsteadOfProgressAndThenBecomesDismissible() {
        val done = stringsFor(UiLangStore.load(context))["update.notif.completedShort"]
        val ready = task(UpdateDownloads.Phase.READY).copy(progress = 100)
        val briefly = UpdateDownloadNotifications.build(context, ready, completionChip = true)
        val modern = android.os.Build.VERSION.SDK_INT >= 36
        assertEquals(done, NotificationCompat.getShortCriticalText(briefly))
        assertFalse(NotificationCompat.getShortCriticalText(briefly).toString().contains("%"))
        assertEquals(modern, NotificationCompat.isRequestPromotedOngoing(briefly))
        assertEquals(modern, briefly.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNull(NotificationCompat.Style.extractStyleFromNotification(briefly))
        assertEquals(0, briefly.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(briefly.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(briefly.actions.isNullOrEmpty())
        val finalNote = UpdateDownloadNotifications.build(context, ready)
        assertEquals(done, NotificationCompat.getShortCriticalText(finalNote))
        assertEquals(0, finalNote.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(finalNote.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertFalse(NotificationCompat.isRequestPromotedOngoing(finalNote))
        assertNotNull(finalNote.contentIntent)
        val failed = UpdateDownloadNotifications.build(context, task(UpdateDownloads.Phase.FAILED), completionChip = true)
        assertNull(NotificationCompat.getShortCriticalText(failed))
        assertFalse(NotificationCompat.isRequestPromotedOngoing(failed))
    }
    @Test fun serviceCompletionBecomesTextAndLeavesADismissibleNotice() = kotlinx.coroutines.runBlocking {
        if (android.os.Build.VERSION.SDK_INT >= 33) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val backend = UpdateDownloadsIntegrationTest.FakeTransfers()
        val downloads = UpdateDownloads(context, backend) { _, _ -> UpdateChecker.VerifyResult(true, true, true, null) }
        val original = UpdateDownloadMonitor.downloadsFactory
        var request: UpdateDownloadMonitor.Request? = null
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val done = stringsFor(UiLangStore.load(context))["update.notif.completedShort"]
        suspend fun <T> awaitStage(name: String, timeout: Long = 8000, block: suspend () -> T): T = try {
            kotlinx.coroutines.withTimeout(timeout) { block() }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val notices = manager.activeNotifications.filter { it.id in setOf(UpdateDownloadNotifications.NOTIFICATION_ID, UpdateDownloadNotifications.TERMINAL_ID) }
                .map { "flags=${it.notification.flags}, progress=${it.notification.extras.getInt(Notification.EXTRA_PROGRESS, -1)}, short=${NotificationCompat.getShortCriticalText(it.notification)}" }
            throw AssertionError("Completion stage $name timed out; notices=$notices", error)
        }
        suspend fun awaitNotice(ongoing: Boolean, timeout: Long = 8000): Notification = awaitStage("completion ongoing=$ongoing", timeout) {
            var result: Notification? = null
            while (result == null) {
                result = manager.activeNotifications.firstOrNull {
                    it.id == (if (ongoing) UpdateDownloadNotifications.NOTIFICATION_ID else UpdateDownloadNotifications.TERMINAL_ID) &&
                        NotificationCompat.getShortCriticalText(it.notification) == done &&
                        (it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) == ongoing &&
                        (ongoing || manager.activeNotifications.none { old -> old.id == UpdateDownloadNotifications.NOTIFICATION_ID })
                }?.notification
                if (result == null) kotlinx.coroutines.delay(50)
            }
            result
        }
        try {
            downloads.cancel()
            UpdateDownloadMonitor.downloadsFactory = { downloads }
            androidx.test.core.app.ActivityScenario.launch<com.zsz.studyassistant.MainActivity>(
                android.content.Intent(context, com.zsz.studyassistant.MainActivity::class.java)).use { scenario ->
                scenario.onActivity { request = UpdateDownloadMonitor.request(it) }
                assertTrue(kotlinx.coroutines.withTimeout(4000) { request!!.ready.await() })
                val started = downloads.start(rel.copy(assetSize = 7), appNotification = true)
                request!!.bound(started)
                // Establish the running notification before testing its transition to completion.
                awaitStage("running 42%") {
                    while (manager.activeNotifications.none { it.id == UpdateDownloadNotifications.NOTIFICATION_ID &&
                        it.notification.extras.getInt(Notification.EXTRA_PROGRESS, -1) == 42 }) kotlinx.coroutines.delay(50)
                }
                backend.file!!.writeText("fixture")
                backend.current = backend.current!!.copy(status = UpdateDownloads.TransferPhase.SUCCESS, bytes = 7)
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    val briefly = awaitNotice(true)
                    assertTrue(NotificationCompat.isRequestPromotedOngoing(briefly))
                    assertTrue(briefly.actions.isNullOrEmpty())
                    // Exceed the old three-second deadline, then replace the completed task.
                    kotlinx.coroutines.delay(4000)
                    awaitNotice(true)
                    scenario.onActivity { request = UpdateDownloadMonitor.request(it) }
                    assertTrue(kotlinx.coroutines.withTimeout(4000) { request!!.ready.await() })
                    downloads.cancel()
                    val replacement = downloads.start(rel.copy(assetSize = 7), appNotification = true)
                    assertNotEquals(started.token, replacement.token)
                    request!!.bound(replacement)
                    awaitStage("new request interrupts retained completion") {
                        while (manager.activeNotifications.none { it.id == UpdateDownloadNotifications.NOTIFICATION_ID &&
                            it.notification.extras.getInt(Notification.EXTRA_PROGRESS, -1) == 42 }) kotlinx.coroutines.delay(50)
                    }
                    backend.file!!.writeText("fixture")
                    backend.current = backend.current!!.copy(status = UpdateDownloads.TransferPhase.SUCCESS, bytes = 7)
                    awaitNotice(true)
                }
                val completionSeenAt = android.os.SystemClock.elapsedRealtime()
                val settled = awaitNotice(false, timeout = UpdateDownloadMonitor.COMPLETION_CHIP_MS + 8000)
                assertEquals(120_000L, UpdateDownloadMonitor.COMPLETION_CHIP_MS)
                assertTrue("Completion should settle after its two-minute window",
                    android.os.SystemClock.elapsedRealtime() - completionSeenAt in 117_000L..128_000L)
                assertTrue(settled.flags and Notification.FLAG_AUTO_CANCEL != 0)
                assertFalse(NotificationCompat.isRequestPromotedOngoing(settled))
                assertEquals(0, settled.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
                assertFalse(settled.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
                assertNotNull(settled.contentIntent)
                // Android may add its own retention TTL to the posted copy; the app request is checked below.
                kotlinx.coroutines.delay(1500)
                assertTrue(manager.activeNotifications.any { it.id == UpdateDownloadNotifications.TERMINAL_ID })
                // A new request removes the preceding ordinary completion notice.
                scenario.onActivity { request = UpdateDownloadMonitor.request(it) }
                assertTrue(kotlinx.coroutines.withTimeout(4000) { request!!.ready.await() })
                awaitStage("new request clears previous completion") {
                    while (manager.activeNotifications.any { it.id == UpdateDownloadNotifications.TERMINAL_ID }) kotlinx.coroutines.delay(50)
                }
            }
        } finally {
            request?.aborted()
            context.stopService(android.content.Intent(context, UpdateDownloadMonitor::class.java))
            UpdateDownloadNotifications.clear(context)
            UpdateDownloadMonitor.downloadsFactory = original
            downloads.cancel()
        }
    }
    @Test fun terminalStatesBecomeDismissibleAndHaveNoCancelAction() {
        for (phase in listOf(UpdateDownloads.Phase.READY, UpdateDownloads.Phase.FAILED)) {
            val note = UpdateDownloadNotifications.build(context, task(phase))
            assertEquals(0, note.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(note.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertFalse(NotificationCompat.isRequestPromotedOngoing(note))
            assertTrue(note.actions.isNullOrEmpty())
            assertNull(NotificationCompat.Style.extractStyleFromNotification(note))
            assertNotNull(note.contentIntent)
            assertEquals("App must not request automatic expiry for ordinary notices", 0L, note.timeoutAfter)
        }
    }
    @Test fun pendingNotificationNeverInventsProgressOrOffersUnboundCancellation() {
        val note = UpdateDownloadNotifications.build(context, null)
        assertTrue(note.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertFalse(NotificationCompat.isRequestPromotedOngoing(note))
        assertTrue(note.actions.isNullOrEmpty())
    }
    @Test fun answerNotificationRequestsPromotionWithLocalizedTextAndAppMark() {
        val previous = UiLangStore.load(context)
        try {
            for (lang in listOf(com.zsz.studyassistant.ui.UiLang.ZH_CN, com.zsz.studyassistant.ui.UiLang.EN,
                com.zsz.studyassistant.ui.UiLang.JA, com.zsz.studyassistant.ui.UiLang.KO)) {
                UiLangStore.save(context, lang)
                val note = AnswerForegroundService.buildNotification(context)
                assertTrue(NotificationCompat.isRequestPromotedOngoing(note))
                assertEquals(stringsFor(lang)["notif.answer.thinkingShort"], NotificationCompat.getShortCriticalText(note))
                assertTrue(note.flags and Notification.FLAG_ONGOING_EVENT != 0)
                assertFalse(note.extras.getBoolean(Notification.EXTRA_COLORIZED))
                assertEquals(0xFF2563EB.toInt(), note.color)
                assertEquals(com.zsz.studyassistant.R.drawable.ic_stat_answer, note.smallIcon.resId)
                assertNotNull(note.getLargeIcon())
                assertEquals(NotificationIntents.answerRunning(context), note.contentIntent)
            }
        } finally { UiLangStore.save(context, previous) }
    }

    @Test fun completionPendingIntentsKeepOldTokensAndDoNotAcknowledgeOrdinaryPageOpens() {
        val first = java.util.UUID.randomUUID().toString()
        val second = java.util.UUID.randomUUID().toString()
        val firstClick = NotificationIntents.update(context, first, completed = true)
        val secondClick = NotificationIntents.update(context, second, completed = true)
        assertNotEquals(firstClick, secondClick)
        assertNotEquals(firstClick, NotificationIntents.update(context, first, completed = false))
        assertEquals(firstClick, NotificationIntents.update(context, first, completed = true))
        val ordinary = android.content.Intent().putExtra(com.zsz.studyassistant.ui.AppNav.OPEN_UPDATE_EXTRA, true)
        NotificationIntents.consumeUpdateCompletion(context, ordinary)
        assertFalse(UpdateDownloadNotifications.isAcknowledged(context, first))
        assertFalse(UpdateDownloadNotifications.isAcknowledged(context, second))
    }

    @Test fun coldAndWarmActivityEntriesAcknowledgeOnlyTheirCompletionTokens() = kotlinx.coroutines.runBlocking {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val first = java.util.UUID.randomUUID().toString()
        val second = java.util.UUID.randomUUID().toString()
        fun entry(token: String) = android.content.Intent(context, com.zsz.studyassistant.MainActivity::class.java)
            .putExtra(com.zsz.studyassistant.ui.AppNav.OPEN_UPDATE_EXTRA, true)
            .putExtra(NotificationIntents.UPDATE_COMPLETION_TOKEN_EXTRA, token)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        suspend fun awaitCleared() = kotlinx.coroutines.withTimeout(4000) {
            while (manager.activeNotifications.any { it.id == UpdateDownloadNotifications.TERMINAL_ID }) kotlinx.coroutines.delay(50)
        }
        try {
            UpdateDownloadNotifications.postTerminal(context, UpdateDownloadNotifications.build(context, task(UpdateDownloads.Phase.READY).copy(token = first)))
            androidx.test.core.app.ActivityScenario.launch<com.zsz.studyassistant.MainActivity>(entry(first)).use { scenario ->
                scenario.onActivity { assertFalse(it.intent.hasExtra(NotificationIntents.UPDATE_COMPLETION_TOKEN_EXTRA)) }
                assertTrue(UpdateDownloadNotifications.isAcknowledged(context, first))
                awaitCleared()
                UpdateDownloadNotifications.postTerminal(context, UpdateDownloadNotifications.build(context, task(UpdateDownloads.Phase.READY).copy(token = second)))
                // Deliver through Android so the existing Activity receives its protected onNewIntent callback.
                scenario.onActivity { it.startActivity(entry(second)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                kotlinx.coroutines.withTimeout(4000) {
                    while (!UpdateDownloadNotifications.isAcknowledged(context, second)) kotlinx.coroutines.delay(50)
                }
                scenario.onActivity { assertFalse(it.intent.hasExtra(NotificationIntents.UPDATE_COMPLETION_TOKEN_EXTRA)) }
                awaitCleared()
            }
        } finally { UpdateDownloadNotifications.clear(context) }
    }

    @Test fun acknowledgedCompletionStopsMonitorWithoutChangingReadyAndOldTokenCannotClearReplacement() = kotlinx.coroutines.runBlocking {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val backend = UpdateDownloadsIntegrationTest.FakeTransfers()
        val downloads = UpdateDownloads(context, backend) { _, _ -> UpdateChecker.VerifyResult(true, true, true, null) }
        val original = UpdateDownloadMonitor.downloadsFactory
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        var request: UpdateDownloadMonitor.Request? = null
        suspend fun awaitNote(token: String, ready: Boolean) = kotlinx.coroutines.withTimeout(7000) {
            while (manager.activeNotifications.none { it.id == UpdateDownloadNotifications.NOTIFICATION_ID &&
                it.notification.extras.getString(UpdateDownloadNotifications.TOKEN_EXTRA) == token &&
                (NotificationCompat.getShortCriticalText(it.notification) == stringsFor(UiLangStore.load(context))["update.notif.completedShort"]) == ready }) kotlinx.coroutines.delay(50)
        }
        try {
            downloads.cancel()
            UpdateDownloadMonitor.downloadsFactory = { downloads }
            androidx.test.core.app.ActivityScenario.launch<com.zsz.studyassistant.MainActivity>(
                android.content.Intent(context, com.zsz.studyassistant.MainActivity::class.java)).use { scenario ->
                scenario.onActivity { request = UpdateDownloadMonitor.request(it) }
                assertTrue(kotlinx.coroutines.withTimeout(4000) { request!!.ready.await() })
                val started = downloads.start(rel.copy(assetSize = 7), appNotification = true)
                request!!.bound(started)
                awaitNote(started.token, false)
                backend.file!!.writeText("fixture")
                backend.current = backend.current!!.copy(status = UpdateDownloads.TransferPhase.SUCCESS, bytes = 7)
                awaitNote(started.token, true)
                scenario.onActivity { UpdateDownloadNotifications.acknowledgeCompletion(it, started.token) }
                kotlinx.coroutines.withTimeout(4000) {
                    while (manager.activeNotifications.any { it.id in setOf(UpdateDownloadNotifications.NOTIFICATION_ID, UpdateDownloadNotifications.TERMINAL_ID) }) kotlinx.coroutines.delay(50)
                }
                kotlinx.coroutines.delay(2300) // The former completion monitor must not repost on its next poll.
                assertTrue(manager.activeNotifications.none { it.id in setOf(UpdateDownloadNotifications.NOTIFICATION_ID, UpdateDownloadNotifications.TERMINAL_ID) })
                val ready = downloads.snapshot()!!
                assertEquals(UpdateDownloads.Phase.READY, ready.phase)
                assertTrue(ready.file!!.exists())
                assertFalse(UpdateDownloadNotifications.post(context, UpdateDownloadNotifications.build(context, ready, completionChip = true)))
                UpdateDownloadNotifications.postTerminal(context, UpdateDownloadNotifications.build(context, ready))
                assertTrue(manager.activeNotifications.none { it.id == UpdateDownloadNotifications.TERMINAL_ID })
                // A new fixture transfer owns a different UUID and remains protected from stale clicks.
                scenario.onActivity { request = UpdateDownloadMonitor.request(it) }
                assertTrue(kotlinx.coroutines.withTimeout(4000) { request!!.ready.await() })
                downloads.cancel()
                val replacement = downloads.start(rel.copy(assetSize = 7), appNotification = true)
                request!!.bound(replacement)
                awaitNote(replacement.token, false)
                scenario.onActivity { UpdateDownloadNotifications.acknowledgeCompletion(it, started.token) }
                awaitNote(replacement.token, false)
                assertFalse(UpdateDownloadNotifications.isAcknowledged(context, replacement.token))
                assertEquals(replacement.token, downloads.snapshot()!!.token)
            }
        } finally {
            request?.aborted()
            context.stopService(android.content.Intent(context, UpdateDownloadMonitor::class.java))
            UpdateDownloadNotifications.clear(context)
            UpdateDownloadMonitor.downloadsFactory = original
            downloads.cancel()
        }
    }

}
