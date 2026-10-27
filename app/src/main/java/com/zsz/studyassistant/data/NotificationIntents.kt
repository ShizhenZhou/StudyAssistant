package com.zsz.studyassistant.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zsz.studyassistant.MainActivity
import com.zsz.studyassistant.ui.AppNav

/** Stable, separate activity identities: extras and activity flags do not distinguish PendingIntents. */
internal object NotificationIntents {
    private const val ANSWER_REQUEST = 2001
    private const val REVIEW_REQUEST = 1001
    private const val ALARM_CLOCK_REQUEST = 1002
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun answerRunning(context: Context): PendingIntent = PendingIntent.getActivity(
        context, ANSWER_REQUEST,
        Intent(context, MainActivity::class.java).apply {
            action = "${context.packageName}.notification.ANSWER_RUNNING"
            // Bring the existing solve page to the foreground without creating another activity.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, FLAGS
    )

    fun reviewReminder(context: Context): PendingIntent = PendingIntent.getActivity(
        context, REVIEW_REQUEST,
        Intent(context, MainActivity::class.java).apply {
            action = "${context.packageName}.notification.REVIEW_REMINDER"
            putExtra(AppNav.OPEN_REVIEW_EXTRA, true)
        }, FLAGS
    )

    fun alarmClock(context: Context): PendingIntent = PendingIntent.getActivity(
        context, ALARM_CLOCK_REQUEST,
        Intent(context, MainActivity::class.java).apply {
            action = "${context.packageName}.notification.ALARM_CLOCK"
        }, FLAGS
    )
}
