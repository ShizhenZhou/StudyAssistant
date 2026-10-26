package com.zsz.studyassistant.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A single durable app-open request; no periodic or application-start scheduling. */
object BackupJobs {
    data class Status(val phaseKey: String = "", val errorKey: String? = null, val progressBytes: Long = 0, val active: Boolean = false, val automatic: Boolean = true) {
        val canRequestNow: Boolean get() = BackupTaskPolicy.canRequestNow(active, automatic, phaseKey)
    }
    data class Notice(val automatic: Boolean, val snapshotAt: Long)
    private val events = MutableSharedFlow<Notice>(extraBufferCapacity = 1)
    val notices: SharedFlow<Notice> = events
    @Volatile private var foreground = false
    internal val mutex = Mutex()
    private const val WORK = "backup-v4-on-open"
    internal const val PREFIX = "backup_job_"
    internal fun prefs(c: Context) = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun setForeground(active: Boolean) { foreground = active }
    /** Folder IO runs without holding the scheduling monitor, so a manual request can take priority. */
    fun onAppOpened(context: Context) {
        var expectedTree: android.net.Uri? = null
        var expectedGeneration: Long? = null
        try {
            val opening = synchronized(this) {
                if (!AutoBackup.isEnabled(context)) return
                val tree = AutoBackup.treeUri(context) ?: return
                val p = prefs(context)
                if (p.getBoolean(PREFIX + "active", false) && !reconcile(context)) return
                if (p.getString(PREFIX + "error", null) != null && !p.getBoolean(PREFIX + "dispatch_failed", false)) return
                if (!hasActiveQuestions(context)) return
                expectedTree = tree
                expectedGeneration = p.getLong(PREFIX + "generation", 0)
                tree to expectedGeneration
            }
            val summary = BackupRetention.folderSummary(context, opening.first)
            synchronized(this) {
                val p = prefs(context)
                if (AutoBackup.treeUri(context) != opening.first || p.getLong(PREFIX + "generation", 0) != opening.second ||
                    !AutoBackup.isEnabled(context) || p.getBoolean(PREFIX + "active", false)) return
                val today = AutoBackup.dayKey(System.currentTimeMillis())
                val latestDay = today.takeIf { it in summary.backupDays }
                if (latestDay == today) {
                    p.edit().putString(PREFIX + "phase", "data.backupJob.today").remove(PREFIX + "error").commit()
                    return
                }
                val canceledDay = p.getString(PREFIX + "attempt_day", null)
                    .takeIf { p.getString(PREFIX + "phase", null) == "data.backupJob.canceled" }
                if (!BackupTaskPolicy.shouldSchedule(true, true, today, false,
                        hasActiveQuestions(context), latestDay, canceledDay)) return
                enqueue(context, automatic = true, delay = 30)
            }
        } catch (_: Exception) {
            synchronized(this) {
                if (expectedGeneration == null || (AutoBackup.treeUri(context) == expectedTree &&
                        prefs(context).getLong(PREFIX + "generation", 0) == expectedGeneration))
                    recordSchedulingFailure(context, true, "data.autoBackup.snapshotFailed")
            }
        }
    }
    @Synchronized fun requestNow(context: Context) {
        try {
            if (!status(context).canRequestNow) return
            invalidate(context)
            if (AutoBackup.treeUri(context) != null) {
                // This runs on IO: do not report a queued manual request before WorkManager commits it.
                enqueue(context, automatic = false, delay = 0)?.result?.get(5, TimeUnit.SECONDS)
            }
        } catch (_: Exception) { recordSchedulingFailure(context, false, "data.autoBackup.storageFailed") }
    }
    /** Reconcile only UI opens. A preference commit may survive a process death before dispatch. */
    private fun reconcile(context: Context): Boolean {
        val p = prefs(context)
        val token = p.getString(PREFIX + "token", null)
        val id = p.getString(PREFIX + "work_id", null)
        val works = try { WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK).get(5, TimeUnit.SECONDS) }
            catch (_: Exception) {
                recordSchedulingFailure(context, p.getBoolean(PREFIX + "automatic", true), "data.autoBackup.storageFailed", terminal = false)
                return false
            }
        val matching = works.firstOrNull { it.id.toString() == id && "backup-token:$token" in it.tags }
        if (matching != null && !matching.state.isFinished) return false
        val edit = p.edit().putBoolean(PREFIX + "active", false)
        if (matching == null) {
            // Missing dispatch, rather than a completed failed attempt: recover once on this open.
            edit.remove(PREFIX + "attempt_day").remove(PREFIX + "error").remove(PREFIX + "token").remove(PREFIX + "work_id")
        } else {
            val failed = matching.state == androidx.work.WorkInfo.State.FAILED
            edit.putString(PREFIX + "phase", if (failed) "data.backupJob.failed" else "data.backupJob.canceled")
            if (failed && p.getString(PREFIX + "error", null) == null) edit.putString(PREFIX + "error", "data.autoBackup.storageFailed")
        }
        edit.commit()
        return true
    }
    internal fun enqueue(context: Context, automatic: Boolean, delay: Long,
        dispatch: (androidx.work.OneTimeWorkRequest) -> androidx.work.Operation = {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, it)
        }): androidx.work.Operation? {
        var queuedOperation: androidx.work.Operation? = null
        var failureKey = "data.autoBackup.snapshotFailed"
        try {
        val p = prefs(context)
        val token = UUID.randomUUID().toString()
        val generation = p.getLong(PREFIX + "generation", 0)
        val tree = AutoBackup.treeUri(context)?.toString() ?: return null
        if (automatic && !hasActiveQuestions(context)) return null
        failureKey = "data.autoBackup.storageFailed"
        // State is committed before dispatch, so repeated activity creation cannot duplicate a request.
        p.edit().putString(PREFIX + "token", token).putBoolean(PREFIX + "active", true)
            .putString(PREFIX + "phase", "data.backupJob.queued").remove(PREFIX + "error").remove(PREFIX + "dispatch_failed")
            .remove(PREFIX + "failures").remove(PREFIX + "yield_stage").remove(PREFIX + "yield_count")
            .putBoolean(PREFIX + "automatic", automatic)
            .putString(PREFIX + "attempt_day", AutoBackup.dayKey(System.currentTimeMillis())).commit()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag("backup-token:$token")
            .setInputData(workDataOf("token" to token, "generation" to generation, "tree" to tree, "automatic" to automatic))
            .setInitialDelay(delay, TimeUnit.SECONDS)
            .setConstraints(if (automatic) Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
                else Constraints.NONE)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
        p.edit().putString(PREFIX + "work_id", request.id.toString()).commit()
        try {
            val operation = dispatch(request)
            queuedOperation = operation
            operation.result.addListener({
                try { operation.result.get() }
                catch (_: Exception) { synchronized(BackupJobs) { dispatchFailed(context, token) } }
            }, java.util.concurrent.Executor { it.run() })
        } catch (_: Exception) { dispatchFailed(context, token) }
        } catch (_: Exception) {
            recordSchedulingFailure(context, automatic, failureKey)
        }
        return queuedOperation
    }
    /** UI entry points must never crash the app when local DB or scheduler services fail. */
    private fun recordSchedulingFailure(context: Context, automatic: Boolean, key: String, terminal: Boolean = true) {
        try {
            val safeKey = if (key == "data.autoBackup.snapshotFailed") key else "data.autoBackup.storageFailed"
            val edit = prefs(context).edit().putString(PREFIX + "error", safeKey)
            if (terminal) edit.putBoolean(PREFIX + "active", false).putString(PREFIX + "phase", "data.backupJob.failed")
            edit.commit()
            AutoBackup.recordFailure(context, safeKey)
            if (terminal) notifyFailure(context, automatic)
        } catch (_: Exception) { /* A failed status store/notification must not crash a UI open. */ }
    }
    private fun dispatchFailed(context: Context, token: String) {
        try {
        val p = prefs(context)
        if (p.getString(PREFIX + "token", null) != token) return
        p.edit().putBoolean(PREFIX + "active", false).putBoolean(PREFIX + "dispatch_failed", true)
            .putString(PREFIX + "phase", "data.backupJob.failed").putString(PREFIX + "error", "data.autoBackup.storageFailed")
            .remove(PREFIX + "attempt_day").remove(PREFIX + "work_id").commit()
        notifyFailure(context, p.getBoolean(PREFIX + "automatic", true))
        } catch (_: Exception) { /* Status/notification failure must not escape a scheduler callback. */ }
    }
    @Synchronized fun cancel(context: Context) {
        invalidate(context)
        prefs(context).edit().putString(PREFIX + "phase", "data.backupJob.canceled")
            .putString(PREFIX + "attempt_day", AutoBackup.dayKey(System.currentTimeMillis())).commit()
    }
    @Synchronized fun invalidate(context: Context) {
        val p = prefs(context)
        p.edit().putLong(PREFIX + "generation", p.getLong(PREFIX + "generation", 0) + 1)
            .putBoolean(PREFIX + "active", false).remove(PREFIX + "token").remove(PREFIX + "checked_day").commit()
        try { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
        catch (_: Exception) { recordSchedulingFailure(context, false, "data.autoBackup.storageFailed") }
    }
    @Synchronized fun configurationChanged(context: Context) {
        invalidate(context)
        prefs(context).edit().remove(PREFIX + "attempt_day").remove(PREFIX + "checked_day")
            .remove(PREFIX + "hash").remove(PREFIX + "phase").remove(PREFIX + "error").remove(PREFIX + "dispatch_failed").commit()
    }
    fun status(context: Context): Status = prefs(context).let { Status(it.getString(PREFIX + "phase", "") ?: "", it.getString(PREFIX + "error", null), it.getLong(PREFIX + "bytes", 0), it.getBoolean(PREFIX + "active", false), it.getBoolean(PREFIX + "automatic", true)) }
    suspend fun <T> exclusive(context: Context, block: suspend () -> T): T {
        invalidate(context)
        return mutex.withLock { block() }
    }
    /** One pre-clean per durable job, including retries after external completion. */
    internal suspend fun preTrimOnce(context: Context, tree: android.net.Uri, token: String, check: () -> Unit = {}): Int {
        if (prefs(context).getString(PREFIX + "pretrim_token", null) == token) return 0
        val removed = BackupRetention.preTrim(context, tree, check)
        synchronized(BackupJobs) {
            check()
            if (!prefs(context).edit().putString(PREFIX + "pretrim_token", token).commit()) throw IOException("pre-clean checkpoint")
        }
        return removed
    }
    /** Call on IO: bounded EXISTS does not load question text/images or a full ID list. */
    internal fun hasActiveQuestions(context: Context, database: AppDatabase = AppDatabase.get(context)): Boolean =
        database.openHelper.writableDatabase.query("SELECT EXISTS(SELECT 1 FROM questions WHERE deleted = 0 LIMIT 1)").use {
            it.moveToFirst() && it.getInt(0) != 0
        }
    internal fun stopAutomaticIfEmpty(context: Context, automatic: Boolean, check: () -> Unit = {}): Boolean {
        if (!automatic || hasActiveQuestions(context)) return false
        synchronized(BackupJobs) {
            check()
            prefs(context).edit().putBoolean(PREFIX + "active", false).putString(PREFIX + "phase", "data.backupJob.empty")
                .remove(PREFIX + "error").remove(PREFIX + "attempt_day").remove(PREFIX + "checked_day").commit()
        }
        return true
    }
    internal fun notifyFailure(context: Context, automatic: Boolean) {
        if (BackupTaskPolicy.shouldNotifyFailure(automatic, foreground, terminal = true)) BackupNotifications.failure(context)
    }
    internal fun notifySuccess(automatic: Boolean, at: Long) { if (foreground) events.tryEmit(Notice(automatic, at)) }
}

/** Long work yields on bounded stream callbacks; only completed private exports are reusable. */
class BackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private class Yield : CancellationException()
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        BackupJobs.mutex.withLock { execute() }
    }
    private suspend fun execute(): Result {
        val c = applicationContext
        val p = BackupJobs.prefs(c)
        val prefix = BackupJobs.PREFIX
        val token = inputData.getString("token") ?: return Result.failure()
        val generation = inputData.getLong("generation", -1)
        val tree = android.net.Uri.parse(inputData.getString("tree") ?: return Result.failure())
        val automatic = inputData.getBoolean("automatic", true)
        fun valid(): Boolean = p.getLong(prefix + "generation", 0) == generation && p.getString(prefix + "token", null) == token && AutoBackup.treeUri(c) == tree && (!automatic || AutoBackup.isEnabled(c))
        if (!valid()) return Result.success()
        val coroutine = currentCoroutineContext()
        val started = android.os.SystemClock.elapsedRealtime()
        var bytesAt = 0L
        val check: () -> Unit = {
            coroutine.ensureActive()
            if (isStopped || !valid()) throw CancellationException("invalidated")
            // WorkManager's ordinary execution window is bounded; allow time to close streams.
            if (android.os.SystemClock.elapsedRealtime() - started > 7 * 60_000) throw Yield()
        }
        fun state(phase: String, bytes: Long = 0) = synchronized(BackupJobs) {
            check()
            // Avoid a preference disk write for every 64 KiB callback.
            if (bytes == 0L || bytes - bytesAt > 1024 * 1024) {
                bytesAt = bytes
                p.edit().putString(prefix + "phase", phase).putLong(prefix + "bytes", bytes).apply()
            }
        }
        val root = File(c.noBackupFilesDir, "backup-jobs").apply { mkdirs() }
        val workspace = File(root, token).apply { mkdirs() }
        val manifest = File(workspace, "complete.json")
        try {
            state("data.backupJob.capturing")
            if (BackupJobs.stopAutomaticIfEmpty(c, automatic, check)) return Result.success()
            if (automatic && !manifest.exists()) {
                val saved = BackupRetention.folderSummary(c, tree, check)
                if (AutoBackup.dayKey(System.currentTimeMillis()) in saved.backupDays) {
                    synchronized(BackupJobs) {
                        check()
                        p.edit().putBoolean(prefix + "active", false).putString(prefix + "phase", "data.backupJob.today")
                            .remove(prefix + "error").commit()
                    }
                    workspace.deleteRecursively()
                    return Result.success()
                }
            }
            if (automatic) {
                state("data.backupJob.retention")
                BackupJobs.preTrimOnce(c, tree, token, check)
            }
            // Only task-owned UUID directories, never business images or external documents.
            root.listFiles()?.filter { it != workspace && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60_000L }?.forEach { it.deleteRecursively() }
            var set = if (manifest.isFile && System.currentTimeMillis() - manifest.lastModified() <= 24 * 60 * 60_000L) readCheckpoint(manifest, workspace, check) else null
            if (set == null) {
                // Preserve repository capture/normalize checkpoints on an ordinary resume.
                // A stale or corrupt completed archive invalidates this entire private task.
                if (manifest.exists()) { workspace.deleteRecursively(); workspace.mkdirs() }
                if (android.os.StatFs(workspace.absolutePath).availableBytes < 32L * 1024 * 1024) throw BackupWriteException("data.backupNoSpace")
                state("data.backupJob.capturing")
                set = BackupRepository(c).export(File(workspace, "export"), check = check, progress = { phase, bytes -> state(phase, bytes) })
                check()
                val json = JSONObject().put("id", set.setId).put("at", set.snapshotAt).put("hash", set.contentHash)
                val parts = org.json.JSONArray()
                for (file in set.files) parts.put(JSONObject().put("path", file.relativeTo(workspace).path).put("sha", hex(BackupStreamCopy.digest(file.inputStream(), check))))
                json.put("parts", parts)
                val temp = File(workspace, "complete.tmp")
                temp.outputStream().use { out -> out.write(json.toString().toByteArray()); out.fd.sync() }
                if (!temp.renameTo(manifest)) throw IOException("checkpoint")
            }
            check()
            state("data.backupJob.writing")
            val result = BackupDocuments.writeSet(c, set, tree, automatic, check)
            check()
            if (!result.ok) throw BackupWriteException(result.errorKey ?: "data.autoBackup.storageFailed")
            synchronized(BackupJobs) {
            check()
            AutoBackup.markVerified(c, set.snapshotAt, tree)
            p.edit().putString(prefix + "hash", set.contentHash).putString(prefix + "checked_day", AutoBackup.dayKey(System.currentTimeMillis()))
                .putBoolean(prefix + "active", false).putString(prefix + "phase", "data.backupJob.success").remove(prefix + "error").commit()
            BackupNotifications.clear(c)
            BackupJobs.notifySuccess(automatic, set.snapshotAt)
            }
            workspace.deleteRecursively()
            return Result.success()
        } catch (e: Yield) {
            // Budget yields have their own durable allowance. A phase that cannot finish
            // inside an ordinary worker must stop rather than retry forever.
            return synchronized(BackupJobs) {
                if (!valid()) return@synchronized Result.success()
                val archiveId = runCatching { JSONObject(manifest.readText()).getString("id") }.getOrNull()
                val checkpoints = listOf("capture.ready", "normalize.ready", "validation.ready").joinToString(",") {
                    File(File(workspace, "export"), it).isFile.toString()
                }
                val signature = "${p.getString(prefix + "phase", "")}:$checkpoints:${manifest.isFile}:${archiveId?.let { BackupDocuments.completedPartCount(c, it) } ?: 0}"
                val count = if (p.getString(prefix + "yield_stage", null) == signature) p.getInt(prefix + "yield_count", 0) + 1 else 1
                val retry = count < 3
                p.edit().putString(prefix + "yield_stage", signature).putInt(prefix + "yield_count", count)
                    .putBoolean(prefix + "active", retry)
                    .putString(prefix + "phase", if (retry) p.getString(prefix + "phase", "data.backupJob.queued") else "data.backupJob.failed")
                    .apply { if (!retry) putString(prefix + "error", "data.backupJob.timeLimit") }.commit()
                if (!retry) BackupJobs.notifyFailure(c, automatic)
                if (retry) Result.retry() else Result.failure()
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (!valid()) return Result.success()
            val key = when (e) {
                is SecurityException -> "data.autoBackup.permissionFailed"
                is BackupWriteException -> e.messageKey
                is BackupValidationException -> e.messageKey
                else -> if (generateSequence<Throwable>(e) { it.cause }.any { it is android.database.sqlite.SQLiteFullException || (it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC) }) "data.backupNoSpace" else "data.autoBackup.storageFailed"
            }
            return synchronized(BackupJobs) {
            if (!valid()) return@synchronized Result.success()
            val failures = p.getInt(prefix + "failures", 0) + 1
            val retry = BackupTaskPolicy.retryable(key, failures - 1)
            p.edit().putInt(prefix + "failures", failures).putString(prefix + "error", key)
                .putString(prefix + "phase", if (retry) "data.backupJob.queued" else "data.backupJob.failed").putBoolean(prefix + "active", retry).commit()
            AutoBackup.recordFailure(c, key, expectedTree = tree)
            if (!retry) BackupJobs.notifyFailure(c, automatic)
            if (retry) Result.retry() else Result.failure()
            }
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun readCheckpoint(file: File, root: File, check: () -> Unit): BackupArchive.ArchiveSet? = try {
        val json = JSONObject(file.readText())
        val parts = json.getJSONArray("parts")
        val files = (0 until parts.length()).map { i ->
            check()
            val part = parts.getJSONObject(i)
            val source = File(root, part.getString("path")).canonicalFile
            if (!source.toPath().startsWith(root.canonicalFile.toPath()) || !source.isFile || hex(BackupStreamCopy.digest(source.inputStream(), check)) != part.getString("sha")) throw IOException("checkpoint")
            source
        }
        BackupArchive.ArchiveSet(json.getString("id"), json.getLong("at"), files, json.getString("hash"))
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}
