package com.zsz.studyassistant.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** 复习提醒：设置读写 + 每日定时任务调度 + 发通知 */
object ReminderScheduler {
    private const val PREFS = "settings"
    private const val WORK_NAME = "review_reminder"
    const val CHANNEL_ID = "review_reminder"

    fun isEnabled(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("notify_enabled", false)
    private fun hour(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("notify_hour", 20)
    private fun minute(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("notify_minute", 0)

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "复习提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /** 根据开关与时间，调度一个每日任务（首日在设定时刻，之后每 24h） */
    fun applySchedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!isEnabled(c)) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(c))
            set(Calendar.MINUTE, minute(c))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
        }
        val delay = (cal.timeInMillis - now).coerceAtLeast(15000L)
        val req = PeriodicWorkRequestBuilder<ReviewReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

/** 每日检查：今天/本周还有多少待复习错题，并发系统通知 */
class ReviewReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (!ReminderScheduler.isEnabled(c)) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success() // 无通知权限，静默跳过
        }
        val dao = AppDatabase.get(c).questionDao()
        val today = dao.dueQuestions(endOfToday()).first().size
        val week = dao.dueQuestions(endOfWeek()).first().size
        if (today <= 0 && week <= 0) return Result.success()
        ReminderScheduler.ensureChannel(c)
        val text = "今天还有 ${today} 道错题要复习！本周末前还有 ${week} 道！"
        val nm = c.getSystemService(NotificationManager::class.java) ?: return Result.success()
        val n = NotificationCompat.Builder(c, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("复习提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try { nm.notify(1001, n) } catch (_: SecurityException) {}
        return Result.success()
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
