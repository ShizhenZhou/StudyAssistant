package com.zsz.studyassistant.ui

import com.zsz.studyassistant.data.readAtMost

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
internal enum class ExportPhase { SNAPSHOT, OPTIONS, RENDERING, STORING, READY, PICKER, SAVING, CHOOSE_AGAIN }

@Serializable
internal data class ConversationExportSession(
    val id: String = UUID.randomUUID().toString(),
    val fallback: String = "StudyAssistant-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()),
    val filename: String = "",
    val format: ConversationExportFormat = ConversationExportFormat.PDF,
    val phase: ExportPhase = ExportPhase.SNAPSHOT,
    val files: List<String> = emptyList(),
    val destination: String? = null,
    val ownedGrantFlags: Int = 0,
    val pngFolder: String? = null,
    val pngFiles: Map<String, String> = emptyMap()
) {
    val name: String get() = conversationExportName(filename, fallback)
}

/** Activity-owned: only private files contain payloads; saved-instance-state holds no export content. */
internal class ConversationExportViewModel(app: Application) : AndroidViewModel(app) {
    private val root = File(app.noBackupFilesDir, "conversation-export")
    private val journal = AtomicFile(File(root, "active.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private val journalJson = Json(json) { encodeDefaults = true }
    var session by mutableStateOf<ConversationExportSession?>(null)
        private set
    var messages by mutableStateOf<List<ChatMsg>?>(null)
        private set
    var feedback by mutableStateOf<String?>(null)
        private set
    private var operation: Job? = null
    private var editSave: Job? = null

    private companion object {
        // Also serialize the brief overlap between a destroyed Activity and its replacement.
        val journalMutex = Mutex()
        var lastCleanup: Job? = null
    }
    @Volatile private var journalOwner: String? = null
    private var cleanup: Job? = null
    private var restoreFailed = false
    var showRestoreError by mutableStateOf(false)
        private set
    var destinationPromptHidden by mutableStateOf(false)
        private set
    private var initialization = viewModelScope.launch { restoreSession() }

    private suspend fun restoreSession() {
        try {
            lastCleanup?.join()
            session = withContext(Dispatchers.IO) {
                journalMutex.withLock {
                    val restored = restore()
                    // An unreadable journal throws: its unknown active task must be preserved.
                    if (restored == null) journal.delete()
                    ConversationExportFiles.cleanupInactive(root, restored?.id)
                    restored
                }
            }
            journalOwner = session?.id
            restoreFailed = false
            showRestoreError = false
            session?.let { value ->
                if (value.phase == ExportPhase.SAVING) save()
                else if (value.phase == ExportPhase.RENDERING) loadMessages(value)
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            restoreFailed = true
            showRestoreError = true
            feedback = "export.restoreFailed"
        }
    }

    fun consumeFeedback() { feedback = null }

    fun keepUnrestored() { showRestoreError = false }

    fun keepRendered() { destinationPromptHidden = true }

    fun retryRestore() {
        if (restoreFailed && !initialization.isActive) initialization = viewModelScope.launch { restoreSession() }
    }

    fun discardUnrestored() {
        if (!restoreFailed || initialization.isActive) return
        initialization = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    journalMutex.withLock {
                        ConversationExportFiles.discardUnrestored(root)
                        journal.delete()
                        check(journalFilesAbsent())
                    }
                }
                restoreFailed = false
                showRestoreError = false
                feedback = "export.discarded"
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { feedback = "export.discardFailed" }
        }
    }

    fun begin(selected: List<ChatMsg>): Job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        initialization.join()
        cleanup?.join()
        if (restoreFailed) { showRestoreError = true; return@launch }
        destinationPromptHidden = false
        if (session != null || selected.isEmpty()) return@launch
        val value = ConversationExportSession()
        val frozen = selected.map { it.copy(images = it.images.toList(), sources = it.sources.toList(), documents = it.documents.toList()) }
        session = value
        journalOwner = value.id
        messages = frozen
        operation = currentCoroutineContext().job
        try {
            update(value)
            withContext(Dispatchers.IO) {
                check(frozen.sumOf { it.content.length.toLong() + (it.quotedText?.length ?: 0) + it.images.sumOf { image -> image.length.toLong() } } <= 16L * 1024 * 1024)
                val directory = directory(value)
                check(directory.mkdirs())
                val snapshot = AtomicFile(File(directory, "messages.json"))
                val stream = snapshot.startWrite()
                try {
                    stream.write(json.encodeToString(frozen).toByteArray(Charsets.UTF_8))
                    snapshot.finishWrite(stream)
                } catch (error: Exception) { snapshot.failWrite(stream); throw error }
            }
            update(value.copy(phase = ExportPhase.OPTIONS))
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { fail(value.id) }
    }

    fun edit(filename: String? = null, format: ConversationExportFormat? = null) {
        val value = session ?: return
        if (value.phase != ExportPhase.OPTIONS) return
        session = value.copy(filename = filename ?: value.filename, format = format ?: value.format)
        editSave?.cancel()
        editSave = viewModelScope.launch {
            delay(250)
            try { persist(checkNotNull(session)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail(value.id) }
        }
    }

    fun render() {
        val value = session ?: return
        editSave?.cancel()
        if (value.phase != ExportPhase.OPTIONS || operation?.isActive == true) return
        operation = viewModelScope.launch {
            try {
                update(value.copy(phase = ExportPhase.RENDERING, files = emptyList(), destination = null,
                    pngFolder = null, pngFiles = emptyMap()))
                if (messages == null) loadMessages(checkNotNull(session))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail(value.id) }
        }
    }

    private fun loadMessages(value: ConversationExportSession) {
        operation = viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    json.decodeFromString<List<ChatMsg>>(File(directory(value), "messages.json").readText())
                }
                if (session?.id == value.id) messages = restored
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail(value.id) }
        }
    }

    fun prepared(id: String, result: ConversationExportResult) {
        val value = session
        if (value == null || value.id != id || value.phase != ExportPhase.RENDERING) {
            viewModelScope.launch(NonCancellable + Dispatchers.IO) { result.directory.deleteRecursively() }
            return
        }
        session = value.copy(phase = ExportPhase.STORING)
        operation = viewModelScope.launch {
            try {
                val names = withContext(Dispatchers.IO) {
                    val output = File(directory(value), "output")
                    // Only this operation's private renderer output is replaced.
                    output.deleteRecursively()
                    check(output.mkdirs())
                    result.files.map { source ->
                        check(source.isFile && source.length() > 0)
                        source.copyTo(File(output, source.name), overwrite = false).name
                    }
                }
                if (session?.id == value.id) update(value.copy(phase = ExportPhase.READY, files = names))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail(value.id) }
            finally { withContext(NonCancellable + Dispatchers.IO) { result.directory.deleteRecursively() } }
        }
    }

    /** Persist before handing control to another Activity; recreation must not launch a second picker. */
    suspend fun picking(): Boolean {
        val value = session ?: return false
        if (value.phase != ExportPhase.READY) return false
        return try { update(value.copy(phase = ExportPhase.PICKER)); true }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { destinationFailed(value.id); false }
    }

    fun chooseDestinationAgain() {
        val value = session ?: return
        if (value.phase !in setOf(ExportPhase.PICKER, ExportPhase.CHOOSE_AGAIN) || operation?.isActive == true) return
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { releaseOwnedGrant(value) }
                update(value.copy(phase = ExportPhase.READY, destination = null, ownedGrantFlags = 0,
                    pngFolder = null, pngFiles = emptyMap()))
            }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { destinationFailed(value.id) }
        }
    }

    fun destination(uri: Uri?) {
        viewModelScope.launch {
            // ActivityResult can arrive before the background journal restore has completed.
            initialization.join()
            val value = session ?: return@launch
            if (value.phase != ExportPhase.PICKER) return@launch
            // READY may be visible while prepared() is still removing renderer cache in finally.
            // Preserve a quick SAF result instead of discarding it during that brief overlap.
            operation?.join()
            if (session?.id != value.id || session?.phase != ExportPhase.PICKER) return@launch
            if (uri == null) return@launch
            // Disable repeat callbacks/cancel while the owned grant and checkpoint are being committed.
            session = value.copy(phase = ExportPhase.SAVING)
            operation = currentCoroutineContext().job
            var acquiredFlags = 0
            var checkpointed = false
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val existing = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                    val needed = (if (existing?.isReadPermission == true) 0 else Intent.FLAG_GRANT_READ_URI_PERMISSION) or
                        (if (existing?.isWritePermission == true) 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    if (needed != 0) runCatching {
                        resolver.takePersistableUriPermission(uri, needed)
                        acquiredFlags = needed
                    }
                }
                // A committed SAVING journal must keep its grant even if the Activity is destroyed
                // while IO returns. Cancellation cannot separate the commit from this ownership flag.
                withContext(NonCancellable) {
                    update(value.copy(phase = ExportPhase.SAVING, destination = uri.toString(), ownedGrantFlags = acquiredFlags))
                    checkpointed = true
                }
                currentCoroutineContext().ensureActive()
                save()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { destinationFailed(value.id) }
            finally {
                if (!checkpointed && acquiredFlags != 0) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(uri, acquiredFlags) }
                }
            }
        }
    }

    private fun save() {
        val value = session ?: return
        operation = viewModelScope.launch {
            try {
                val output = File(directory(value), "output")
                val files = value.files.map { File(output, it) }
                // Validate every source before opening (and truncating) the SAF PDF destination.
                if (!withContext(Dispatchers.IO) { files.isNotEmpty() && files.all { it.isFile && it.length() > 0 } }) {
                    fail(value.id)
                    return@launch
                }
                val result = ConversationExportResult(output, files, value.name, value.format)
                saveConversationExport(getApplication(), result, Uri.parse(checkNotNull(value.destination)),
                    restoredFolder = value.pngFolder?.let(Uri::parse),
                    restoredFiles = value.pngFiles.mapValues { Uri.parse(it.value) },
                    onFolderCreated = { uri -> checkpoint(value.id) { it.copy(pngFolder = uri.toString()) } },
                    onFileCreated = { name, uri -> checkpoint(value.id) { it.copy(pngFiles = it.pngFiles + (name to uri.toString())) } })
                feedback = "export.success"
                finish()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { destinationFailed(value.id) }
        }
    }

    fun pickerFailed(id: String) {
        viewModelScope.launch { destinationFailed(id) }
    }

    private suspend fun destinationFailed(id: String) {
        val value = session?.takeIf { it.id == id } ?: return
        val retry = value.copy(phase = ExportPhase.CHOOSE_AGAIN)
        try { update(retry) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            checkCurrent(value)
            session = retry
        }
    }

    private suspend fun checkpoint(id: String, change: (ConversationExportSession) -> ConversationExportSession) =
        withContext(Dispatchers.Main.immediate) {
            val value = session
            if (value?.id != id) throw CancellationException("Export superseded")
            update(change(value))
        }

    fun fail() { fail(session?.id) }

    fun fail(id: String?) {
        if (session?.id != id) return
        feedback = "export.failed"
        finish()
    }

    /** Explicit cancellation clears the checkpoint; Compose disposal/configuration changes do not. */
    fun cancel() {
        val pending = operation
        pending?.cancel()
        finish(pending)
    }

    private fun finish(pending: Job? = operation) {
        val edits = editSave
        edits?.cancel()
        val value = session ?: return
        journalOwner = null
        session = null
        messages = null
        cleanup = viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            pending?.join()
            edits?.join()
            journalMutex.withLock {
                // New begin() waits for this cleanup, so an old task cannot delete a new journal.
                journal.delete()
                runCatching { releaseOwnedGrant(value) }
                runCatching { ConversationExportFiles.deleteTask(root, value.id) }
            }
        }
        lastCleanup = cleanup
    }

    private fun releaseOwnedGrant(value: ConversationExportSession) {
        val destination = value.destination ?: return
        if (value.ownedGrantFlags == 0) return
        val uri = Uri.parse(destination)
        val resolver = getApplication<Application>().contentResolver
        val existing = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val currentFlags = (if (existing.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (existing.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        val owned = currentFlags and value.ownedGrantFlags
        if (owned != 0) resolver.releasePersistableUriPermission(uri, owned)
    }

    private suspend fun update(value: ConversationExportSession) {
        checkCurrent(value)
        persist(value)
        checkCurrent(value)
        session = value
    }

    private fun checkCurrent(value: ConversationExportSession) {
        if (session?.id != value.id) throw CancellationException("Export superseded")
    }

    private suspend fun persist(value: ConversationExportSession) {
        checkCurrent(value)
        // Includes fsync and cancellation cleanup: no journal writes on the UI thread.
        journalMutex.withLock {
            withContext(Dispatchers.IO) {
                // A canceled picker/filename write may still be queued on IO after cleanup.
                if (journalOwner != value.id) throw CancellationException("Export superseded")
                check(root.isDirectory || root.mkdirs())
                val stream = journal.startWrite()
                try {
                    stream.write(journalJson.encodeToString(value).toByteArray(Charsets.UTF_8))
                    journal.finishWrite(stream)
                } catch (error: Exception) { journal.failWrite(stream); throw error }
            }
        }
    }

    private fun directory(value: ConversationExportSession): File = File(root, value.id)

    private fun journalFilesAbsent() = listOf("active.json", "active.json.bak", "active.json.new").all {
        java.nio.file.Files.notExists(File(root, it).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }

    private fun restore(): ConversationExportSession? {
        val bytes = try {
            journal.openRead().use { it.readAtMost(1024 * 1024 + 1) }
        } catch (error: java.io.FileNotFoundException) {
            if (!journalFilesAbsent()) throw error
            return null
        }
        check(bytes.size <= 1024 * 1024)
        val record = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        check("id" in record)
        val value = json.decodeFromJsonElement<ConversationExportSession>(record)
        check(ConversationExportFiles.isTaskId(value.id))
        check(value.fallback.matches(Regex("StudyAssistant-[0-9]{8}-[0-9]{6}")))
        check(value.files.all { it.isNotBlank() && File(it).name == it && it != "." && it != ".." })
        check(value.files.distinct().size == value.files.size)
        check(value.pngFiles.keys.all { it in value.files })
        check(value.pngFiles.values.distinct().size == value.pngFiles.size)
        check(value.pngFiles.isEmpty() || value.pngFolder != null)
        check(value.pngFolder == null || (value.format == ConversationExportFormat.PNG && value.destination != null))
        check(value.destination == null || Uri.parse(value.destination).scheme == "content")
        check(value.ownedGrantFlags in 0..(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        check(value.destination != null || value.ownedGrantFlags == 0)
        check(value.phase != ExportPhase.SAVING || value.destination != null)
        if (value.phase in setOf(ExportPhase.READY, ExportPhase.PICKER, ExportPhase.SAVING, ExportPhase.CHOOSE_AGAIN)) {
            check(value.files.isNotEmpty())
            check(value.format != ConversationExportFormat.PDF || value.files.size == 1)
            val output = File(directory(value), "output")
            check(value.files.all { name -> File(output, name).let { it.isFile && it.length() > 0 } })
        } else {
            check(value.files.isEmpty() && value.destination == null)
        }
        check(File(directory(value), "messages.json").isFile)
        return when (value.phase) {
            ExportPhase.STORING -> value.copy(phase = ExportPhase.RENDERING)
            ExportPhase.SNAPSHOT -> value.copy(phase = ExportPhase.OPTIONS)
            else -> value
        }
    }
}
