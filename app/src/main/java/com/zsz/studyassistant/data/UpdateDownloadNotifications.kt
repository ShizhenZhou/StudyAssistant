package com.zsz.studyassistant.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.zsz.studyassistant.MainActivity
import com.zsz.studyassistant.ui.AppNav
import com.zsz.studyassistant.ui.AppThemeColor
import com.zsz.studyassistant.ui.appColorScheme
import com.zsz.studyassistant.ui.UiLangStore
import com.zsz.studyassistant.ui.stringsFor

/** Standard Android notification contracts only; OEMs decide whether promotion becomes a capsule. */
internal object UpdateDownloadNotifications {
    const val CHANNEL_ID = "update_download_progress"
    const val NOTIFICATION_ID = 2003
    const val CANCEL_ACTION = "com.zsz.studyassistant.CANCEL_UPDATE_DOWNLOAD"
    const val TOKEN_EXTRA = "update_task_token"

    fun ensureChannel(context: Context) {
        val strings = stringsFor(UiLangStore.load(context))
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings["update.notif.channel"], NotificationManager.IMPORTANCE_LOW))
    }
    fun canPost(context: Context): Boolean = try {
        ensureChannel(context)
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    } catch (_: Exception) { false }
    fun canReplaceNative(context: Context): Boolean = canPost(context) &&
        ContextCompat.checkSelfPermission(context, "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION") == PackageManager.PERMISSION_GRANTED

    private fun accentColor(context: Context): Int {
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val dark = when (settings.getString("theme", "system")) {
            "light" -> false
            "dark" -> true
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val scheme = appColorScheme(dark, AppThemeColor.fromId(settings.getString("theme_color", null)),
            settings.getInt("theme_color_custom", 0xFF6750A4.toInt()))
        return ColorUtils.setAlphaComponent(scheme.primary.toArgb(), 255)
    }

    fun build(context: Context, task: UpdateDownloads.Snapshot?, messageKey: String? = null): Notification {
        ensureChannel(context)
        val strings = stringsFor(UiLangStore.load(context))
        val ongoing = messageKey == null && (task == null || task.phase == UpdateDownloads.Phase.DOWNLOADING || task.phase == UpdateDownloads.Phase.WAITING)
        val promote = ongoing && task?.phase == UpdateDownloads.Phase.DOWNLOADING
        val percent = task?.progress ?: -1
        val title = when {
            messageKey != null || task == null -> strings["update.notif.genericTitle"]
            task.phase == UpdateDownloads.Phase.READY -> strings["update.notif.readyTitle"]
            task.phase == UpdateDownloads.Phase.FAILED -> strings["update.notif.failureTitle"]
            else -> strings.format("update.notif.title", "v" to task.release.version)
        }
        val text = when {
            messageKey != null -> strings[messageKey]
            task == null -> strings["update.notif.preparing"]
            task.errorKey == "update.downloadUnavailable" -> strings["update.notif.observing"]
            task.phase == UpdateDownloads.Phase.WAITING -> strings["update.notif.waiting"]
            task.phase == UpdateDownloads.Phase.READY -> strings["update.notif.ready"]
            task.phase == UpdateDownloads.Phase.FAILED -> strings["update.notif.failed"]
            percent >= 0 -> strings.format("update.notif.progress", "p" to "$percent")
            else -> strings["update.notif.preparing"]
        }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AppNav.OPEN_UPDATE_EXTRA, true)
        val note = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(accentColor(context))
            .setContentTitle(title).setContentText(text)
            .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(promote)
            .setShortCriticalText(if (promote && percent >= 0) "$percent%" else null)
        if (ongoing) note.setProgress(100, percent.coerceAtLeast(0), percent < 0)
        if (ongoing && task != null) {
            val cancel = Intent(context, UpdateDownloadActionReceiver::class.java)
                .setAction(CANCEL_ACTION).setData(Uri.parse("studyassistant-update-cancel:${task.token}"))
                .putExtra(TOKEN_EXTRA, task.token)
            note.addAction(android.R.drawable.ic_menu_close_clear_cancel, strings["update.cancelDownload"],
                PendingIntent.getBroadcast(context, NOTIFICATION_ID + 1, cancel,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return note.build()
    }
    fun post(context: Context, note: Notification) {
        if (canPost(context)) runCatching { context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, note) }
    }
    fun clear(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
