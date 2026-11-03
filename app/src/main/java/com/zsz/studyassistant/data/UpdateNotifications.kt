package com.zsz.studyassistant.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zsz.studyassistant.ui.UiLangStore
import com.zsz.studyassistant.ui.stringsFor
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Optional stable-release alerts. No push service, APK download or exact alarm. */
object UpdateNotifications {
    const val CHANNEL_ID = "update_available"
    private const val NOTIFICATION_ID = 1004
    private const val WORK_NAME = "stable-update-notification"
    private const val ENABLED = "update_notifications_enabled"
    private const val LAST_NOTIFIED = "update_last_notified_build"

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, true)

    fun ensureChannel(context: Context) {
        val s = stringsFor(UiLangStore.load(context))
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, s["notify.update.enable"], NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun needsPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean = !needsPermission(context) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL_ID)?.importance
            ?.let { it != NotificationManager.IMPORTANCE_NONE } == true

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
        applySchedule(context)
    }

    fun applySchedule(context: Context) {
        ensureChannel(context)
        val work = WorkManager.getInstance(context)
        if (!isEnabled(context)) {
            work.cancelUniqueWork(WORK_NAME)
            clear(context)
            return
        }
        // KEEP preserves the cadence across Activity recreation and subsequent launches.
        // Initial delay avoids repeating the existing foreground startup check immediately.
        val request = PeriodicWorkRequestBuilder<UpdateNotificationWorker>(6, TimeUnit.HOURS)
            .setInitialDelay(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun candidateKey(release: UpdateChecker.ReleaseInfo, eligible: Boolean, lastNotified: String?): String? {
        if (release.channel != UpdateChecker.Channel.STABLE || !eligible) return null
        val key = "${release.tagName}|${release.assetUrl}|${release.assetSha256.orEmpty()}"
        return key.takeUnless { it == lastNotified }
    }

    @Synchronized
    fun postIfNeeded(context: Context, release: UpdateChecker.ReleaseInfo) {
        // Recheck after the network request: the user may have disabled, skipped or installed it.
        if (!isEnabled(context) || !canNotify(context)) return
        val eligible = release.channel == UpdateChecker.Channel.STABLE && UpdateChecker.shouldNotify(context, release)
        if (!eligible) { clear(context); return }
        val preferences = prefs(context)
        val key = candidateKey(release, eligible, preferences.getString(LAST_NOTIFIED, null)) ?: return
        val s = stringsFor(UiLangStore.load(context))
        val note = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.zsz.studyassistant.R.drawable.ic_stat_update)
            .setContentTitle(s.format("notify.update.title", "version" to release.displayVersion))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentText(notificationText(s["notify.update.body"]))
            .setContentIntent(NotificationIntents.updateAvailable(context))
            .setAutoCancel(true).setOnlyAlertOnce(true).build()
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, note)
            preferences.edit().putString(LAST_NOTIFIED, key).apply()
        } catch (_: SecurityException) { /* Permission can be revoked while building the notification. */ }
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

class UpdateNotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        UpdateNotifications.ensureChannel(context)
        if (!UpdateNotifications.isEnabled(context) || !UpdateNotifications.canNotify(context)) return Result.success()
        // Only fetch the official stable endpoint, never the optional beta feed.
        val release = UpdateChecker.fetchLatest() ?: return Result.success()
        coroutineContext.ensureActive()
        UpdateChecker.markChecked(context)
        UpdateNotifications.postIfNeeded(context, release)
        // Unavailable networks are handled at the next period, without a retry loop.
        return Result.success()
    }
}
