package com.zsz.studyassistant.ui

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

@Serializable
internal enum class ExportPhase { SNAPSHOT, OPTIONS, RENDERING, STORING, READY, PICKER, SAVING }

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
    private val initialization = viewModelScope.launch {
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
            session?.let { value ->
                if (value.phase == ExportPhase.SAVING) save()
                else if (value.phase == ExportPhase.RENDERING) loadMessages(value)
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { restoreFailed = true; feedback = "export.failed" }
    }

    fun consumeFeedback() { feedback = null }

    fun begin(selected: List<ChatMsg>): Job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        initialization.join()
        cleanup?.join()
        if (restoreFailed || session != null || selected.isEmpty()) return@launch
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
        catch (_: Exception) { fail(value.id); false }
    }

    fun chooseDestinationAgain() {
        val value = session ?: return
        if (value.phase != ExportPhase.PICKER || operation?.isActive == true) return
        operation = viewModelScope.launch {
            try { update(value.copy(phase = ExportPhase.READY)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail(value.id) }
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
            if (uri == null) { cancel(); return@launch }
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
            catch (_: Exception) { fail(value.id) }
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
                withContext(Dispatchers.IO) { check(files.isNotEmpty() && files.all { it.isFile && it.length() > 0 }) }
                val result = ConversationExportResult(output, files, value.name, value.format)
                saveConversationExport(getApplication(), result, Uri.parse(checkNotNull(value.destination)),
                    restoredFolder = value.pngFolder?.let(Uri::parse),
                    restoredFiles = value.pngFiles.mapValues { Uri.parse(it.value) },
                    onFolderCreated = { uri -> checkpoint(value.id) { it.copy(pngFolder = uri.toString()) } },
                    onFileCreated = { name, uri -> checkpoint(value.id) { it.copy(pngFiles = it.pngFiles + (name to uri.toString())) } })
                feedback = "export.success"
                finish()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail(value.id) }
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
                if (value.destination != null && value.ownedGrantFlags != 0) runCatching {
                    getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(value.destination), value.ownedGrantFlags)
                }
                runCatching { ConversationExportFiles.deleteTask(root, value.id) }
            }
        }
        lastCleanup = cleanup
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
                    stream.write(json.encodeToString(value).toByteArray(Charsets.UTF_8))
                    journal.finishWrite(stream)
                } catch (error: Exception) { journal.failWrite(stream); throw error }
            }
        }
    }

    private fun directory(value: ConversationExportSession): File = File(root, value.id)

    private fun restore(): ConversationExportSession? {
        val bytes = try {
            journal.openRead().use { it.readNBytes(1024 * 1024 + 1) }
        } catch (error: java.io.FileNotFoundException) {
            if (!java.nio.file.Files.notExists(journal.baseFile.toPath()) ||
                !java.nio.file.Files.notExists(File(root, "active.json.bak").toPath())) throw error
            return null
        }
        return runCatching {
            check(bytes.size <= 1024 * 1024)
            val value = json.decodeFromString<ConversationExportSession>(bytes.toString(Charsets.UTF_8))
            check(ConversationExportFiles.isTaskId(value.id))
            check(value.files.all { File(it).name == it && it != "." && it != ".." })
            // Snapshot is committed atomically before OPTIONS; an interrupted initial write is disposable.
            if (value.phase == ExportPhase.SNAPSHOT && !File(directory(value), "messages.json").isFile) return null
            when (value.phase) {
                ExportPhase.STORING -> value.copy(phase = ExportPhase.RENDERING)
                ExportPhase.SNAPSHOT -> value.copy(phase = ExportPhase.OPTIONS)
                else -> value
            }
        }.getOrNull()
    }
}
