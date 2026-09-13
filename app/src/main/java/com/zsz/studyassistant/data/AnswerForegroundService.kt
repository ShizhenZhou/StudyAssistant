package com.zsz.studyassistant.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zsz.studyassistant.MainActivity

/**
 * 生成答案期间的前台服务：让进程保持前台优先级，切到后台也不被系统冻结，
 * 从而保证 AI 请求能跑完（避免切后台一会儿就"网络中断"）。
 */
class AnswerForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = com.zsz.studyassistant.ui.stringsFor(com.zsz.studyassistant.ui.UiLangStore.load(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, s["notif.answer.channel"], NotificationManager.IMPORTANCE_LOW)
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Study Assistant")
            .setContentText(s["notif.answer.text"])
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTI_ID, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "answer_running"
        private const val NOTI_ID = 2001

        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, AnswerForegroundService::class.java))
            } catch (_: Exception) { }
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, AnswerForegroundService::class.java))
            } catch (_: Exception) { }
        }
    }
}
