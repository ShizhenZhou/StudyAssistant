package com.zsz.studyassistant.data

import android.content.Context
import android.os.Build

/** Local-only report preparation. No network client, account identifier, or automatic upload. */
object CrashReports {
    private const val PREFS = "crash_report_reminder"
    private const val SEEN = "last_seen"
    private const val PENDING = "pending_crash"
    private const val INSTALLATION = "crash_installation"

    fun pending(context: Context): String? {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return pendingCrashForInstallation(
            preferences.getString(PENDING, null), preferences.getLong(INSTALLATION, 0),
            UpdateChecker.installedLastUpdateTime(context), preferences.getString(SEEN, null)
        )?.takeIf { id -> CrashLogger.files(context).any { it.name == id } }
    }

    /** Only the uncaught-exception handler creates a reminder; old files alone never do. */
    internal fun record(context: Context, id: String, installation: Long) {
        // The process is about to terminate, so this must be durable before forwarding to Android.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PENDING, id).putLong(INSTALLATION, installation).remove(SEEN).commit()
    }

    fun acknowledge(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SEEN, id).apply()
    }

    fun preview(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val crash = CrashLogger.files(context).firstOrNull()
        return buildString {
            appendLine("StudyAssistant ${info.versionName} (versionCode ${info.longVersionCode})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            if (crash != null) {
                appendLine()
                val safeReport = CrashLogger.reportFile(crash).takeIf { it.isFile }
                // Bound both memory and work when reading legacy local crash logs.
                val raw = (safeReport ?: crash).reader(Charsets.UTF_8).use { reader ->
                    val chars = CharArray(128 * 1024)
                    var size = 0
                    while (size < chars.size) {
                        val n = reader.read(chars, size, chars.size - size)
                        if (n < 0) break
                        size += n
                    }
                    String(chars, 0, size)
                }
                appendLine(if (safeReport != null) raw else CrashReportText.extract(raw))
            }
        }.trim()
    }
}
