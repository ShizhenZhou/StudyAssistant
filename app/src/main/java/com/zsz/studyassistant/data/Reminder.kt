package com.zsz.studyassistant.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlinx.coroutines.launch

/** 复习提醒：创建通知渠道 + 每天到点用精确闹钟触发 */
object ReminderScheduler {
    private const val PREFS = "settings"
    const val CHANNEL_ID = "review_reminder"

    fun isEnabled(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("notify_enabled", false)
    private fun hour(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("notify_hour", 20)
    private fun minute(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("notify_minute", 0)

    /** 启动时创建通知渠道（否则设置页通知管理会显示"未发布任何通知"） */
    fun ensureChannel(c: Context) {
        val s = com.zsz.studyassistant.ui.stringsFor(com.zsz.studyassistant.ui.UiLangStore.load(c))
        c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, s["notif.review.channel"], NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun pending(c: Context): PendingIntent =
        PendingIntent.getBroadcast(c, 0, Intent(c, ReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun nextTriggerAt(c: Context): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(c)); set(Calendar.MINUTE, minute(c)); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    /** 设置/开关调用：用 setAlarmClock 精确到点，国产机也尽量按时、Doze 下也触发 */
    fun applySchedule(c: Context) {
        try {
            val am = c.getSystemService(AlarmManager::class.java) ?: return
            if (!isEnabled(c)) {
                am.cancel(pending(c))
                return
            }
            ensureChannel(c)
            val trigger = nextTriggerAt(c)
            val showIntent = NotificationIntents.alarmClock(c)
            // Android 12+ 精确闹钟需要 SCHEDULE_EXACT_ALARM（14+ 起默认不授予）。
            // 没拿到就退化为 setAndAllowWhileIdle：不保证分秒不差，但 Doze 下也会触发，至少不漏提醒。
            val canExact = am.canScheduleExactAlarms()
            if (canExact) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showIntent), pending(c))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending(c))
            }
        } catch (_: Exception) {
            // 个别系统/厂商对精确闹钟有限制，忽略避免崩溃
        }
    }
}

/** 到点发通知 + 重排下一天；开机时恢复闹钟 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            ReminderScheduler.applySchedule(c)
            return
        }
        val result = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try { kotlinx.coroutines.withTimeout(7000) {
                if (!ReminderScheduler.isEnabled(c)) return@withTimeout
                if (ContextCompat.checkSelfPermission(c,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@withTimeout
                val day = CalendarDay.now()
                val dao = AppDatabase.get(c).questionDao()
                val today = dao.dueCount(day.end)
                val week = dao.dueCount(day.weekEnd)
                if (today > 0 || week > 0) {
                    ReminderScheduler.ensureChannel(c)
                    val s = com.zsz.studyassistant.ui.stringsFor(com.zsz.studyassistant.ui.UiLangStore.load(c))
                    val text = s.format("notif.review.text", "today" to "$today", "week" to "$week")
                    val notification = NotificationCompat.Builder(c, ReminderScheduler.CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(s["notif.review.channel"])
                        .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setContentIntent(NotificationIntents.reviewReminder(c))
                        .setAutoCancel(true).build()
                    c.getSystemService(NotificationManager::class.java)?.notify(1001, notification)
                }
            } } catch (_: Exception) { /* No private data or exception payload is logged. */ }
            finally {
                try { ReminderScheduler.applySchedule(c) } finally { result.finish() }
            }
        }
    }

}
