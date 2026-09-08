package com.zsz.studyassistant.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zsz.studyassistant.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/** 复习提醒：设置读取/保存、每日定时调度 + 广播发通知 */
object ReminderScheduler {
    private const val PREFS = "settings"
    private const val CHANNEL_ID = "review_reminder"

    fun isEnabled(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("notify_enabled", false)
    private fun hour(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("notify_hour", 20)
    private fun minute(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("notify_minute", 0)

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = c.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "复习提醒", NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(ch)
        }
    }

    private fun pendingIntent(c: Context): PendingIntent {
        val intent = Intent(c, ReminderReceiver::class.java)
        return PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 根据设置的开关与时间，安排每日定时提醒 */
    fun applySchedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(c)
        if (!isEnabled(c)) {
            am.cancel(pi)
            return
        }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(c))
            set(Calendar.MINUTE, minute(c))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        // 每日重复（时间大致稳定，每天一次）
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 24 * 60 * 60 * 1000L, pi)
    }
}

/** 到点后：查询今日/本周待复习错题数，发系统通知 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 重启后闹钟会丢失，重设
            ReminderScheduler.applySchedule(c)
            return
        }
        if (!ReminderScheduler.isEnabled(c)) return
        val dao = AppDatabase.get(c).questionDao()
        val (today, week) = runBlocking {
            val t = dao.dueQuestions(endOfToday()).first().size
            val w = dao.dueQuestions(endOfWeek()).first().size
            t to w
        }
        if (today <= 0 && week <= 0) return
        ReminderScheduler.ensureChannel(c)
        val text = "今天还有 ${today} 道错题要复习！本周末前还有 ${week} 道！"
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val n = NotificationCompat.Builder(c, "review_reminder")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("复习提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try { nm.notify(1001, n) } catch (_: SecurityException) { }
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
    private fun endOfToday(): Long = startOfToday() + 86400000L - 1
    private fun endOfWeek(): Long {
        val cal = Calendar.getInstance()
        var add = Calendar.SUNDAY - cal.get(Calendar.DAY_OF_WEEK)
        if (add < 0) add += 7
        cal.add(Calendar.DAY_OF_MONTH, add)
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }
}
