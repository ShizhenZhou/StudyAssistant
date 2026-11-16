package com.zsz.studyassistant.data

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Notification work exists only during a scheduled transfer, never during its network/backoff wait. */
internal object UpdateTransferNotification {
    private val completing = ConcurrentHashMap<String, UUID>()

    fun acknowledge(context: Context, token: String) {
        completing[token]?.let { WorkManager.getInstance(context).cancelWorkById(it) }
    }

    suspend fun run(worker: CoroutineWorker, transferId: Long,
        transfer: suspend () -> ListenableWorker.Result): ListenableWorker.Result = coroutineScope {
        val context = worker.applicationContext
        val downloads = UpdateDownloads(context)
        // Distinct worker IDs prevent a finished delta stage removing the next stage's notification.
        val notificationId = 0x40000000 or (worker.id.hashCode() and 0x3fffffff)
        val manager = context.getSystemService(NotificationManager::class.java)
        var foreground = false
        var foregroundAttempted = false
        var last: UpdateDownloads.Snapshot? = null
        var key: UpdateDownloadNotifications.PresentationKey? = null
        suspend fun refresh(): UpdateDownloads.Snapshot? = try { downloads.snapshotForTransfer(transferId) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        suspend fun publish(task: UpdateDownloads.Snapshot, completion: Boolean = false) {
            last = task
            if (!task.appNotification || !UpdateDownloadNotifications.canPost(context) ||
                UpdateDownloadNotifications.isAcknowledged(context, task.token)) return
            val nextKey = UpdateDownloadNotifications.presentationKey(context, task)
            if (nextKey == key) return
            val note = UpdateDownloadNotifications.build(context, task, completionChip = completion)
            try {
                if (!foregroundAttempted) {
                    foregroundAttempted = true
                    worker.setForeground(if (android.os.Build.VERSION.SDK_INT >= 29)
                        ForegroundInfo(notificationId, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    else ForegroundInfo(notificationId, note))
                    foreground = true
                } else manager?.notify(notificationId, note)
                UpdateDownloadNotifications.clearTerminal(context)
                key = nextKey
            } catch (error: CancellationException) { throw error }
              catch (_: Exception) {
                  // Foreground quota/OS restrictions must not discard bytes or hide ordinary progress.
                  runCatching {
                      manager?.notify(notificationId, note)
                      UpdateDownloadNotifications.clearTerminal(context)
                      key = nextKey
                  }
              }
        }
        try {
            refresh()?.let { publish(it) }
            val execution = async { transfer() }
            while (!execution.isCompleted) {
                delay(1000)
                refresh()?.let { publish(it) }
            }
            val result = execution.await()
            val terminal = refresh()
            last = terminal
            if (terminal?.phase == UpdateDownloads.Phase.READY && terminal.appNotification &&
                UpdateDownloadNotifications.canPost(context)) {
                completing[terminal.token] = worker.id
                key = null
                publish(terminal, completion = true)
                val until = SystemClock.elapsedRealtime() + UpdateDownloadMonitor.COMPLETION_CHIP_MS
                while (SystemClock.elapsedRealtime() < until &&
                    !UpdateDownloadNotifications.isAcknowledged(context, terminal.token)) {
                    delay(1000)
                    if (refresh()?.token != terminal.token) break
                }
            }
            result
        } finally {
            // A stopped worker can still preserve a WAITING notice; a cancelled/replaced root cannot.
            withContext(NonCancellable) {
                val task = runCatching { refresh() }.getOrNull()
                last?.token?.let { completing.remove(it, worker.id) }
                if (task != null && task.appNotification && task.phase != UpdateDownloads.Phase.DOWNLOADING &&
                    !UpdateDownloadNotifications.isAcknowledged(context, task.token)) {
                    runCatching { UpdateDownloadNotifications.postTerminal(context,
                        UpdateDownloadNotifications.build(context, task, completionChip = false)) }
                }
                // WorkManager owns foreground removal; ordinary notices use the separate terminal ID.
                if (!foreground) manager?.cancel(notificationId)
            }
        }
    }
}
