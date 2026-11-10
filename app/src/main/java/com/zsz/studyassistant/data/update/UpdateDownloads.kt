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
    private val installedApk: () -> File = { File(context.applicationInfo.sourceDir) },
    private val verifier: (File, UpdateChecker.ReleaseInfo) -> UpdateChecker.VerifyResult = { file, release ->
        UpdateChecker.verify(context.applicationContext, file, release.assetSha256, release.displayVersion, release.minSdk, release.versionCode)
    },
) {
    private val context = context.applicationContext
    private val prefs get() = context.getSharedPreferences("update_download", Context.MODE_PRIVATE)
    enum class Phase { DOWNLOADING, WAITING, READY, FAILED }
    enum class TransferPhase { QUEUED, RUNNING, WAITING, SUCCESS, FAILED }
    data class Transfer(val id: Long, val url: String, val localUri: String?, val status: TransferPhase,
        val bytes: Long, val total: Long, val token: String)
    interface Backend {
        fun enqueue(release: UpdateChecker.ReleaseInfo, destination: File, appNotification: Boolean): Long
        fun query(id: Long): Transfer?
        fun find(url: String, destination: File): Transfer?
        fun remove(id: Long)
    }
    data class Snapshot(val token: String, val release: UpdateChecker.ReleaseInfo, val phase: Phase,
        val progress: Int = -1, val file: File? = null, val errorKey: String? = null, val appNotification: Boolean = false)
    private data class Record(val token: String, val release: UpdateChecker.ReleaseInfo,
        val id: Long = -1, val ready: Boolean = false, val error: String? = null, val appNotification: Boolean = false,
        val delta: UpdateDeltaInfo? = null, val transferToken: String = token, val enqueuePending: Boolean = false)
    companion object {
        private val mutex = Mutex()
        private val handoffScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        private const val MIME_PREFIX = "application/vnd.studyassistant.update-"
    }
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    /** The request handoff itself also survives a page disappearing immediately after the tap. */
    fun enqueue(release: UpdateChecker.ReleaseInfo, notification: UpdateDownloadMonitor.Request? = null,
        forceFull: Boolean = false): kotlinx.coroutines.Deferred<Snapshot> =
        handoffScope.async {
            try {
                val ownsNotification = notification != null && kotlinx.coroutines.withTimeoutOrNull(3000) { notification.ready.await() } == true
                start(release, ownsNotification, forceFull).also { notification?.bound(it) }
            } catch (error: Throwable) { notification?.aborted(); throw error }
        }

    /** Shared by the size preview and transfer selection; APK hashing never runs on the UI thread. */
    suspend fun matchingDelta(release: UpdateChecker.ReleaseInfo): UpdateDeltaInfo? = withContext(Dispatchers.IO) {
        selectDelta(release)
    }

    private fun selectDelta(release: UpdateChecker.ReleaseInfo): UpdateDeltaInfo? = runCatching {
        if (UpdateChecker.compatibilityError(release) != null || release.deltas.isEmpty()) return@runCatching null
        val base = installedApk()
        release.deltas.firstOrNull { eligible(it, release) && UpdateDelta.matches(base, it.baseSize, it.baseSha256) }
    }.getOrNull()

    suspend fun start(release: UpdateChecker.ReleaseInfo, appNotification: Boolean = false,
        forceFull: Boolean = false): Snapshot = locked {
        UpdateChecker.compatibilityError(release)?.let { key ->
            return@locked status(Record(UUID.randomUUID().toString(), release), Phase.FAILED, errorKey = key)
        }
        require(UpdateChecker.isReleaseAssetUrl(release.assetUrl, release.tagName))
        val previous = read()
        val target = release.copy(mandatory = release.mandatory ||
            (previous != null && sameAsset(previous.release, release) && previous.release.mandatory))
        // Notes may change without replacing the asset; only transfer identity determines reuse.
        if (previous != null && sameAsset(previous.release, release) && (!forceFull || previous.delta == null)) {
            // A subsequently discovered mandatory requirement must survive process recreation too.
            val updated = previous.copy(release = previous.release.copy(mandatory = target.mandatory))
            write(updated)
            val existing = snapshotLocked(updated)
            if (existing.phase != Phase.FAILED) return@locked existing
        }
        cancelLocked()
        val selected = if (forceFull) null else selectDelta(target)
        val pending = Record(UUID.randomUUID().toString(), target, appNotification = appNotification,
            delta = selected, enqueuePending = true)
        try { enqueueLocked(pending) }
        catch (error: Exception) {
            if (pending.delta == null) throw error
            fail(pending, "update.downloadInterrupted")
        }
    }

    private fun enqueueLocked(initial: Record): Snapshot {
        var pending = initial
        write(pending) // A crash around enqueue can recover by this exact destination token.
        val destination = partial(pending)
        destination.parentFile?.mkdirs()
        // The caller has already established that this transfer token has no OS row.
        // An interrupted enqueue may leave our own destination behind.
        if (destination.exists()) check(destination.delete())
        val id = try { backend.enqueue(transferRelease(pending), destination, pending.appNotification) }
        catch (error: SecurityException) {
            if (!pending.appNotification) throw error
            pending = pending.copy(appNotification = false); write(pending)
            backend.enqueue(transferRelease(pending), destination, false)
        }
        require(id >= 0)
        val record = pending.copy(id = id, enqueuePending = false)
        try { write(record) }
        catch (error: Exception) { backend.remove(id); throw error }
        return status(record, Phase.DOWNLOADING, 0)
    }

    suspend fun snapshot(): Snapshot? = locked {
        val record = read() ?: return@locked null
        try { snapshotLocked(record) }
        catch (_: Exception) { status(record, Phase.WAITING, errorKey = "update.downloadUnavailable") }
    }

    suspend fun forInstall(): Snapshot? = locked {
        val record = read() ?: return@locked null
        UpdateChecker.compatibilityError(record.release)?.let { return@locked fail(record, it) }
        if (!record.ready) return@locked snapshotLocked(record)
        val file = ready(record)
        if (!valid(file, record.release)) return@locked fail(record, "update.verifyFailed")
        status(record, Phase.READY, 100, file)
    }

    suspend fun cancel(expectedToken: String? = null) = locked {
        if (expectedToken == null || read()?.token == expectedToken) cancelLocked()
    }

    private fun snapshotLocked(initial: Record): Snapshot {
        UpdateChecker.compatibilityError(initial.release)?.let { return fail(initial, it) }
        if (initial.ready) {
            val file = ready(initial)
            return if (file.isFile) status(initial, Phase.READY, 100, file)
            else fail(initial, "update.downloadMissing")
        }
        initial.error?.let { return status(initial, Phase.FAILED, errorKey = it) }
        var record = initial
        val transfer = if (record.id < 0) {
            backend.find(transferRelease(record).assetUrl, partial(record))?.also {
                if (owns(it, record)) { record = record.copy(id = it.id, enqueuePending = false); write(record) }
            }
        } else backend.query(record.id)
        if (transfer == null || !owns(transfer, record)) {
            if (transfer == null && record.id < 0 && record.enqueuePending) {
                return try { enqueueLocked(record) } catch (error: Exception) {
                    if (record.delta == null) throw error
                    fail(record, "update.downloadInterrupted")
                }
            }
            return fail(record, "update.downloadMissing", removeTransfer = false)
        }
        val expectedSize = transferRelease(record).assetSize
        if (expectedSize > 0 && (transfer.bytes > expectedSize || transfer.total > expectedSize))
            return fail(record, "update.verifyFailed")
        val total = transfer.total.takeIf { it > 0 } ?: expectedSize
        val percent = if (total > 0) (transfer.bytes.coerceAtLeast(0).toDouble() / total * 100).toInt().coerceIn(0, 100) else -1
        return when (transfer.status) {
            TransferPhase.QUEUED, TransferPhase.RUNNING -> status(record, Phase.DOWNLOADING, percent)
            TransferPhase.WAITING -> status(record, Phase.WAITING, percent)
            TransferPhase.FAILED -> fail(record, "update.downloadInterrupted")
            TransferPhase.SUCCESS -> complete(record)
        }
    }

    private fun complete(record: Record): Snapshot {
        val candidate = checking(record)
        val result = try {
            val source = partial(record)
            require(source.isFile)
            val delta = record.delta
            if (delta != null) {
                // Freeze the patch in private storage before checking it; external files may change.
                require(source.length() == delta.patchSize)
                val patch = patchChecking(record)
                copyCandidate(source, patch, delta.patchSize)
                val base = installedApk()
                require(eligible(delta, record.release) && UpdateDelta.matches(patch, delta.patchSize, delta.patchSha256) &&
                    UpdateDelta.matches(base, delta.baseSize, delta.baseSha256))
                UpdateDelta.applyGdiff(base, patch, candidate, record.release.assetSize)
                require(UpdateDelta.matches(candidate, record.release.assetSize, checkNotNull(record.release.assetSha256)))
            } else {
                require(record.release.assetSize <= 0 || source.length() == record.release.assetSize)
                copyCandidate(source, candidate, record.release.assetSize)
            }
            // Verify the private copy, never a mutable external path that will later be copied again.
            require(valid(candidate, record.release))
            val output = ready(record)
            check(candidate.renameTo(output))
            write(record.copy(ready = true, error = null))
            // Failure to tidy an OS row must not invalidate a verified package.
            runCatching { backend.remove(record.id) }
            status(record, Phase.READY, 100, output)
        } catch (_: Exception) { null }
        finally { candidate.delete(); patchChecking(record).delete() }
        // Outside the reconstruction catch: never restart fallback if its OS handoff itself fails.
        return result ?: fail(record, "update.verifyFailed")
    }

    private fun copyCandidate(source: File, destination: File, expectedSize: Long) {
        val limit = if (expectedSize > 0) expectedSize else UpdateDelta.MAX_APK_SIZE
        require(limit <= UpdateDelta.MAX_APK_SIZE)
        source.inputStream().buffered().use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count.toLong() <= limit - copied)
                    output.write(buffer, 0, count)
                    copied += count
                }
                require(expectedSize <= 0 || copied == expectedSize)
            }
        }
    }

    private fun valid(file: File, release: UpdateChecker.ReleaseInfo): Boolean =
        file.isFile && (release.assetSize <= 0 || file.length() == release.assetSize) && verifier(file, release).ok

    private fun fail(record: Record, key: String, removeTransfer: Boolean = true): Snapshot {
        if (removeTransfer) removeOwned(record)
        partial(record).delete(); ready(record).delete(); checking(record).delete(); patchChecking(record).delete()
        if (record.delta != null && key !in setOf("update.incompatible", "update.compatibilityUnknown")) {
            // Keep the public task token for notifications/cancel; never reuse the patch transfer identity.
            val fallback = record.copy(delta = null, id = -1, ready = false, error = null,
                transferToken = UUID.randomUUID().toString(), enqueuePending = true)
            write(fallback) // Persist the one-way transition before performing the new OS handoff.
            return enqueueLocked(fallback)
        }
        write(record.copy(ready = false, error = key))
        return status(record, Phase.FAILED, errorKey = key)
    }
    private fun cancelLocked() {
        val record = read() ?: return
        removeOwned(record)
        partial(record).delete(); ready(record).delete(); checking(record).delete(); patchChecking(record).delete()
        check(prefs.edit().remove("session").commit())
    }
    private fun removeOwned(record: Record) {
        val current = if (record.id >= 0) backend.query(record.id) else backend.find(transferRelease(record).assetUrl, partial(record))
        if (current != null && owns(current, record)) backend.remove(current.id)
    }
    private fun owns(transfer: Transfer, record: Record): Boolean = transfer.url == transferRelease(record).assetUrl && transfer.token == record.transferToken &&
        (transfer.localUri == null || runCatching {
            val uri = Uri.parse(transfer.localUri)
            uri.scheme == "file" && File(checkNotNull(uri.path)).canonicalFile == partial(record).canonicalFile
        }.getOrDefault(false))

    private fun status(record: Record, phase: Phase, progress: Int = -1, file: File? = null, errorKey: String? = null) =
        Snapshot(record.token, record.release, phase, progress, file, errorKey, record.appNotification)

    private fun partial(record: Record): File {
        val root = checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
        return File(root, "updates/${record.transferToken}.part")
    }
    private fun ready(record: Record) = File(UpdateChecker.downloadDir(context), "${record.token}.apk")
    private fun checking(record: Record) = File(UpdateChecker.downloadDir(context), "${record.token}.checking")
    private fun patchChecking(record: Record) = File(UpdateChecker.downloadDir(context), "${record.token}.patch-checking")
    private fun transferRelease(record: Record): UpdateChecker.ReleaseInfo = record.delta?.let {
        record.release.copy(assetName = it.patchName, assetUrl = it.patchUrl, assetSha256 = it.patchSha256, assetSize = it.patchSize)
    } ?: record.release
    private fun eligible(delta: UpdateDeltaInfo, release: UpdateChecker.ReleaseInfo): Boolean =
        delta.format == UpdateDelta.FORMAT && delta.baseSize in 1..UpdateDelta.MAX_APK_SIZE &&
            release.assetSize in 1..UpdateDelta.MAX_APK_SIZE && delta.patchSize in 1 until release.assetSize &&
            listOf(delta.baseSha256, delta.patchSha256, release.assetSha256.orEmpty()).all { Regex("[0-9a-fA-F]{64}").matches(it) } &&
            Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}").matches(delta.patchName) &&
            UpdateChecker.isReleaseAssetUrl(delta.patchUrl, release.tagName) &&
            runCatching { java.net.URI(delta.patchUrl).path.substringAfterLast('/') == delta.patchName }.getOrDefault(false)
    private fun sameAsset(a: UpdateChecker.ReleaseInfo, b: UpdateChecker.ReleaseInfo) =
        a.tagName == b.tagName && a.version == b.version && a.assetName == b.assetName && a.assetUrl == b.assetUrl &&
            a.assetSha256 == b.assetSha256 && a.assetSize == b.assetSize && a.assetUploadedAt == b.assetUploadedAt &&
            a.minSdk == b.minSdk && a.versionCode == b.versionCode

    private fun read(): Record? = runCatching {
        val stored = prefs.getString("session", null) ?: return null
        val obj = JSONObject(stored)
        val token = obj.getString("token")
        require(UUID.fromString(token).toString() == token)
        val transferToken = obj.optString("transferToken", token)
        require(UUID.fromString(transferToken).toString() == transferToken)
        val release = UpdateChecker.ReleaseInfo(obj.getString("tag"), obj.getString("version"), obj.getString("notes"),
            obj.getString("name"), obj.getString("url"), obj.optString("sha").takeIf { it.isNotBlank() },
            obj.getLong("size"), obj.getLong("uploaded"),
            if (obj.has("minSdk")) obj.optInt("minSdk").takeIf { it > 0 } else UpdateChecker.legacyMinSdk(obj.getString("version")),
            obj.optLong("versionCode").takeIf { it > 0 },
            channel = if (obj.optString("channel") == "beta") UpdateChecker.Channel.BETA else UpdateChecker.Channel.STABLE,
            mandatory = obj.optBoolean("mandatory", false))
        require(UpdateChecker.isReleaseAssetUrl(release.assetUrl, release.tagName))
        val delta = obj.optJSONObject("delta")?.let {
            UpdateDeltaInfo(it.getString("format"), it.getString("baseSha256"), it.getLong("baseSize"),
                it.getString("patchName"), it.getString("patchUrl"), it.getString("patchSha256"), it.getLong("patchSize"))
                .also { info -> require(eligible(info, release)) }
        }
        Record(token, release, obj.getLong("id"), obj.optBoolean("ready"),
            obj.optString("error").takeIf { it in setOf("update.verifyFailed", "update.downloadMissing", "update.downloadInterrupted",
                "update.incompatible", "update.compatibilityUnknown") }, obj.optBoolean("appNotification", false),
            delta, transferToken, obj.optBoolean("enqueuePending", false))
    }.getOrNull()
    private fun write(record: Record) {
        val rel = record.release
        val obj = JSONObject().put("token", record.token).put("id", record.id).put("ready", record.ready)
            .put("appNotification", record.appNotification).put("error", record.error ?: "").put("tag", rel.tagName).put("version", rel.version).put("notes", rel.notes)
            .put("name", rel.assetName).put("url", rel.assetUrl).put("sha", rel.assetSha256 ?: "")
            .put("size", rel.assetSize).put("uploaded", rel.assetUploadedAt)
            .put("minSdk", rel.minSdk ?: 0).put("versionCode", rel.versionCode ?: 0)
            .put("channel", if (rel.channel == UpdateChecker.Channel.BETA) "beta" else "stable")
            .put("mandatory", rel.mandatory).put("transferToken", record.transferToken).put("enqueuePending", record.enqueuePending)
        record.delta?.let {
            obj.put("delta", JSONObject().put("format", it.format).put("baseSha256", it.baseSha256).put("baseSize", it.baseSize)
                .put("patchName", it.patchName).put("patchUrl", it.patchUrl).put("patchSha256", it.patchSha256).put("patchSize", it.patchSize))
        }
        check(prefs.edit().putString("session", obj.toString()).commit())
    }

    private class SystemBackend(private val context: Context) : Backend {
        private val manager get() = checkNotNull(context.getSystemService(DownloadManager::class.java))
        override fun enqueue(release: UpdateChecker.ReleaseInfo, destination: File, appNotification: Boolean): Long {
            val strings = stringsFor(UiLangStore.load(context))
            val request = DownloadManager.Request(Uri.parse(release.assetUrl))
                // Do not inherit the system user-agent's device/build identifiers.
                .addRequestHeader("User-Agent", "StudyAssistant")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/${destination.name}")
                // Never advertise an unverified .apk to another app or launch an installer on completion.
                // MIME is an immediately queryable ownership tag, including before COLUMN_LOCAL_URI exists.
                .setMimeType("$MIME_PREFIX${destination.nameWithoutExtension}").setTitle("Study Assistant v${release.displayVersion}")
                .setDescription(strings["update.background"])
                .setNotificationVisibility(if (appNotification) DownloadManager.Request.VISIBILITY_HIDDEN else DownloadManager.Request.VISIBILITY_VISIBLE)
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
