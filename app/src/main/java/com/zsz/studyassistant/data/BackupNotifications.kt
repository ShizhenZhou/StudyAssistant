package com.zsz.studyassistant.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zsz.studyassistant.MainActivity
import com.zsz.studyassistant.ui.AppNav
import com.zsz.studyassistant.ui.UiLangStore
import com.zsz.studyassistant.ui.stringsFor

/** Failure alerts are local and contain no question, file name, key or exception text. */
object BackupNotifications {
    const val CHANNEL_ID = "backup_failures"
    const val NOTIFICATION_ID = 1003

    fun ensureChannel(context: Context) {
        val strings = stringsFor(UiLangStore.load(context))
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings["data.backupNotifyChannel"], NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun needsPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean {
        if (needsPermission(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun failure(context: Context) {
        // Notification denial never turns a recorded backup failure into a new worker exception.
        try {
            ensureChannel(context)
            if (!canNotify(context)) return
            val strings = stringsFor(UiLangStore.load(context))
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AppNav.OPEN_BACKUP_EXTRA, true)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(strings["data.backupNotifyFailed"])
                .setContentText(strings["data.backupNotifyBody"])
                .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true).setAutoCancel(true).build()
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) { /* The persisted backup status remains available in the app. */ }
    }

    fun clear(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}
