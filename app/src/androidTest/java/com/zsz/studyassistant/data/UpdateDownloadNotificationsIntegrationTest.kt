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
        "https://github.com/ShizhenZhou/StudyAssistant/releases/download/v9.0/fixture.apk", null, 100, 123)
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
    @Test fun waitingKeepsProgressWithoutClaimingActiveCapsule() {
        val note = UpdateDownloadNotifications.build(context, task(UpdateDownloads.Phase.WAITING))
        assertTrue(note.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(NotificationCompat.isRequestPromotedOngoing(note))
        assertEquals(37, note.extras.getInt(Notification.EXTRA_PROGRESS))
    }
    @Test fun terminalStatesBecomeDismissibleAndHaveNoCancelAction() {
        for (phase in listOf(UpdateDownloads.Phase.READY, UpdateDownloads.Phase.FAILED)) {
            val note = UpdateDownloadNotifications.build(context, task(phase))
            assertEquals(0, note.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(note.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertFalse(NotificationCompat.isRequestPromotedOngoing(note))
            assertTrue(note.actions.isNullOrEmpty())
            assertNotNull(note.contentIntent)
        }
    }
    @Test fun pendingNotificationNeverInventsProgressOrOffersUnboundCancellation() {
        val note = UpdateDownloadNotifications.build(context, null)
        assertTrue(note.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertFalse(NotificationCompat.isRequestPromotedOngoing(note))
        assertTrue(note.actions.isNullOrEmpty())
    }
}
