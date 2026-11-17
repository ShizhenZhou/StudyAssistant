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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Durable handle to a background transfer. Page/coroutine disposal must never cancel it. */
internal class UpdateDownloads(
    context: Context,
    private val backend: Backend = ResumableUpdateBackend(context.applicationContext, SystemBackend(context.applicationContext)),
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
        val bytes: Long, val total: Long, val token: String, val errorKey: String? = null,
        val usingMirror: Boolean = false)
    interface Backend {
        fun enqueue(release: UpdateChecker.ReleaseInfo, destination: File, appNotification: Boolean): Long
        fun query(id: Long): Transfer?
        fun find(url: String, destination: File): Transfer?
        fun remove(id: Long)
        fun resume(release: UpdateChecker.ReleaseInfo, destination: File, id: Long,
            appNotification: Boolean): Long? = null
    }
    data class Snapshot(val token: String, val release: UpdateChecker.ReleaseInfo, val phase: Phase,
        val progress: Int = -1, val file: File? = null, val errorKey: String? = null, val appNotification: Boolean = false,
        val resumable: Boolean = false, val usingMirror: Boolean = false, val currentPackageSize: Long = 0)
    private data class Record(val token: String, val release: UpdateChecker.ReleaseInfo,
        val id: Long = -1, val ready: Boolean = false, val error: String? = null, val appNotification: Boolean = false,
        val delta: UpdateDeltaInfo? = null, val transferToken: String = token, val enqueuePending: Boolean = false,
        val steps: List<UpdateDeltaStep> = emptyList(), val stepIndex: Int = 0)
    companion object {
        private val digestCache = InstalledApkDigestCache()
        private val mutex = Mutex()
        private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val cancelRevision = java.util.concurrent.atomic.AtomicLong()
        private val handoffScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        private const val MIME_PREFIX = "application/vnd.studyassistant.update-"
    }
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    /** The request handoff itself also survives a page disappearing immediately after the tap. */
    fun enqueue(release: UpdateChecker.ReleaseInfo, notification: UpdateDownloadMonitor.Request? = null,
        forceFull: Boolean = false): kotlinx.coroutines.Deferred<Snapshot> {
        val revision = cancelRevision.get()
        return handoffScope.async {
            try {
                val ownsNotification = notification != null && kotlinx.coroutines.withTimeoutOrNull(3000) { notification.ready.await() } == true
                start(release, ownsNotification, forceFull, revision).also { notification?.bound(it) }
            } catch (error: Throwable) { notification?.aborted(); throw error }
        }
    }

    /** Hash once on IO; reuse the release-history graph already fetched during update checking. */
    suspend fun matchingPlan(release: UpdateChecker.ReleaseInfo): UpdateDeltaPlan? = withContext(Dispatchers.IO) {
        val operation = kotlinx.coroutines.currentCoroutineContext()
        selectPlan(release) { operation.ensureActive() }
    }

    private fun selectPlan(release: UpdateChecker.ReleaseInfo, check: () -> Unit = {}): UpdateDeltaPlan? = try {
        if (UpdateChecker.compatibilityError(release) != null || release.assetSha256 == null) null
        else {
            val direct = release.deltas.map { UpdateDeltaStep(it, release.assetSha256, release.assetSize, release.tagName) }
            val edges = direct + release.deltaHistory
            if (edges.isEmpty()) null else {
                val base = installedApk()
                check()
                val digest = digestCache.read(base, UpdateChecker.installedLastUpdateTime(context)) {
                    UpdateDelta.digest(it, check)
                }
                check()
                UpdateDeltaPlanner.find(checkNotNull(digest), base.length(), release.assetSha256, release.assetSize, edges, check)
            }
        }
    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
      catch (_: Exception) { null }

    suspend fun start(release: UpdateChecker.ReleaseInfo, appNotification: Boolean = false,
        forceFull: Boolean = false, expectedRevision: Long = cancelRevision.get()): Snapshot = locked {
        if (expectedRevision != cancelRevision.get()) throw TransferCancelled()
        UpdateChecker.compatibilityError(release)?.let { key ->
            return@locked status(Record(UUID.randomUUID().toString(), release), Phase.FAILED, errorKey = key)
        }
        require(UpdateChecker.isReleaseAssetUrl(release.assetUrl, release.tagName))
        val previous = readCompatible()
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
        var pending = Record(UUID.randomUUID().toString(), target, appNotification = appNotification, enqueuePending = true)
        write(pending)
        try {
            val selected = if (forceFull) null else selectPlan(target) {
                checkTransfer(pending)
                if (expectedRevision != cancelRevision.get()) throw TransferCancelled()
            }
            pending = pending.copy(delta = selected?.steps?.firstOrNull()?.delta, steps = selected?.steps.orEmpty())
            if (expectedRevision != cancelRevision.get()) throw TransferCancelled()
            checkTransfer(pending)
            enqueueLocked(pending)
        } catch (error: TransferCancelled) { cancelLocked(); throw error }
          catch (error: Exception) {
            if (UpdateStorage.isInsufficient(error)) return@locked pauseForStorage(pending)
            if (pending.delta == null) throw error
            fail(pending, "update.downloadInterrupted")
        }
    }

    private fun enqueueLocked(initial: Record): Snapshot {
        checkTransfer(initial)
        var pending = initial
        write(pending) // A crash around enqueue can recover by this exact destination token.
        if (pending.appNotification && backend is ResumableUpdateBackend) {
            runCatching { UpdateDownloadNotifications.postTerminal(context,
                UpdateDownloadNotifications.build(context, status(pending, Phase.WAITING))) }
        }
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
        val record = readCompatible() ?: return@locked null
        try { snapshotLocked(record) }
        catch (_: TransferCancelled) { null }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            if (UpdateStorage.isInsufficient(error)) pauseForStorage(record)
            else status(record, Phase.WAITING, errorKey = "update.downloadUnavailable")
        }
    }

    /** A worker must never observe, advance or publish a replacement transfer. */
    internal suspend fun snapshotForTransfer(id: Long): Snapshot? = locked {
        val record = readCompatible()?.takeIf { it.id == id } ?: return@locked null
        try { snapshotLocked(record) }
        catch (_: TransferCancelled) { null }
    }

    /** Resume only the displayed transfer; a stale tap cannot affect a replacement task. */
    fun resume(expectedToken: String, restart: Boolean = false): kotlinx.coroutines.Deferred<Snapshot?> {
        val revision = cancelRevision.get()
        return handoffScope.async {
            locked {
                val record = readCompatible()?.takeIf { it.token == expectedToken } ?: return@locked null
                if (revision != cancelRevision.get()) throw TransferCancelled()
                checkTransfer(record)
                val current = snapshotLocked(record)
                if (current.phase != Phase.WAITING) return@locked current
                if (record.error == UpdateStorage.ERROR_KEY && record.id < 0) {
                    val pending = record.copy(error = null)
                    return@locked try { enqueueLocked(pending) } catch (error: Exception) {
                        if (UpdateStorage.isInsufficient(error)) pauseForStorage(pending) else throw error
                    }
                }
                val transfer = backend.query(record.id)
                if (transfer == null || !owns(transfer, record)) return@locked current
                if (!restart && record.error == UpdateStorage.ERROR_KEY && transfer.status == TransferPhase.SUCCESS) {
                    val retry = record.copy(error = null)
                    write(retry)
                    return@locked complete(retry)
                }
                if (restart) {
                    // Keep verified earlier delta stages; discard only the current partial on request.
                    val pending = record.copy(id = -1, enqueuePending = true, error = null)
                    write(pending)
                    backend.remove(record.id)
                    return@locked enqueueLocked(pending)
                }
                val id = try { backend.resume(transferRelease(record), partial(record), record.id, record.appNotification) }
                    catch (error: Exception) {
                        if (UpdateStorage.isInsufficient(error)) return@locked pauseForStorage(record)
                        throw error
                    }
                    ?: return@locked current.copy(errorKey = "update.resumeUnavailable")
                val resumed = record.copy(id = id, enqueuePending = false, error = null)
                write(resumed)
                snapshotLocked(resumed)
            }
        }
    }

    suspend fun forInstall(): Snapshot? = locked {
        val record = readCompatible() ?: return@locked null
        UpdateChecker.compatibilityError(record.release)?.let { return@locked fail(record, it) }
        if (!record.ready) return@locked snapshotLocked(record)
        val file = ready(record)
        if (!valid(file, record.release)) return@locked fail(record, "update.verifyFailed")
        status(record, Phase.READY, 100, file)
    }

    private class TransferCancelled : kotlinx.coroutines.CancellationException()
    private fun checkTransfer(record: Record) { if (record.token in cancelled) throw TransferCancelled() }

    suspend fun cancel(expectedToken: String? = null) {
        if (expectedToken == null) cancelRevision.incrementAndGet()
        withContext(Dispatchers.IO) {
        // Signal before waiting for the IO lock, so reconstruction can stop at its next chunk.
        val token = expectedToken ?: read()?.token ?: return@withContext
        cancelled.add(token)
        try {
            mutex.withLock { if (read()?.token == token) cancelLocked() }
        } finally { cancelled.remove(token) }
        }
    }

    private fun snapshotLocked(initial: Record): Snapshot {
        checkTransfer(initial)
        cleanupPreviousSteps(initial)
        UpdateChecker.compatibilityError(initial.release)?.let { return fail(initial, it) }
        if (initial.ready) {
            val file = ready(initial)
            return if (file.isFile) status(initial, Phase.READY, 100, file)
            else fail(initial, "update.downloadMissing")
        }
        initial.error?.let { return status(initial,
            if (it == UpdateStorage.ERROR_KEY) Phase.WAITING else Phase.FAILED, errorKey = it) }
        var record = initial
        val transfer = if (record.id < 0) {
            backend.find(transferRelease(record).assetUrl, partial(record))?.also {
                if (owns(it, record)) { record = record.copy(id = it.id, enqueuePending = false); write(record) }
            }
        } else backend.query(record.id)
        if (transfer == null || !owns(transfer, record)) {
            if (transfer == null && record.id < 0 && record.enqueuePending) {
                return try { enqueueLocked(record) } catch (error: Exception) {
                    if (UpdateStorage.isInsufficient(error)) return pauseForStorage(record)
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
        val completed = record.steps.take(record.stepIndex).sumOf { it.delta.patchSize }
        val totalDownload = record.steps.takeIf { it.isNotEmpty() }?.sumOf { it.delta.patchSize } ?: total
        val percent = if (totalDownload > 0) ((completed + transfer.bytes.coerceAtLeast(0)).toDouble() / totalDownload * 100)
            .toInt().coerceIn(0, 100) else -1
        return when (transfer.status) {
            TransferPhase.QUEUED, TransferPhase.RUNNING -> status(record, Phase.DOWNLOADING, percent)
                .copy(usingMirror = transfer.status == TransferPhase.RUNNING && transfer.usingMirror)
            TransferPhase.WAITING -> status(record, Phase.WAITING, percent, errorKey = transfer.errorKey)
            TransferPhase.FAILED -> fail(record, transfer.errorKey ?: "update.downloadInterrupted")
            TransferPhase.SUCCESS -> complete(record)
        }
    }

    private fun complete(record: Record): Snapshot {
        val candidate = checking(record)
        val ensureTransfer = { checkTransfer(record) }
        val result = try {
            ensureTransfer()
            val source = partial(record)
            require(source.isFile)
            val delta = record.delta
            if (delta != null) {
                // Freeze the patch in private storage before checking it; external files may change.
                require(source.length() == delta.patchSize)
                val patch = patchChecking(record)
                copyCandidate(source, patch, delta.patchSize, ensureTransfer)
                val step = record.steps.getOrNull(record.stepIndex)
                    ?: UpdateDeltaStep(delta, checkNotNull(record.release.assetSha256), record.release.assetSize, record.release.tagName)
                val base = if (record.stepIndex > 0) intermediate(record, record.stepIndex - 1) else installedApk()
                require(step.valid() && UpdateDelta.matches(patch, delta.patchSize, delta.patchSha256, ensureTransfer) &&
                    UpdateDelta.matches(base, delta.baseSize, delta.baseSha256, ensureTransfer))
                UpdateDelta.applyPatch(base, patch, candidate, step.targetSize, delta.format, ensureTransfer)
                require(UpdateDelta.matches(candidate, step.targetSize, step.targetSha256, ensureTransfer))
                if (record.stepIndex + 1 < record.steps.size) {
                    ensureTransfer()
                    check(candidate.renameTo(intermediate(record, record.stepIndex)))
                    val nextIndex = record.stepIndex + 1
                    val next = record.copy(id = -1, delta = record.steps[nextIndex].delta, stepIndex = nextIndex,
                        transferToken = stepToken(record.token, nextIndex), enqueuePending = true)
                    write(next) // Persist the verified intermediate before starting the next transfer.
                    cleanupPreviousSteps(next)
                    return try { enqueueLocked(next) } catch (error: Exception) {
                        // The old download is retired; retain the newly persisted stage on ENOSPC.
                        if (UpdateStorage.isInsufficient(error)) return pauseForStorage(next)
                        throw error
                    }
                }
            } else {
                require(record.release.assetSize <= 0 || source.length() == record.release.assetSize)
                copyCandidate(source, candidate, record.release.assetSize, ensureTransfer)
            }
            // Verify the private copy, never a mutable external path that will later be copied again.
            ensureTransfer()
            require(valid(candidate, record.release))
            ensureTransfer()
            val output = ready(record)
            check(candidate.renameTo(output))
            write(record.copy(ready = true, error = null))
            // Failure to tidy an OS row must not invalidate a verified package.
            runCatching { backend.remove(record.id) }
            cleanupIntermediates(record)
            status(record, Phase.READY, 100, output)
        } catch (error: TransferCancelled) { cancelLocked(); throw error }
          catch (error: Exception) {
            if (UpdateStorage.isInsufficient(error)) return pauseForStorage(record)
            null
        }
        finally { candidate.delete(); patchChecking(record).delete() }
        // Outside the reconstruction catch: never restart fallback if its OS handoff itself fails.
        ensureTransfer()
        return result ?: fail(record, "update.verifyFailed")
    }

    private fun copyCandidate(source: File, destination: File, expectedSize: Long, check: () -> Unit) {
        val limit = if (expectedSize > 0) expectedSize else UpdateDelta.MAX_APK_SIZE
        require(limit <= UpdateDelta.MAX_APK_SIZE)
        UpdateStorage.requireSpace(destination, if (expectedSize > 0) expectedSize else source.length())
        source.inputStream().buffered().use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    check()
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
        checkTransfer(record)
        if (key == UpdateStorage.ERROR_KEY) return pauseForStorage(record)
        if (removeTransfer) removeOwned(record)
        cleanupPreviousSteps(record)
        cleanupIntermediates(record)
        partial(record).delete(); ready(record).delete(); checking(record).delete(); patchChecking(record).delete()
        UpdateArchiveDelta.cleanup(checking(record))
        if (record.delta != null && key !in setOf("update.incompatible", "update.compatibilityUnknown")) {
            // Keep the public task token for notifications/cancel; never reuse the patch transfer identity.
            val fallback = record.copy(delta = null, steps = emptyList(), stepIndex = 0, id = -1, ready = false, error = null,
                transferToken = UUID.randomUUID().toString(), enqueuePending = true)
            write(fallback) // Persist the one-way transition before performing the new OS handoff.
            return enqueueLocked(fallback)
        }
        write(record.copy(ready = false, error = key))
        return status(record, Phase.FAILED, errorKey = key)
    }
    private fun pauseForStorage(record: Record): Snapshot {
        checkTransfer(record)
        // Do not remove the transfer, patch or verified chain base. Avoid reattempting synthesis on every poll.
        write(record.copy(error = UpdateStorage.ERROR_KEY))
        return status(record, Phase.WAITING, errorKey = UpdateStorage.ERROR_KEY)
    }
    private fun cancelLocked() {
        val record = read() ?: return
        removeOwned(record)
        cleanupPreviousSteps(record)
        cleanupIntermediates(record)
        partial(record).delete(); ready(record).delete(); checking(record).delete(); patchChecking(record).delete()
        UpdateArchiveDelta.cleanup(checking(record))
        check(prefs.edit().remove("session").commit())
        UpdateTransferNotification.acknowledge(context, record.token)
        UpdateDownloadNotifications.clearToken(context, record.token)
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
        Snapshot(record.token, record.release, phase, progress, file, errorKey, record.appNotification,
            record.id >= ResumableUpdateBackend.ID_BASE || errorKey == UpdateStorage.ERROR_KEY,
            currentPackageSize = transferRelease(record).assetSize)

    private fun partial(record: Record): File {
        val root = checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
        return File(root, "updates/${record.transferToken}.part")
    }
    private fun ready(record: Record) = File(UpdateChecker.downloadDir(context), "${record.token}.apk")
    private fun checking(record: Record) = File(UpdateChecker.downloadDir(context), "${record.token}.checking")
    private fun patchChecking(record: Record) = File(UpdateChecker.downloadDir(context), "${record.token}.patch-checking")
    private fun transferRelease(record: Record): UpdateChecker.ReleaseInfo = record.delta?.let {
        record.release.copy(tagName = record.steps.getOrNull(record.stepIndex)?.tagName ?: record.release.tagName,
            assetName = it.patchName, assetUrl = it.patchUrl, assetSha256 = it.patchSha256, assetSize = it.patchSize)
    } ?: record.release
    private fun eligible(delta: UpdateDeltaInfo, release: UpdateChecker.ReleaseInfo): Boolean =
        UpdateDeltaStep(delta, release.assetSha256.orEmpty(), release.assetSize, release.tagName).valid()

    private fun stepToken(token: String, index: Int): String = if (index == 0) token
        else UUID.nameUUIDFromBytes("$token:$index".toByteArray(Charsets.UTF_8)).toString()
    private fun intermediate(record: Record, index: Int) = File(UpdateChecker.downloadDir(context), "${record.token}.stage-$index")
    private fun cleanupIntermediates(record: Record) {
        record.steps.indices.forEach { intermediate(record, it).delete() }
    }
    private var previousCleanup: Pair<String, Int>? = null
    private fun cleanupPreviousSteps(record: Record) {
        val identity = record.token to record.stepIndex
        if (previousCleanup == identity) return
        for (index in 0 until record.stepIndex) {
            val old = record.copy(id = -1, delta = record.steps[index].delta, transferToken = stepToken(record.token, index))
            removeOwned(old)
            partial(old).delete()
            if (index < record.stepIndex - 1) intermediate(record, index).delete()
        }
        previousCleanup = identity
    }

    private fun sameAsset(a: UpdateChecker.ReleaseInfo, b: UpdateChecker.ReleaseInfo) =
        a.tagName == b.tagName && a.version == b.version && a.assetName == b.assetName && a.assetUrl == b.assetUrl &&
            a.assetSha256 == b.assetSha256 && a.assetSize == b.assetSize && a.assetUploadedAt == b.assetUploadedAt &&
            a.minSdk == b.minSdk && a.maxSdk == b.maxSdk && a.distribution == b.distribution && a.versionCode == b.versionCode

    /** Only call under mutex: incompatible records still carry valid cleanup ownership. */
    private fun readCompatible(): Record? {
        val record = read() ?: return null
        if (UpdateChecker.compatibilityError(record.release) != null) {
            cancelLocked()
            return null
        }
        return record
    }

    /** Fence persistent workers after an app/channel change, before they open a connection. */
    internal suspend fun authorizeTransfer(id: Long): Boolean = locked {
        val record = readCompatible()
        val transfer = backend.query(id) ?: return@locked false
        // A migrated DownloadManager row may still be the persisted root after process death.
        val matchesId = record != null && (record.id == id || record.enqueuePending ||
            (record.id >= 0 && backend.query(record.id)?.id == id))
        if (record != null && matchesId && owns(transfer, record)) true
        else {
            backend.remove(id)
            false
        }
    }

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
            mandatory = obj.optBoolean("mandatory", false),
            distribution = obj.optString("distribution", "main"),
            maxSdk = obj.optInt("maxSdk").takeIf { it > 0 })
        require(UpdateChecker.isReleaseAssetUrl(release.assetUrl, release.tagName))
        val delta = obj.optJSONObject("delta")?.let(::readDelta)
        val entries = obj.optJSONArray("steps")
        val steps = if (entries == null) emptyList() else (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            UpdateDeltaStep(readDelta(entry.getJSONObject("delta")), entry.getString("targetSha256"),
                entry.getLong("targetSize"), entry.getString("tag")).also { require(it.valid()) }
        }
        val stepIndex = obj.optInt("stepIndex", 0)
        if (steps.isEmpty()) {
            require(stepIndex == 0)
            delta?.let { require(eligible(it, release)) }
        } else {
            require(stepIndex in steps.indices && delta == steps[stepIndex].delta)
            require(transferToken == stepToken(token, stepIndex))
            require(steps.sumOf { it.delta.patchSize } < release.assetSize)
            require(steps.last().targetSha256 == release.assetSha256 && steps.last().targetSize == release.assetSize &&
                steps.last().tagName == release.tagName)
            require(steps.zipWithNext().all { (a, b) -> a.targetSha256 == b.delta.baseSha256 && a.targetSize == b.delta.baseSize })
        }
        Record(token, release, obj.getLong("id"), obj.optBoolean("ready"),
            obj.optString("error").takeIf { it in setOf("update.verifyFailed", "update.downloadMissing", "update.downloadInterrupted",
                UpdateStorage.ERROR_KEY,
                "update.incompatible", "update.distributionIncompatible", "update.compatibilityUnknown") }, obj.optBoolean("appNotification", false),
            delta, transferToken, obj.optBoolean("enqueuePending", false), steps, stepIndex)
    }.getOrNull()
    private fun write(record: Record) {
        val rel = record.release
        val obj = JSONObject().put("token", record.token).put("id", record.id).put("ready", record.ready)
            .put("appNotification", record.appNotification).put("error", record.error ?: "").put("tag", rel.tagName).put("version", rel.version).put("notes", rel.notes)
            .put("name", rel.assetName).put("url", rel.assetUrl).put("sha", rel.assetSha256 ?: "")
            .put("size", rel.assetSize).put("uploaded", rel.assetUploadedAt)
            .put("minSdk", rel.minSdk ?: 0).put("versionCode", rel.versionCode ?: 0)
            .put("distribution", rel.distribution).put("maxSdk", rel.maxSdk)
            .put("channel", if (rel.channel == UpdateChecker.Channel.BETA) "beta" else "stable")
            .put("mandatory", rel.mandatory).put("transferToken", record.transferToken).put("enqueuePending", record.enqueuePending)
        record.delta?.let { obj.put("delta", writeDelta(it)) }
        if (record.steps.isNotEmpty()) obj.put("steps", org.json.JSONArray().apply {
            record.steps.forEach { step -> put(JSONObject().put("delta", writeDelta(step.delta))
                .put("targetSha256", step.targetSha256).put("targetSize", step.targetSize).put("tag", step.tagName)) }
        }).put("stepIndex", record.stepIndex)
        check(prefs.edit().putString("session", obj.toString()).commit())
    }

    private fun readDelta(value: JSONObject) = UpdateDeltaInfo(value.getString("format"), value.getString("baseSha256"),
        value.getLong("baseSize"), value.getString("patchName"), value.getString("patchUrl"),
        value.getString("patchSha256"), value.getLong("patchSize"))
    private fun writeDelta(value: UpdateDeltaInfo) = JSONObject().put("format", value.format)
        .put("baseSha256", value.baseSha256).put("baseSize", value.baseSize).put("patchName", value.patchName)
        .put("patchUrl", value.patchUrl).put("patchSha256", value.patchSha256).put("patchSize", value.patchSize)

    internal class SystemBackend(private val context: Context) : Backend {
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
                    text(DownloadManager.COLUMN_MEDIA_TYPE)?.takeIf { it.startsWith(MIME_PREFIX) }?.removePrefix(MIME_PREFIX).orEmpty(),
                    errorKey = if (state == TransferPhase.FAILED && number(DownloadManager.COLUMN_REASON).toInt() ==
                        DownloadManager.ERROR_INSUFFICIENT_SPACE) UpdateStorage.ERROR_KEY else null)
            }
            result
        } ?: emptyList()
    }
}
