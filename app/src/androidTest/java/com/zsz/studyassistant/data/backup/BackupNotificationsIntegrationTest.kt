package com.zsz.studyassistant.data

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zsz.studyassistant.ui.UiLangStore
import com.zsz.studyassistant.ui.stringsFor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupNotificationsIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName == "com.zsz.studyassistant.integration")
    }
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun alert() = manager.activeNotifications.firstOrNull { it.id == BackupNotifications.NOTIFICATION_ID }
    private fun waitUntilCleared() {
        val deadline = android.os.SystemClock.uptimeMillis() + 3000
        while (alert() != null && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        assertNull(alert())
    }
    @Before fun prepare() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        BackupNotifications.clear(context)
        waitUntilCleared()
    }
    @After fun cleanup() { BackupJobs.setForeground(false); BackupNotifications.clear(context); waitUntilCleared() }

    @Test fun backgroundAutomaticFailurePostsOneLocalAlertAndSuccessClearsIt() {
        BackupJobs.setForeground(false)
        BackupJobs.notifyFailure(context, automatic = true)
        val deadline = android.os.SystemClock.uptimeMillis() + 3000
        while (alert() == null && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        val notification = alert()?.notification ?: error("No backup failure notification")
        assertEquals(stringsFor(UiLangStore.load(context))["data.backupNotifyFailed"], notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotNull(notification.contentIntent)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(BackupNotifications.CHANNEL_ID, notification.channelId)
        BackupJobs.notifyFailure(context, automatic = true)
        assertEquals(1, manager.activeNotifications.count { it.id == BackupNotifications.NOTIFICATION_ID })
        BackupNotifications.clear(context)
        waitUntilCleared()
    }

    @Test fun foregroundOrManualFailureDoesNotPostAutomaticAlert() {
        BackupJobs.setForeground(true)
        BackupJobs.notifyFailure(context, automatic = true)
        assertNull(alert())
        BackupJobs.setForeground(false)
        BackupJobs.notifyFailure(context, automatic = false)
        assertNull(alert())
    }
}
