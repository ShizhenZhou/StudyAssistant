package com.zsz.studyassistant.data

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Keeps the notification fresh, never owns/cancels the actual DownloadManager transfer. */
class UpdateDownloadMonitor : Service() {
    internal class Request {
        val ready = CompletableDeferred<Boolean>()
        val created = SystemClock.elapsedRealtime()
        @Volatile var bindingComplete = false
            private set
        @Volatile var token: String? = null
            private set
        fun bound(task: UpdateDownloads.Snapshot) {
            token = task.token.takeIf { task.appNotification }
            bindingComplete = true
        }
        fun aborted() { bindingComplete = true; ready.complete(false) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var lastTask: UpdateDownloads.Snapshot? = null
    private var completionToken: String? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lease = latestRequest
        try {
            if (!UpdateDownloadNotifications.canPost(this)) {
                lease?.ready?.complete(false); stopSelf(startId); return START_NOT_STICKY
            }
            UpdateDownloadNotifications.clearTerminal(this)
            startForeground(UpdateDownloadNotifications.NOTIFICATION_ID, UpdateDownloadNotifications.build(this, null))
            running = true
            lease?.ready?.complete(true)
        } catch (_: Exception) {
            lease?.ready?.complete(false); stopSelf(startId); return START_NOT_STICKY
        }
        if (observer?.isActive != true) observer = scope.launch {
            val downloads = downloadsFactory(applicationContext)
            while (isActive) {
                try {
                val current = latestRequest
                if (current != null && !current.bindingComplete) {
                    if (SystemClock.elapsedRealtime() - current.created > 60_000) { finish(null); break }
                    delay(200); continue
                }
                if (current != null && current.token == null) { finish(null); break }
                val task = downloads.snapshot()
                // A newer user request must not be stopped by a stale query/terminal result.
                if (current !== latestRequest) continue
                if (task == null || !task.appNotification || (current?.token != null && current.token != task.token)) {
                    finish(null); break
                }
                val visibleTask = if (task.errorKey == "update.downloadUnavailable" && task.progress < 0 && lastTask?.token == task.token)
                    task.copy(progress = lastTask!!.progress) else task
                lastTask = visibleTask
                val note = UpdateDownloadNotifications.build(this@UpdateDownloadMonitor, visibleTask)
                if (task.phase == UpdateDownloads.Phase.READY && completionToken != task.token) {
                    completionToken = task.token
                    UpdateDownloadNotifications.post(this@UpdateDownloadMonitor,
                        UpdateDownloadNotifications.build(this@UpdateDownloadMonitor, visibleTask, completionChip = true))
                    delay(COMPLETION_CHIP_MS)
                    // Re-query ownership and state before leaving a dismissible completion notice.
                    // Cancellation or a newer download during this interval must win.
                    continue
                }
                if (task.phase == UpdateDownloads.Phase.READY || task.phase == UpdateDownloads.Phase.FAILED) {
                    finish(note); break
                }
                UpdateDownloadNotifications.post(this@UpdateDownloadMonitor, note)
                delay(1000)
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { delay(1000) } // Transient notification/binder failures must not crash the app or stop the transfer.
            }
        }
        return START_STICKY
    }
    private fun finish(note: android.app.Notification?) {
        running = false
        try {
            if (note == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                UpdateDownloadNotifications.clear(this)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                UpdateDownloadNotifications.postTerminal(this, note)
            }
        } finally { stopSelf() }
    }
    private fun pauseMonitoring() {
        observer?.cancel()
        finish(runCatching { lastTask?.let { UpdateDownloadNotifications.build(this, it, "update.notif.monitorPaused") } }.getOrNull())
    }
    override fun onTimeout(startId: Int) { pauseMonitoring() }
    override fun onTimeout(startId: Int, fgsType: Int) { pauseMonitoring() }
    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        internal const val COMPLETION_CHIP_MS = 3000L
        // Shared by progress monitoring and notification actions, including isolated test backends.
        internal var downloadsFactory: (Context) -> UpdateDownloads = { UpdateDownloads(it) }
        @Volatile private var latestRequest: Request? = null
        @Volatile private var running = false
        private var lastRestoreAttempt = 0L
        @Synchronized internal fun request(context: Context): Request? {
            if (!UpdateDownloadNotifications.canReplaceNative(context)) return null
            val lease = Request()
            latestRequest = lease
            return try {
                ContextCompat.startForegroundService(context.applicationContext, Intent(context, UpdateDownloadMonitor::class.java))
                lease
            } catch (_: Exception) { lease.aborted(); null }
        }
        @Synchronized internal fun ensureForRestored(context: Context, task: UpdateDownloads.Snapshot) {
            if (!task.appNotification || running || task.phase !in setOf(UpdateDownloads.Phase.DOWNLOADING, UpdateDownloads.Phase.WAITING)) return
            if (latestRequest?.ready?.isCompleted == false) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastRestoreAttempt < 5000) return
            lastRestoreAttempt = now
            request(context)?.bound(task)
        }
    }
}

/** The immutable action includes the task token, so an old notification cannot cancel a new package. */
class UpdateDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateDownloadNotifications.CANCEL_ACTION) return
        val token = intent.getStringExtra(UpdateDownloadNotifications.TOKEN_EXTRA) ?: return
        if (runCatching { UUID.fromString(token).toString() != token }.getOrDefault(true)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { UpdateDownloadMonitor.downloadsFactory(context.applicationContext).cancel(token) }
            catch (_: Exception) { /* Retain the task if the OS cannot currently confirm ownership. */ }
            finally { pending.finish() }
        }
    }
}
