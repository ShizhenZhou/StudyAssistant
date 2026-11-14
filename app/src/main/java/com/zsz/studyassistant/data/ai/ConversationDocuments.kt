package com.zsz.studyassistant.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ConversationDocument(val sha256: String, val name: String, val size: Long)

class DocumentProblem(val messageKey: String, internal val uploadRejected: Boolean = false) : IOException(messageKey)

fun validateDocument(document: ConversationDocument) {
    require(document.sha256.matches(Regex("[0-9a-f]{64}")))
    require(document.size in 1..DocumentStore.MAX_BYTES)
    require(document.name.length in 1..200 && document.name.none { it == '/' || it == '\\' || it.code < 32 })
    require(document.name.substringAfterLast('.', "").lowercase() in setOf("pdf", "doc", "docx", "ppt", "pptx"))
}

/** Immutable originals, addressed by content hash. Remote file IDs never replace these files. */
class DocumentStore(context: Context) {
    val directory = File(context.filesDir, "ai-documents")
    internal val referenceCache = DocumentReferenceCache(File(context.noBackupFilesDir, "ai-document-references-v1/state.json"))
    private val resolver = context.applicationContext.contentResolver

    suspend fun importUri(uri: Uri): ConversationDocument {
        val context = currentCoroutineContext()
        var displayName: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) displayName = it.getString(index)
                val length = it.getColumnIndex(OpenableColumns.SIZE)
                if (length >= 0 && !it.isNull(length) && it.getLong(length) > MAX_BYTES) throw DocumentProblem("document.tooLarge")
            }
        }
        val name = displayName?.substringAfterLast('/')?.substringAfterLast('\\')?.filter { it.code >= 32 }
            ?.takeIf { it.length in 1..200 } ?: throw DocumentProblem("document.invalid")
        if (name.substringAfterLast('.', "").lowercase() !in setOf("pdf", "doc", "docx", "ppt", "pptx")) throw DocumentProblem("document.invalid")
        ensureDirectory()
        val temp = File.createTempFile("import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_BYTES) throw DocumentProblem("document.tooLarge")
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                output.fd.sync()
            } } ?: throw DocumentProblem("document.readFailed")
            if (size == 0L) throw DocumentProblem("document.invalid")
            val document = ConversationDocument(digest.digest().hex(), name, size)
            install(temp, document) { context.ensureActive() }
            return document
        } finally { temp.delete() }
    }

    fun file(document: ConversationDocument): File = withOriginalsLock {
        validateDocument(document)
        val file = File(directory, document.sha256)
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() != document.size) throw DocumentProblem("document.missing")
        if (!file.setLastModified(System.currentTimeMillis())) throw DocumentProblem("document.readFailed")
        file
    }

    /** Also used by backup import, before any DB merge; never deletes or overwrites existing originals. */
    fun install(source: File, document: ConversationDocument, check: () -> Unit = {}): File = withOriginalsLock {
        validateDocument(document); ensureDirectory()
        val target = File(directory, document.sha256)
        if (target.exists()) {
            if (Files.isSymbolicLink(target.toPath()) || target.length() != document.size || hash(target, check) != document.sha256)
                throw DocumentProblem("document.damaged")
            if (!target.setLastModified(System.currentTimeMillis())) throw DocumentProblem("document.readFailed")
            return@withOriginalsLock target
        }
        val temp = File.createTempFile("install-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            source.inputStream().use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > document.size) throw DocumentProblem("document.damaged")
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                output.fd.sync()
            } }
            if (copied != document.size || digest.digest().hex() != document.sha256) throw DocumentProblem("document.damaged")
            check()
            if (!temp.renameTo(target)) {
                if (!target.isFile || hash(target, check) != document.sha256) throw IOException("Cannot install document")
            }
            target
        } finally { temp.delete() }
    }

    private fun ensureDirectory() {
        if (Files.isSymbolicLink(directory.toPath()) || (!directory.isDirectory && !directory.mkdirs())) throw IOException("Cannot create document storage")
    }

    companion object {
        private val originalsLock = Any()
        internal fun <T> withOriginalsLock(block: () -> T): T = synchronized(originalsLock) { block() }
        const val MAX_BYTES = 100L * 1024 * 1024
        fun validateDocument(document: ConversationDocument) = com.zsz.studyassistant.data.validateDocument(document)
        fun hash(file: File, check: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { check(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().hex()
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}

/** Private persistent remote references; originals and conversation metadata remain unchanged. */
internal object ConversationDocumentUploads {
    private val lock = Mutex()

    suspend fun prepare(messages: List<DeepSeekMessage>, store: DocumentStore, key: String,
        onProgress: (suspend (String) -> Unit)?): List<DeepSeekMessage> = lock.withLock {
        val count = messages.sumOf { it.localDocuments.size }
        if (count > 20) throw DocumentProblem("document.tooMany")
        messages.map { message ->
            if (message.localDocuments.isEmpty()) return@map message
            require(message.role == "user")
            val references = message.localDocuments.map { document ->
                currentCoroutineContext().ensureActive()
                validateDocument(document)
                val scope = aiAccountScope(key)
                val purpose = DocumentRecognition.TEXT_AND_FIGURES
                val now = System.currentTimeMillis() / 1000
                val file = store.file(document)
                store.referenceCache.find(scope, document.sha256, purpose.wireValue, now) ?: run {
                    val coroutine = currentCoroutineContext()
                    if (DocumentStore.hash(file) { coroutine.ensureActive() } != document.sha256) throw DocumentProblem("document.damaged")
                    onProgress?.invoke("document.uploading")
                    // A disconnected upload may still be parsed and billed. Do not retry immediately.
                    store.referenceCache.begin(scope, document.sha256, purpose.wireValue, now)
                    val reference = try {
                        AiFileSession(ApiClient.files, ApiClient.ocr, key)
                            .uploadDocument(file, purpose = purpose, filename = document.name)
                    } catch (failure: DocumentProblem) {
                        // A received HTTP rejection is a known failure, so it needs no uncertain-upload delay.
                        if (failure.uploadRejected) store.referenceCache.invalidate(scope, setOf(document.sha256),
                            System.currentTimeMillis() / 1000)
                        throw failure
                    }
                    store.referenceCache.complete(scope, document.sha256, purpose.wireValue, reference,
                        System.currentTimeMillis() / 1000)
                    currentCoroutineContext().ensureActive()
                    reference
                }
            }
            attachDocuments(listOf(message.copy(localDocuments = emptyList())), references, key).single()
        }
    }

    suspend fun invalidate(messages: List<DeepSeekMessage>, key: String, store: DocumentStore) = lock.withLock {
        store.referenceCache.invalidate(aiAccountScope(key), messages.flatMap { it.localDocuments }.map { it.sha256 }.toSet(),
            System.currentTimeMillis() / 1000)
    }

    suspend fun clear(store: DocumentStore) = lock.withLock { store.referenceCache.clear() }
}
