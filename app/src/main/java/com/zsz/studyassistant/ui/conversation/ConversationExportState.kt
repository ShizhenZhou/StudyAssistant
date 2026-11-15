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
    var session by mutableStateOf<ConversationExportSession?>(restore())
        private set
    var messages by mutableStateOf<List<ChatMsg>?>(null)
        private set
    var feedback by mutableStateOf<String?>(null)
        private set
    private var operation: Job? = null
    private var editSave: Job? = null

    init {
        session?.let { value ->
            if (value.phase == ExportPhase.SAVING) save()
            else if (value.phase == ExportPhase.RENDERING) loadMessages(value)
        }
    }

    fun consumeFeedback() { feedback = null }

    fun begin(selected: List<ChatMsg>) {
        if (session != null || selected.isEmpty()) return
        val value = ConversationExportSession()
        val frozen = selected.map { it.copy(images = it.images.toList(), sources = it.sources.toList(), documents = it.documents.toList()) }
        messages = frozen
        try { update(value) } catch (_: Exception) { fail(); return }
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(frozen.sumOf { it.content.length.toLong() + (it.quotedText?.length ?: 0) + it.images.sumOf { image -> image.length.toLong() } } <= 16L * 1024 * 1024)
                    val directory = directory(value)
                    check(directory.mkdirs())
                    File(directory, "messages.json").writeText(json.encodeToString(frozen))
                }
                if (session?.id == value.id) update(value.copy(phase = ExportPhase.OPTIONS))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail() }
        }
    }

    fun edit(filename: String? = null, format: ConversationExportFormat? = null) {
        val value = session ?: return
        if (value.phase != ExportPhase.OPTIONS) return
        session = value.copy(filename = filename ?: value.filename, format = format ?: value.format)
        editSave?.cancel()
        editSave = viewModelScope.launch {
            delay(250)
            try { persist(checkNotNull(session)) } catch (_: Exception) { fail() }
        }
    }

    fun render() {
        val value = session ?: return
        editSave?.cancel()
        try {
            update(value.copy(phase = ExportPhase.RENDERING, files = emptyList(), destination = null,
                pngFolder = null, pngFiles = emptyMap()))
            if (messages == null) loadMessages(checkNotNull(session))
        } catch (_: Exception) { fail() }
    }

    private fun loadMessages(value: ConversationExportSession) {
        operation = viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    json.decodeFromString<List<ChatMsg>>(File(directory(value), "messages.json").readText())
                }
                if (session?.id == value.id) messages = restored
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail() }
        }
    }

    fun prepared(result: ConversationExportResult) {
        val value = session ?: return
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
            catch (_: Exception) { fail() }
            finally { withContext(NonCancellable + Dispatchers.IO) { result.directory.deleteRecursively() } }
        }
    }

    /** Persist before handing control to another Activity; recreation must not launch a second picker. */
    fun picking(): Boolean = try {
        update(checkNotNull(session).copy(phase = ExportPhase.PICKER)); true
    } catch (_: Exception) { fail(); false }

    fun chooseDestinationAgain() {
        val value = session ?: return
        if (value.phase == ExportPhase.PICKER) try {
            update(value.copy(phase = ExportPhase.READY))
        } catch (_: Exception) { fail() }
    }

    fun destination(uri: Uri?) {
        val value = session ?: return
        if (value.phase != ExportPhase.PICKER) return
        if (uri == null) { cancel(); return }
        var acquiredFlags = 0
        try {
            // Providers may decline persistence; the live result grant still permits this save.
            val resolver = getApplication<Application>().contentResolver
            val existing = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            val needed = (if (existing?.isReadPermission == true) 0 else Intent.FLAG_GRANT_READ_URI_PERMISSION) or
                (if (existing?.isWritePermission == true) 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (needed != 0) runCatching {
                resolver.takePersistableUriPermission(uri, needed)
                acquiredFlags = needed
            }
            update(value.copy(phase = ExportPhase.SAVING, destination = uri.toString(), ownedGrantFlags = acquiredFlags))
            save()
        } catch (_: Exception) {
            if (acquiredFlags != 0) runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(uri, acquiredFlags) }
            fail()
        }
    }

    private fun save() {
        val value = session ?: return
        operation = viewModelScope.launch {
            try {
                val output = File(directory(value), "output")
                val files = value.files.map { File(output, it) }
                // Validate every source before opening (and truncating) the SAF PDF destination.
                check(files.isNotEmpty() && files.all { it.isFile && it.length() > 0 })
                val result = ConversationExportResult(output, files, value.name, value.format)
                saveConversationExport(getApplication(), result, Uri.parse(checkNotNull(value.destination)),
                    restoredFolder = value.pngFolder?.let(Uri::parse),
                    restoredFiles = value.pngFiles.mapValues { Uri.parse(it.value) },
                    onFolderCreated = { uri -> checkpoint { it.copy(pngFolder = uri.toString()) } },
                    onFileCreated = { name, uri -> checkpoint { it.copy(pngFiles = it.pngFiles + (name to uri.toString())) } })
                feedback = "export.success"
                finish()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { fail() }
        }
    }

    private suspend fun checkpoint(change: (ConversationExportSession) -> ConversationExportSession) =
        withContext(Dispatchers.Main.immediate) { update(change(checkNotNull(session))) }

    fun fail() {
        feedback = "export.failed"
        finish()
    }

    /** Explicit cancellation clears the checkpoint; Compose disposal/configuration changes do not. */
    fun cancel() {
        val pending = operation
        pending?.cancel()
        finish(pending)
    }

    private fun finish(pending: Job? = null) {
        editSave?.cancel()
        val value = session
        journal.delete()
        session = null
        messages = null
        if (value != null) viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            pending?.join()
            if (value.destination != null && value.ownedGrantFlags != 0) runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(value.destination), value.ownedGrantFlags)
            }
            directory(value).deleteRecursively()
        }
    }

    private fun update(value: ConversationExportSession) {
        persist(value)
        session = value
    }

    private fun persist(value: ConversationExportSession) {
        check(root.isDirectory || root.mkdirs())
        val stream = journal.startWrite()
        try {
            stream.write(json.encodeToString(value).toByteArray(Charsets.UTF_8))
            journal.finishWrite(stream)
        } catch (error: Exception) { journal.failWrite(stream); throw error }
    }

    private fun directory(value: ConversationExportSession): File = File(root, value.id)

    private fun restore(): ConversationExportSession? = runCatching {
        val value = journal.openRead().use { input ->
            val bytes = input.readNBytes(1024 * 1024 + 1)
            check(bytes.size <= 1024 * 1024)
            json.decodeFromString<ConversationExportSession>(bytes.toString(Charsets.UTF_8))
        }
        check(UUID.fromString(value.id).toString() == value.id)
        check(value.files.all { File(it).name == it && it != "." && it != ".." })
        // A renderer belongs to its Activity. Resume it from the private message snapshot.
        when (value.phase) {
            ExportPhase.STORING -> value.copy(phase = ExportPhase.RENDERING)
            ExportPhase.SNAPSHOT -> value.copy(phase = ExportPhase.OPTIONS)
            else -> value
        }
    }.getOrNull()
}
