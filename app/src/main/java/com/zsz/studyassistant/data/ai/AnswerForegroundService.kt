package com.zsz.studyassistant.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 生成答案期间的前台服务：让进程保持前台优先级，切到后台也不被系统冻结，
 * 从而保证 AI 请求能跑完（避免切后台一会儿就"网络中断"）。
 */
class AnswerForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15+ gives dataSync services only a few seconds to leave foreground at quota expiry.
    // Requests retain their existing cancellation/recovery ownership; the service only protects them.
    override fun onTimeout(startId: Int, fgsType: Int) {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } finally { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (synchronized(owners) { owners.isEmpty() }) { stopSelf(startId); return START_NOT_STICKY }
        val n = buildNotification(this)
        startForeground(NOTI_ID, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "answer_running"
        private const val NOTI_ID = 2001
        // Completion must outlive destruction of the running foreground service.
        private const val COMPLETED_ID = 2006

        private val owners = mutableSetOf<String>()
        @Volatile private var appForeground = false

        internal fun buildNotification(context: Context, completed: Boolean = false): android.app.Notification {
            val strings = com.zsz.studyassistant.ui.stringsFor(com.zsz.studyassistant.ui.UiLangStore.load(context))
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, strings["notif.answer.channel"], NotificationManager.IMPORTANCE_LOW)
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(if (completed) com.zsz.studyassistant.R.drawable.ic_stat_answer_done else com.zsz.studyassistant.R.drawable.ic_stat_answer)
                .setLargeIcon(NotificationArtwork.application(context))
                .setCategory(if (completed) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_PROGRESS)
                .setColor(0xFF2563EB.toInt())
                // Colorized backgrounds make Android 16 notifications ineligible for promotion.
                .setContentTitle(if (completed) strings["notif.answer.completedShort"] else "Study Assistant")
                .setContentText(notificationText(strings[if (completed) "notif.answer.completedText" else "notif.answer.text"]))
                .setContentIntent(if (completed) NotificationIntents.answerCompleted(context) else NotificationIntents.answerRunning(context))
                .setOngoing(true)
                .setAutoCancel(completed)
                .setRequestPromotedOngoing(!completed)
                .setShortCriticalText(strings[if (completed) "notif.answer.completedShort" else "notif.answer.thinkingShort"])
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        fun start(c: Context, owner: String) {
            synchronized(owners) { owners += owner }
            try {
                ContextCompat.startForegroundService(c, Intent(c, AnswerForegroundService::class.java))
            } catch (_: Exception) { }
        }

        fun setAppForeground(active: Boolean) { appForeground = active }

        fun acknowledgeCompletion(c: Context) {
            c.getSystemService(NotificationManager::class.java)?.cancel(COMPLETED_ID)
        }

        fun stop(c: Context, owner: String, completed: Boolean = false) {
            val empty = synchronized(owners) {
                // A cancelled/replaced request may reach its finally block later.
                if (!owners.remove(owner)) return
                if (completed && !appForeground) {
                    try {
                        c.getSystemService(NotificationManager::class.java)?.notify(COMPLETED_ID, buildNotification(c, completed = true))
                    } catch (_: SecurityException) { /* Notification permission may have been revoked. */ }
                }
                owners.isEmpty()
            }
            if (!empty) return
            try {
                c.stopService(Intent(c, AnswerForegroundService::class.java))
            } catch (_: Exception) { }
        }
    }
}
