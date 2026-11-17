package com.zsz.studyassistant.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Owns durable bytes; the executing worker owns progress, without a waiting foreground service. */
internal class ResumableUpdateBackend(context: Context, private val legacy: UpdateDownloads.Backend) : UpdateDownloads.Backend {
    private val context = context.applicationContext
    private val prefs get() = context.getSharedPreferences("update_resumable_transfers", Context.MODE_PRIVATE)
    private val work get() = WorkManager.getInstance(context)
    private data class Record(val id: Long, val tag: String, val url: String, val destination: String, val size: Long,
        val sha: String?, val uploaded: Long, val generation: String,
        val phase: UpdateDownloads.TransferPhase = UpdateDownloads.TransferPhase.QUEUED,
        val error: String? = null, val legacyId: Long = -1)

    override fun enqueue(release: UpdateChecker.ReleaseInfo, destination: File, appNotification: Boolean): Long {
        validate(release, destination)
        val record = synchronized(lock) {
            records().firstOrNull { same(it, release, destination) }?.let { return it.id }
            var id: Long
            do { id = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE) or ID_BASE } while (read(id) != null)
            Record(id, release.tagName, release.assetUrl, destination.canonicalPath, release.assetSize, normalizedSha(release.assetSha256),
                release.assetUploadedAt, UUID.randomUUID().toString()).also(::write)
        }
        schedule(record)
        return record.id
    }

    override fun resume(release: UpdateChecker.ReleaseInfo, destination: File, id: Long, appNotification: Boolean): Long? {
        validate(release, destination)
        var record = synchronized(lock) { resolve(id) }
        if (record == null && id < ID_BASE) record = migrate(release, destination, id) ?: return null
        val resolved = record ?: return null
        val next = synchronized(lock) {
            val current = read(resolved.id) ?: return null
            if (!same(current, release, destination)) return null
            if (current.phase == UpdateDownloads.TransferPhase.SUCCESS) return current.id
            // Replacing the generation invalidates every old callback before WorkManager stops it.
            current.copy(generation = UUID.randomUUID().toString(), phase = UpdateDownloads.TransferPhase.QUEUED,
                error = null).also(::write)
        }
        active[next.id]?.call?.cancel()
        schedule(next)
        return next.id
    }

    override fun query(id: Long): UpdateDownloads.Transfer? {
        val record = synchronized(lock) { resolve(id) } ?: return if (id < ID_BASE) legacy.query(id) else null
        var phase = record.phase
        if (phase in setOf(UpdateDownloads.TransferPhase.QUEUED, UpdateDownloads.TransferPhase.RUNNING) &&
            active[record.id]?.generation != record.generation) {
            // Process death, a stopped worker or a constraint wait must not appear permanently running.
            val info = runCatching { work.getWorkInfoById(UUID.fromString(record.generation)).get(2, TimeUnit.SECONDS) }.getOrNull()
            if (info == null || info.state != androidx.work.WorkInfo.State.RUNNING) phase = UpdateDownloads.TransferPhase.WAITING
        }
        return UpdateDownloads.Transfer(record.id, record.url, Uri.fromFile(File(record.destination)).toString(), phase,
            partial(record.id).length(), record.size, File(record.destination).nameWithoutExtension, record.error,
            usingMirror = phase == UpdateDownloads.TransferPhase.RUNNING &&
                active[record.id]?.let { it.generation == record.generation && it.usingMirror } == true)
    }

    override fun find(url: String, destination: File): UpdateDownloads.Transfer? {
        val record = synchronized(lock) { records().firstOrNull { it.url == url && it.destination == destination.canonicalPath } }
        return record?.let { query(it.id) } ?: legacy.find(url, destination)
    }

    override fun remove(id: Long) {
        val record = synchronized(lock) {
            val current = resolve(id) ?: return@synchronized null
            // Retain the alias if Android cannot yet confirm removal of a migrated legacy row.
            retireLegacy(current)
            // Removing the durable authority first makes late callbacks unable to recreate the task.
            check(prefs.edit().remove(current.id.toString()).commit())
            active[current.id]?.call?.cancel()
            partial(current.id).delete()
            File(current.destination).delete()
            current
        }
        if (record == null) { if (id < ID_BASE) legacy.remove(id); return }
        // Completing a transfer tidies its bytes while its worker finishes notification handoff.
        if (record.phase != UpdateDownloads.TransferPhase.SUCCESS) work.cancelUniqueWork(workName(record.id))
    }

    private fun schedule(record: Record) {
        try {
            val request = OneTimeWorkRequestBuilder<ResumableUpdateWorker>()
                .setId(UUID.fromString(record.generation))
                .setInputData(workDataOf("transfer" to record.id, "generation" to record.generation))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            work.enqueueUniqueWork(workName(record.id), ExistingWorkPolicy.REPLACE, request).result.get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            transition(record, UpdateDownloads.TransferPhase.WAITING, "update.downloadUnavailable")
        }
    }

    /** Freeze a genuinely owned, stopped legacy partial before DownloadManager deletes its file. */
    private fun migrate(release: UpdateChecker.ReleaseInfo, destination: File, id: Long): Record? = synchronized(lock) {
        resolve(id)?.let { return it }
        val source = legacy.query(id) ?: return null
        if (!owns(source, release.assetUrl, destination) || source.status !in setOf(
                UpdateDownloads.TransferPhase.WAITING, UpdateDownloads.TransferPhase.FAILED)) return null
        if (!destination.isFile || destination.length() != source.bytes || source.bytes !in 1..release.assetSize ||
            normalizedSha(release.assetSha256) == null) return null
        var newId: Long
        do { newId = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE) or ID_BASE } while (read(newId) != null)
        val staged = partial(newId)
        try {
            UpdateStorage.requireSpace(staged, source.bytes)
            destination.inputStream().use { input -> staged.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (copied < source.bytes) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), source.bytes - copied).toInt())
                    if (count < 0) throw IOException("Legacy partial changed")
                    output.write(buffer, 0, count)
                    copied += count
                }
                output.fd.sync()
            } }
            val current = legacy.query(id) ?: throw IOException("Legacy transfer changed")
            if (!owns(current, release.assetUrl, destination) || current.status != source.status ||
                current.bytes != source.bytes || destination.length() != source.bytes) throw IOException("Legacy partial changed")
            val record = Record(newId, release.tagName, release.assetUrl, destination.canonicalPath, release.assetSize,
                normalizedSha(release.assetSha256), release.assetUploadedAt, UUID.randomUUID().toString(),
                phase = UpdateDownloads.TransferPhase.WAITING, legacyId = id)
            write(record) // Old-ID alias remains recoverable even if the process dies before root records newId.
            record
        } catch (error: Exception) {
            staged.delete()
            if (UpdateStorage.isInsufficient(error)) throw error
            null
        }
    }

    private fun retireLegacy(record: Record) {
        if (record.legacyId < 0) return
        val row = legacy.query(record.legacyId) ?: return
        if (!owns(row, record.url, File(record.destination))) throw IOException("Legacy ownership changed")
        legacy.remove(record.legacyId)
    }

    internal fun run(id: Long, generation: String, attempt: Int, stopped: () -> Boolean): Boolean {
        val record = synchronized(lock) { read(id)?.takeIf { it.generation == generation } } ?: return false
        val execution = Execution(generation)
        // WorkManager normally serializes unique work. This also fences a replaced worker still unwinding IO.
        if (active.putIfAbsent(id, execution) != null) return true
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(7)
        fun check() {
            if (stopped() || synchronized(lock) { read(id)?.generation != generation }) throw Stopped()
            if (System.nanoTime() >= deadline) throw WindowEnded()
        }
        fun guard(action: () -> Unit) = synchronized(lock) { check(); action() }
        try {
            check()
            retireLegacy(record)
            transition(record, UpdateDownloads.TransferPhase.RUNNING)
            val file = partial(id)
            if (file.length() > record.size) throw UpdateRangeTransfer.Rejected("update.verifyFailed")
            val destination = File(record.destination)
            destination.parentFile?.mkdirs()
            UpdateStorage.requireDownloadSpace(file, destination, record.size,
                Environment.isExternalStorageEmulated(destination))
            val urls = UpdateTransport.urls(record.url, UpdateTransport.load(context))
            var lastError: Exception? = null
            for (url in urls) {
                check()
                if (file.length() == record.size) break
                try {
                    while (file.length() < record.size) {
                        check()
                        val offset = file.length()
                        if (offset > 0 && record.sha == null) throw UpdateRangeTransfer.Rejected()
                        val request = Request.Builder().url(url).header("User-Agent", "StudyAssistant-Update")
                            .header("Accept-Encoding", "identity")
                            .apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
                        val call = client.newCall(request)
                        call.timeout().timeout((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                        execution.call = call
                        check()
                        execution.usingMirror = url != record.url
                        call.execute().use { response ->
                            val body = response.body ?: throw IOException("Empty update response")
                            UpdateRangeTransfer.append(response.code, response.header("Content-Range"), body.contentLength(),
                                response.header("Content-Encoding"), body.byteStream(), file, record.size, ::guard, ::check)
                        }
                    }
                    lastError = null
                    break
                } catch (error: Stopped) { throw error }
                  catch (error: WindowEnded) { throw error }
                  catch (error: Exception) {
                      if (UpdateStorage.isInsufficient(error)) throw error
                      if (lastError !is UpdateRangeTransfer.Rejected || error is UpdateRangeTransfer.Rejected) lastError = error
                  }
                finally { execution.usingMirror = false; execution.call = null }
            }
            lastError?.let { throw it }
            check()
            if (file.length() != record.size) throw IOException("Incomplete update")
            if (record.sha != null && !UpdateDelta.matches(file, record.size, record.sha, ::check)) {
                transition(record, UpdateDownloads.TransferPhase.FAILED, "update.verifyFailed")
                return false
            }
            // Complete bytes (hash-checked when a digest is published) reach the existing handoff path.
            UpdateStorage.requireSpace(destination, record.size)
            file.inputStream().use { input ->
                var output: java.io.FileOutputStream? = null
                guard {
                    File(record.destination).parentFile?.mkdirs()
                    output = File(record.destination).outputStream()
                }
                checkNotNull(output).use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        guard { target.write(buffer, 0, count) }
                    }
                    guard { target.fd.sync() }
                }
            }
            guard { transition(record, UpdateDownloads.TransferPhase.SUCCESS) }
            return false
        } catch (error: WindowEnded) {
            if (UpdateStorage.isInsufficient(error)) {
                transition(record, UpdateDownloads.TransferPhase.WAITING, UpdateStorage.ERROR_KEY)
                return false
            }
            transition(record, UpdateDownloads.TransferPhase.WAITING, "update.downloadInterrupted")
            return attempt < MAX_RETRIES // Persisted bytes are the next attempt's Range offset.
        } catch (_: Stopped) {
            transition(record, UpdateDownloads.TransferPhase.WAITING, "update.downloadInterrupted")
            return false
        } catch (error: Exception) {
            if (UpdateStorage.isInsufficient(error)) {
                transition(record, UpdateDownloads.TransferPhase.WAITING, UpdateStorage.ERROR_KEY)
                return false // Keep the partial; only an explicit resume may retry after freeing space.
            }
            val key = (error as? UpdateRangeTransfer.Rejected)?.errorKey ?: "update.downloadInterrupted"
            transition(record, UpdateDownloads.TransferPhase.WAITING, key)
            // Back off transient IO at 30s, 60s, … 5min, then leave retained bytes for manual resume.
            // Protocol rejection cannot improve without a different host or an explicit restart.
            return error is IOException && error !is UpdateRangeTransfer.Rejected && attempt < MAX_RETRIES
        } finally {
            execution.call?.cancel()
            active.remove(id, execution)
        }
    }

    private fun transition(record: Record, phase: UpdateDownloads.TransferPhase, error: String? = null) = synchronized(lock) {
        val current = read(record.id)
        if (current?.generation == record.generation) write(current.copy(phase = phase, error = error))
    }
    private fun partial(id: Long): File = File(context.noBackupFilesDir, "update-transfers/$id.part").also { it.parentFile?.mkdirs() }
    private fun resolve(id: Long) = read(id) ?: if (id < ID_BASE) records().firstOrNull { it.legacyId == id } else null
    private fun records() = prefs.all.keys.mapNotNull { it.toLongOrNull()?.let(::read) }
    private fun read(id: Long): Record? = runCatching {
        val json = JSONObject(prefs.getString(id.toString(), null) ?: return null)
        require(id >= ID_BASE)
        val destination = File(json.getString("destination"))
        validateDestination(destination)
        val url = json.getString("url")
        val tag = json.getString("tag")
        require(UpdateChecker.isReleaseAssetUrl(url, tag))
        val size = json.getLong("size")
        require(size in 1..UpdateDelta.MAX_APK_SIZE)
        val generation = json.getString("generation")
        require(UUID.fromString(generation).toString() == generation)
        Record(id, tag, url, destination.canonicalPath, size, normalizedSha(json.optString("sha")),
            json.optLong("uploaded"), generation, UpdateDownloads.TransferPhase.valueOf(json.getString("phase")),
            json.optString("error").takeIf { it.isNotBlank() }, json.optLong("legacyId", -1))
    }.getOrNull()
    private fun write(record: Record) {
        val json = JSONObject().put("tag", record.tag).put("url", record.url).put("destination", record.destination).put("size", record.size)
            .put("sha", record.sha ?: "").put("uploaded", record.uploaded).put("generation", record.generation)
            .put("phase", record.phase.name).put("error", record.error ?: "").put("legacyId", record.legacyId)
        check(prefs.edit().putString(record.id.toString(), json.toString()).commit())
    }
    private fun same(record: Record, release: UpdateChecker.ReleaseInfo, destination: File) =
        record.url == release.assetUrl && record.destination == destination.canonicalPath && record.size == release.assetSize &&
            record.sha == normalizedSha(release.assetSha256) && record.uploaded == release.assetUploadedAt
    private fun validate(release: UpdateChecker.ReleaseInfo, destination: File) {
        require(UpdateChecker.isReleaseAssetUrl(release.assetUrl, release.tagName))
        require(release.assetSize in 1..UpdateDelta.MAX_APK_SIZE)
        require(release.assetSha256 == null || normalizedSha(release.assetSha256) != null)
        validateDestination(destination)
    }
    private fun validateDestination(destination: File) {
        val root = File(checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)), "updates").canonicalFile
        require(destination.canonicalFile.parentFile == root && destination.extension == "part")
        require(UUID.fromString(destination.nameWithoutExtension).toString() == destination.nameWithoutExtension)
    }
    private fun owns(transfer: UpdateDownloads.Transfer, url: String, destination: File) =
        transfer.url == url && transfer.token == destination.nameWithoutExtension && transfer.localUri?.let { value ->
            runCatching { val uri = Uri.parse(value); uri.scheme == "file" && File(checkNotNull(uri.path)).canonicalFile == destination.canonicalFile }
                .getOrDefault(false)
        } != false

    private class Stopped : IOException()
    private class WindowEnded : IOException()
    private class Execution(val generation: String) {
        @Volatile var call: Call? = null
        @Volatile var usingMirror: Boolean = false
    }
    companion object {
        internal const val ID_BASE = 1L shl 62
        private const val MAX_RETRIES = 10
        private val lock = Any()
        private val active = ConcurrentHashMap<Long, Execution>()
        private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.MINUTES).retryOnConnectionFailure(false).followSslRedirects(false).build()
        private fun normalizedSha(value: String?) = value?.removePrefix("sha256:")?.lowercase()
            ?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
        private fun workName(id: Long) = "update-transfer-$id"
        internal fun stop(id: Long, generation: String) {
            active[id]?.takeIf { it.generation == generation }?.call?.cancel()
        }
    }
}

/** Ordinary bounded work also runs when the user has disabled notifications. */
class ResumableUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("transfer", -1)
        val generation = inputData.getString("generation") ?: return Result.success()
        if (id < ResumableUpdateBackend.ID_BASE) return Result.success()
        val backend = ResumableUpdateBackend(applicationContext, UpdateDownloads.SystemBackend(applicationContext))
        if (!UpdateDownloads(applicationContext, backend).authorizeTransfer(id)) return Result.success()
        return coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { ResumableUpdateBackend.stop(id, generation) }
            }
            try {
                UpdateTransferNotification.run(this@ResumableUpdateWorker, id) {
                    withContext(Dispatchers.IO) {
                        if (backend.run(id, generation, runAttemptCount) { isStopped }) Result.retry() else Result.success()
                    }
                }
            } finally { cancellation.cancel() }
        }
    }
}
