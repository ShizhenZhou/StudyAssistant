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

class DocumentProblem(val messageKey: String) : IOException(messageKey)

fun validateDocument(document: ConversationDocument) {
    require(document.sha256.matches(Regex("[0-9a-f]{64}")))
    require(document.size in 1..DocumentStore.MAX_BYTES)
    require(document.name.length in 1..200 && document.name.none { it == '/' || it == '\\' || it.code < 32 })
    require(document.name.substringAfterLast('.', "").lowercase() in setOf("pdf", "doc", "docx", "ppt", "pptx"))
}

/** Immutable originals, addressed by content hash. Remote file IDs never replace these files. */
class DocumentStore(context: Context) {
    val directory = File(context.filesDir, "ai-documents")
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

    fun file(document: ConversationDocument): File {
        validateDocument(document)
        val file = File(directory, document.sha256)
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() != document.size) throw DocumentProblem("document.missing")
        return file
    }

    /** Also used by backup import, before any DB merge; never deletes or overwrites existing originals. */
    fun install(source: File, document: ConversationDocument, check: () -> Unit = {}): File {
        validateDocument(document); ensureDirectory()
        val target = File(directory, document.sha256)
        if (target.exists()) {
            if (Files.isSymbolicLink(target.toPath()) || target.length() != document.size || hash(target, check) != document.sha256)
                throw DocumentProblem("document.damaged")
            return target
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
            return target
        } finally { temp.delete() }
    }

    private fun ensureDirectory() {
        if (Files.isSymbolicLink(directory.toPath()) || (!directory.isDirectory && !directory.mkdirs())) throw IOException("Cannot create document storage")
    }

    companion object {
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

/** Bounded volatile remote references; original document metadata stays in drafts and history. */
internal object ConversationDocumentUploads {
    private data class Entry(val reference: RemoteAiFile?, val retryAfter: Long = 0)
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, Entry>(32, .75f, true)

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
                val cacheKey = aiAccountScope(key) + document.sha256
                val now = System.currentTimeMillis() / 1000
                val entry = cache[cacheKey]
                if (entry?.reference != null && runCatching { entry.reference.checkUsable(key, now) }.isSuccess) entry.reference
                else {
                    if (entry != null && entry.retryAfter > now) throw DocumentProblem("document.uploadUncertain")
                    val file = store.file(document)
                    val coroutine = currentCoroutineContext()
                    if (DocumentStore.hash(file) { coroutine.ensureActive() } != document.sha256) throw DocumentProblem("document.damaged")
                    onProgress?.invoke("document.uploading")
                    // A disconnected upload may still be parsed and billed. Do not retry immediately.
                    cache[cacheKey] = Entry(null, now + 300)
                    while (cache.size > 32) cache.remove(cache.keys.first())
                    val reference = AiFileSession(ApiClient.files, ApiClient.ocr, key)
                        .uploadDocument(file, filename = document.name)
                    currentCoroutineContext().ensureActive()
                    cache[cacheKey] = Entry(reference)
                    reference
                }
            }
            attachDocuments(listOf(message.copy(localDocuments = emptyList())), references, key).single()
        }
    }

    suspend fun invalidate(messages: List<DeepSeekMessage>, key: String) = lock.withLock {
        val scope = aiAccountScope(key)
        messages.flatMap { it.localDocuments }.forEach { cache.remove(scope + it.sha256) }
    }
}
