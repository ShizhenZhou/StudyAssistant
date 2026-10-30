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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.zsz.studyassistant.ui.AppThemeColor
import com.zsz.studyassistant.ui.appColorScheme
import com.zsz.studyassistant.ui.UiLangStore
import com.zsz.studyassistant.ui.stringsFor

/** Standard Android notification contracts only; OEMs decide whether promotion becomes a capsule. */
internal object UpdateDownloadNotifications {
    const val CHANNEL_ID = "update_download_progress"
    const val NOTIFICATION_ID = 2003
    const val TERMINAL_ID = 2005
    const val CANCEL_ACTION = "com.zsz.studyassistant.CANCEL_UPDATE_DOWNLOAD"
    const val TOKEN_EXTRA = "update_task_token"

    fun ensureChannel(context: Context) {
        val strings = stringsFor(UiLangStore.load(context))
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings["update.notif.channel"], NotificationManager.IMPORTANCE_LOW))
    }
    fun canPost(context: Context): Boolean = try {
        ensureChannel(context)
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
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

    internal data class PresentationKey(
        val token: String, val phase: UpdateDownloads.Phase, val progress: Int,
        val error: String?, val version: String, val language: String, val locales: String, val color: Int
    )
    internal fun presentationKey(context: Context, task: UpdateDownloads.Snapshot) = PresentationKey(
        task.token, task.phase, task.progress, task.errorKey, task.release.version,
        UiLangStore.load(context).name, context.resources.configuration.locales.toLanguageTags(), accentColor(context)
    )

    fun build(context: Context, task: UpdateDownloads.Snapshot?, messageKey: String? = null, completionChip: Boolean = false): Notification {
        ensureChannel(context)
        val strings = stringsFor(UiLangStore.load(context))
        val ongoing = messageKey == null && (task == null || task.phase == UpdateDownloads.Phase.DOWNLOADING || task.phase == UpdateDownloads.Phase.WAITING)
        val completed = messageKey == null && task?.phase == UpdateDownloads.Phase.READY
        val showCompletion = completed && completionChip
        val promote = (ongoing && task?.phase == UpdateDownloads.Phase.DOWNLOADING) || showCompletion
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
        val color = accentColor(context)
        val note = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(color)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(NotificationIntents.update(context, task?.token, completed))
            .addExtras(android.os.Bundle().apply { putString(TOKEN_EXTRA, task?.token) })
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(ongoing || showCompletion).setAutoCancel(!ongoing && !showCompletion).setOnlyAlertOnce(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(promote)
            .setShortCriticalText(when {
                completed -> strings["update.notif.completedShort"]
                promote && percent >= 0 -> "$percent%"
                else -> null
            })
        if (ongoing) {
            note.setStyle(NotificationCompat.ProgressStyle()
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(100).setColor(color))
                .setProgress(percent.coerceAtLeast(0))
                .setProgressIndeterminate(percent < 0))
        }
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
    @Synchronized fun post(context: Context, note: Notification): Boolean {
        if (isAcknowledged(context, note.extras.getString(TOKEN_EXTRA)) || !canPost(context)) return false
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            manager.notify(NOTIFICATION_ID, note)
            true
        }.getOrDefault(false)
    }
    // Use a different identity from the FGS notification: its asynchronous removal must
    // never restore ongoing flags on the final, dismissible completion notice.
    @Synchronized fun postTerminal(context: Context, note: Notification) {
        if (!isAcknowledged(context, note.extras.getString(TOKEN_EXTRA)) && canPost(context)) runCatching { context.getSystemService(NotificationManager::class.java)?.notify(TERMINAL_ID, note) }
    }
    private const val NOTICE_PREFS = "update_download_notices"
    private const val ACKNOWLEDGED_TOKENS = "acknowledged_tokens"

    @Synchronized internal fun isAcknowledged(context: Context, token: String?): Boolean = token != null &&
        context.getSharedPreferences(NOTICE_PREFS, Context.MODE_PRIVATE)
            .getString(ACKNOWLEDGED_TOKENS, "").orEmpty().split(',').contains(token)

    /** Notice-only metadata: never changes the transfer, verified APK or READY install state. */
    @Synchronized fun acknowledgeCompletion(context: Context, token: String) {
        if (runCatching { java.util.UUID.fromString(token).toString() != token }.getOrDefault(true)) return
        val prefs = context.getSharedPreferences(NOTICE_PREFS, Context.MODE_PRIVATE)
        val tokens = prefs.getString(ACKNOWLEDGED_TOKENS, "").orEmpty().split(',')
            .filter { it.isNotEmpty() && it != token }.takeLast(15) + token
        prefs.edit().putString(ACKNOWLEDGED_TOKENS, tokens.joinToString(",")).apply()
        UpdateDownloadMonitor.dismissAcknowledgedCompletion(token)
        clearToken(context, token)
    }

    internal fun clearToken(context: Context, token: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.activeNotifications.filter { it.id in setOf(NOTIFICATION_ID, TERMINAL_ID) &&
                it.notification.extras.getString(TOKEN_EXTRA) == token }.forEach { manager.cancel(it.id) }
        }
    }

    fun clearTerminal(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(TERMINAL_ID) }
    }
    fun clear(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        clearTerminal(context)
    }
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
