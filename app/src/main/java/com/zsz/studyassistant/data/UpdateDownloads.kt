package com.zsz.studyassistant.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.zsz.studyassistant.ui.UiLangStore
import com.zsz.studyassistant.ui.stringsFor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Durable handle to an OS-owned transfer. Page/coroutine disposal must never cancel it. */
internal class UpdateDownloads(
    context: Context,
    private val backend: Backend = SystemBackend(context.applicationContext),
    private val verifier: (File, UpdateChecker.ReleaseInfo) -> UpdateChecker.VerifyResult = { file, release ->
        UpdateChecker.verify(context.applicationContext, file, release.assetSha256, release.version)
    },
) {
    private val context = context.applicationContext
    private val prefs get() = context.getSharedPreferences("update_download", Context.MODE_PRIVATE)
    enum class Phase { DOWNLOADING, WAITING, READY, FAILED }
    enum class TransferPhase { QUEUED, RUNNING, WAITING, SUCCESS, FAILED }
    data class Transfer(val id: Long, val url: String, val localUri: String?, val status: TransferPhase,
        val bytes: Long, val total: Long, val token: String)
    interface Backend {
        fun enqueue(release: UpdateChecker.ReleaseInfo, destination: File): Long
        fun query(id: Long): Transfer?
        fun find(url: String, destination: File): Transfer?
        fun remove(id: Long)
    }
    data class Snapshot(val token: String, val release: UpdateChecker.ReleaseInfo, val phase: Phase,
        val progress: Int = -1, val file: File? = null, val errorKey: String? = null)
    private data class Record(val token: String, val release: UpdateChecker.ReleaseInfo,
        val id: Long = -1, val ready: Boolean = false, val error: String? = null)
    companion object {
        private val mutex = Mutex()
        private val handoffScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        private const val MIME_PREFIX = "application/vnd.studyassistant.update-"
    }
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    /** The request handoff itself also survives a page disappearing immediately after the tap. */
    fun enqueue(release: UpdateChecker.ReleaseInfo): kotlinx.coroutines.Deferred<Snapshot> =
        handoffScope.async { start(release) }

    suspend fun start(release: UpdateChecker.ReleaseInfo): Snapshot = locked {
        val uri = Uri.parse(release.assetUrl)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
            uri.path?.startsWith("/${UpdateChecker.REPO}/releases/download/") == true)
        val previous = read()
        // Notes may change without replacing the asset; only transfer identity determines reuse.
        if (previous != null && sameAsset(previous.release, release)) {
            val existing = snapshotLocked(previous)
            if (existing.phase != Phase.FAILED) return@locked existing
        }
        cancelLocked()
        val pending = Record(UUID.randomUUID().toString(), release)
        write(pending) // A crash around enqueue can recover by this exact destination token.
        val destination = partial(pending)
        destination.parentFile?.mkdirs()
        val id = backend.enqueue(release, destination)
        require(id >= 0)
        val record = pending.copy(id = id)
        try { write(record) }
        catch (error: Exception) { backend.remove(id); throw error }
        Snapshot(record.token, release, Phase.DOWNLOADING, 0)
    }

    suspend fun snapshot(): Snapshot? = locked {
        val record = read() ?: return@locked null
        try { snapshotLocked(record) }
        catch (_: Exception) { Snapshot(record.token, record.release, Phase.FAILED, errorKey = "update.downloadUnavailable") }
    }

    suspend fun forInstall(): Snapshot? = locked {
        val record = read() ?: return@locked null
        if (!record.ready) return@locked snapshotLocked(record)
        val file = ready(record)
        if (!valid(file, record.release)) return@locked fail(record, "update.verifyFailed")
        Snapshot(record.token, record.release, Phase.READY, 100, file)
    }

    suspend fun cancel(expectedToken: String? = null) = locked {
        if (expectedToken == null || read()?.token == expectedToken) cancelLocked()
    }

    private fun snapshotLocked(initial: Record): Snapshot {
        if (initial.ready) {
            val file = ready(initial)
            return if (file.isFile) Snapshot(initial.token, initial.release, Phase.READY, 100, file)
            else fail(initial, "update.downloadMissing")
        }
        initial.error?.let { return Snapshot(initial.token, initial.release, Phase.FAILED, errorKey = it) }
        var record = initial
        val transfer = if (record.id < 0) {
            backend.find(record.release.assetUrl, partial(record))?.also {
                if (owns(it, record)) { record = record.copy(id = it.id); write(record) }
            }
        } else backend.query(record.id)
        if (transfer == null || !owns(transfer, record)) {
            return fail(record, "update.downloadMissing", removeTransfer = false)
        }
        val total = transfer.total.takeIf { it > 0 } ?: record.release.assetSize
        val percent = if (total > 0) (transfer.bytes.coerceAtLeast(0).toDouble() / total * 100).toInt().coerceIn(0, 100) else -1
        return when (transfer.status) {
            TransferPhase.QUEUED, TransferPhase.RUNNING -> Snapshot(record.token, record.release, Phase.DOWNLOADING, percent)
            TransferPhase.WAITING -> Snapshot(record.token, record.release, Phase.WAITING, percent)
            TransferPhase.FAILED -> fail(record, "update.downloadInterrupted")
            TransferPhase.SUCCESS -> complete(record)
        }
    }

    private fun complete(record: Record): Snapshot {
        val candidate = checking(record)
        try {
            val source = partial(record)
            if (!source.isFile) return fail(record, "update.downloadMissing")
            if (record.release.assetSize > 0 && source.length() != record.release.assetSize) return fail(record, "update.verifyFailed")
            source.copyTo(candidate, overwrite = true)
            // Verify the private copy, never a mutable external path that will later be copied again.
            if (!valid(candidate, record.release)) return fail(record, "update.verifyFailed")
            val output = ready(record)
            check(candidate.renameTo(output))
            write(record.copy(ready = true, error = null))
            // Failure to tidy an OS row must not invalidate a verified package.
            runCatching { backend.remove(record.id) }
            return Snapshot(record.token, record.release, Phase.READY, 100, output)
        } finally { candidate.delete() }
    }

    private fun valid(file: File, release: UpdateChecker.ReleaseInfo): Boolean =
        file.isFile && (release.assetSize <= 0 || file.length() == release.assetSize) && verifier(file, release).ok

    private fun fail(record: Record, key: String, removeTransfer: Boolean = true): Snapshot {
        if (removeTransfer) removeOwned(record)
        partial(record).delete(); ready(record).delete(); checking(record).delete()
        write(record.copy(ready = false, error = key))
        return Snapshot(record.token, record.release, Phase.FAILED, errorKey = key)
    }
    private fun cancelLocked() {
        val record = read() ?: return
        removeOwned(record)
        partial(record).delete(); ready(record).delete(); checking(record).delete()
        check(prefs.edit().remove("session").commit())
    }
    private fun removeOwned(record: Record) {
        val current = if (record.id >= 0) backend.query(record.id) else backend.find(record.release.assetUrl, partial(record))
        if (current != null && owns(current, record)) backend.remove(current.id)
    }
    private fun owns(transfer: Transfer, record: Record): Boolean = transfer.url == record.release.assetUrl && transfer.token == record.token &&
        (transfer.localUri == null || runCatching {
            val uri = Uri.parse(transfer.localUri)
            uri.scheme == "file" && File(checkNotNull(uri.path)).canonicalFile == partial(record).canonicalFile
        }.getOrDefault(false))

    private fun partial(record: Record): File {
        val root = checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
        return File(root, "updates/${record.token}.part")
    }
    private fun ready(record: Record) = File(UpdateChecker.downloadDir(context), "${record.token}.apk")
    private fun checking(record: Record) = File(UpdateChecker.downloadDir(context), "${record.token}.checking")
    private fun sameAsset(a: UpdateChecker.ReleaseInfo, b: UpdateChecker.ReleaseInfo) =
        a.tagName == b.tagName && a.version == b.version && a.assetName == b.assetName && a.assetUrl == b.assetUrl &&
            a.assetSha256 == b.assetSha256 && a.assetSize == b.assetSize && a.assetUploadedAt == b.assetUploadedAt

    private fun read(): Record? = runCatching {
        val stored = prefs.getString("session", null) ?: return null
        val obj = JSONObject(stored)
        val token = obj.getString("token")
        require(UUID.fromString(token).toString() == token)
        Record(token, UpdateChecker.ReleaseInfo(obj.getString("tag"), obj.getString("version"), obj.getString("notes"),
            obj.getString("name"), obj.getString("url"), obj.optString("sha").takeIf { it.isNotBlank() },
            obj.getLong("size"), obj.getLong("uploaded")), obj.getLong("id"), obj.optBoolean("ready"),
            obj.optString("error").takeIf { it in setOf("update.verifyFailed", "update.downloadMissing", "update.downloadInterrupted") })
    }.getOrNull()
    private fun write(record: Record) {
        val rel = record.release
        val obj = JSONObject().put("token", record.token).put("id", record.id).put("ready", record.ready)
            .put("error", record.error ?: "").put("tag", rel.tagName).put("version", rel.version).put("notes", rel.notes)
            .put("name", rel.assetName).put("url", rel.assetUrl).put("sha", rel.assetSha256 ?: "")
            .put("size", rel.assetSize).put("uploaded", rel.assetUploadedAt)
        check(prefs.edit().putString("session", obj.toString()).commit())
    }

    private class SystemBackend(private val context: Context) : Backend {
        private val manager get() = checkNotNull(context.getSystemService(DownloadManager::class.java))
        override fun enqueue(release: UpdateChecker.ReleaseInfo, destination: File): Long {
            val strings = stringsFor(UiLangStore.load(context))
            val request = DownloadManager.Request(Uri.parse(release.assetUrl))
                // Do not inherit the system user-agent's device/build identifiers.
                .addRequestHeader("User-Agent", "StudyAssistant")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/${destination.name}")
                // Never advertise an unverified .apk to another app or launch an installer on completion.
                // MIME is an immediately queryable ownership tag, including before COLUMN_LOCAL_URI exists.
                .setMimeType("$MIME_PREFIX${destination.nameWithoutExtension}").setTitle("Study Assistant v${release.version}")
                .setDescription(strings["update.background"])
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
            return manager.enqueue(request)
        }
        override fun query(id: Long): Transfer? = queryRows(DownloadManager.Query().setFilterById(id)).firstOrNull()
        override fun find(url: String, destination: File): Transfer? = queryRows(DownloadManager.Query()).firstOrNull {
            it.url == url && it.token == destination.nameWithoutExtension && (it.localUri == null ||
                runCatching { File(checkNotNull(Uri.parse(it.localUri).path)).canonicalFile == destination.canonicalFile }.getOrDefault(false))
        }
        override fun remove(id: Long) { manager.remove(id) }
        private fun queryRows(query: DownloadManager.Query): List<Transfer> = manager.query(query)?.use { cursor ->
            val result = mutableListOf<Transfer>()
            while (cursor.moveToNext()) {
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                fun text(column: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(column))
                val state = when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_PENDING -> TransferPhase.QUEUED
                    DownloadManager.STATUS_RUNNING -> TransferPhase.RUNNING
                    DownloadManager.STATUS_PAUSED -> TransferPhase.WAITING
                    DownloadManager.STATUS_SUCCESSFUL -> TransferPhase.SUCCESS
                    else -> TransferPhase.FAILED
                }
                result += Transfer(number(DownloadManager.COLUMN_ID), text(DownloadManager.COLUMN_URI).orEmpty(),
                    text(DownloadManager.COLUMN_LOCAL_URI), state, number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    text(DownloadManager.COLUMN_MEDIA_TYPE)?.takeIf { it.startsWith(MIME_PREFIX) }?.removePrefix(MIME_PREFIX).orEmpty())
            }
            result
        } ?: emptyList()
    }
}
