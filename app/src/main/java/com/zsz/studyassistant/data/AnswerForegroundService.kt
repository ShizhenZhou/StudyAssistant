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

        private val owners = mutableSetOf<String>()

        internal fun buildNotification(context: Context): android.app.Notification {
            val strings = com.zsz.studyassistant.ui.stringsFor(com.zsz.studyassistant.ui.UiLangStore.load(context))
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, strings["notif.answer.channel"], NotificationManager.IMPORTANCE_LOW)
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.zsz.studyassistant.R.drawable.ic_stat_answer)
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(context.resources, com.zsz.studyassistant.R.mipmap.ic_launcher))
                .setColor(0xFF2563EB.toInt())
                // Colorized backgrounds make Android 16 notifications ineligible for promotion.
                .setContentTitle("Study Assistant")
                .setContentText(strings["notif.answer.text"])
                .setContentIntent(NotificationIntents.answerRunning(context))
                .setOngoing(true)
                .setRequestPromotedOngoing(true)
                .setShortCriticalText(strings["notif.answer.thinkingShort"])
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

        fun stop(c: Context, owner: String) {
            val empty = synchronized(owners) { owners.remove(owner); owners.isEmpty() }
            if (!empty) return
            try {
                c.stopService(Intent(c, AnswerForegroundService::class.java))
            } catch (_: Exception) { }
        }
    }
}
