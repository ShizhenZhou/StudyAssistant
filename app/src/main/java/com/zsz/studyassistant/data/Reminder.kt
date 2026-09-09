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
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zsz.studyassistant.MainActivity
import java.util.Calendar
import kotlinx.coroutines.flow.first

/** 复习提醒：创建通知渠道 + 每天到点用精确闹钟触发 */
object ReminderScheduler {
    private const val PREFS = "settings"
    const val CHANNEL_ID = "review_reminder"

    fun isEnabled(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("notify_enabled", false)
    private fun hour(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("notify_hour", 20)
    private fun minute(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("notify_minute", 0)

    /** 启动时创建通知渠道（否则设置页通知管理会显示"未发布任何通知"） */
    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "复习提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
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
            val showIntent = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showIntent), pending(c))
        } catch (_: Exception) {
            // 个别系统/厂商对精确闹钟有限制，忽略避免崩溃
        }
    }
}

/** 到点发通知 + 重排下一天；开机时恢复闹钟 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler.applySchedule(c)
            return
        }
        if (!ReminderScheduler.isEnabled(c)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val dao = AppDatabase.get(c).questionDao()
        val (today, week) = kotlinx.coroutines.runBlocking {
            val t = dao.dueQuestions(endOfToday()).first().size
            val w = dao.dueQuestions(endOfWeek()).first().size
            t to w
        }
        if (today > 0 || week > 0) {
            ReminderScheduler.ensureChannel(c)
            val text = "今天还有 ${today} 道错题要复习！本周末前还有 ${week} 道！"
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            val n = NotificationCompat.Builder(c, ReminderScheduler.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("复习提醒")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        c, 0,
                        Intent(c, MainActivity::class.java).putExtra("open_review", true),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            try { nm.notify(1001, n) } catch (_: SecurityException) {}
        }
        // 重排下一天
        ReminderScheduler.applySchedule(c)
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
