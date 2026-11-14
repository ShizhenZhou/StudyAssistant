package com.zsz.studyassistant.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.BufferedSink

/** Backend only. The caller owns local originals, user consent, editing and persistence. */
object AiFiles {
    fun session(): AiFileSession {
        StudyAssistant.requireKey()
        return AiFileSession(ApiClient.files, ApiClient.ocr, KeyManager.getApiKey())
    }
}

internal fun aiAccountScope(key: String): String = MessageDigest.getInstance("SHA-256")
    .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** An account-bound temporary reference, never a substitute for the local original. */
class RemoteAiFile internal constructor(val metadata: DeepSeekFile, internal val accountScope: String) {
    internal fun checkUsable(key: String, now: Long) {
        require(accountScope == aiAccountScope(key)) { "File belongs to a different API account" }
        require(metadata.expiresAt?.let { it > now + 300 } != false) { "File reference expired; explicit re-upload required" }
        require(validAiFileId(metadata.id)) { "Invalid file reference" }
    }
    override fun toString() = "RemoteAiFile(temporary reference)"
}

internal fun validAiFileId(id: String) = id.matches(Regex("file-[A-Za-z0-9_-]{1,180}"))

/** Each session captures one credential for upload/retrieve/delete/OCR; never logged or serialized. */
class AiFileSession internal constructor(private val files: DeepSeekFileService,
    private val ocr: DeepSeekOcrService, private val key: String,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    init { require(key.isNotBlank()) }
    private val authorization get() = "Bearer $key"
    private fun bind(file: DeepSeekFile) = RemoteAiFile(file, aiAccountScope(key)).also {
        it.checkUsable(key, clock())
    }

    /** Explicit upload; no hidden retries. An interrupted upload may still exist/bill remotely. */
    suspend fun uploadDocument(original: File, purpose: DocumentRecognition = DocumentRecognition.TEXT_AND_FIGURES,
        lifetimeSeconds: Long = 86400, filename: String = original.name): RemoteAiFile = withContext(Dispatchers.IO) {
        require(lifetimeSeconds in 3600..2592000)
        val part = documentPart(original, filename)
        bind(files.upload(authorization, textPart(purpose.wireValue), textPart("created_at"),
            textPart(lifetimeSeconds.toString()), part))
    }

    /** Use listing/retrieval to reconcile uncertain uploads; never choose a file by name alone. */
    suspend fun listDocuments(after: String? = null, limit: Int = 100,
        purpose: DocumentRecognition? = null): AiFilePage {
        require(after == null || validAiFileId(after)); require(limit in 1..1000)
        return files.list(authorization, after, limit, "desc", purpose?.wireValue)
    }

    suspend fun retrieve(id: String): RemoteAiFile {
        require(validAiFileId(id))
        val metadata = files.retrieve(authorization, id)
        require(metadata.id == id) { "File identity mismatch" }
        return bind(metadata)
    }

    /** Only explicit deletion of this session's remote reference; never touches the local file. */
    suspend fun delete(reference: RemoteAiFile): Boolean {
        require(reference.accountScope == aiAccountScope(key) && validAiFileId(reference.metadata.id))
        val result = files.delete(authorization, reference.metadata.id)
        require(result.id == reference.metadata.id) { "File identity mismatch" }
        return result.deleted
    }

    suspend fun recognize(original: File, purpose: DocumentRecognition = DocumentRecognition.TEXT): OcrResult =
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            ocr.recognize(authorization, textPart(purpose.wireValue), documentPart(original)).use { body ->
                coroutineScope {
                    val reader = async(Dispatchers.IO) {
                        try { decodeOcrResult(body) { context.ensureActive() } }
                        catch (error: Exception) { context.ensureActive(); throw error }
                    }
                    // Cancellation of await closes the socket even while the reader is blocked in read().
                    try { reader.await() } finally { body.close() }
                }
            }
        }
}

private fun textPart(value: String) = value.toRequestBody("text/plain".toMediaType())

/** Stream bounded files; a 100 MiB document must not create another 100 MiB byte array. */
internal fun documentPart(file: File, filename: String = file.name): MultipartBody.Part {
    require(file.isFile && file.canRead()) { "Document is not readable" }
    val bytes = file.length()
    require(bytes in 1..100L * 1024 * 1024) { "Document must be between 1 byte and 100 MiB" }
    require(filename.substringAfterLast('.', "").lowercase() in setOf("pdf", "doc", "docx", "ppt", "pptx", "png", "jpg", "jpeg", "webp", "gif", "bmp", "tif", "tiff")) {
        "Unsupported document format"
    }
    val modified = file.lastModified()
    val safeFilename = filename.replace(Regex("[\\r\\n\\u0000]"), "_")
    val body = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = bytes
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            if (file.length() != bytes || file.lastModified() != modified) throw IOException("Document changed before upload")
            file.inputStream().use { input ->
                val buffer = ByteArray(16384)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > bytes) throw IOException("Document changed during upload")
                    sink.write(buffer, 0, count)
                }
                if (copied != bytes || file.lastModified() != modified) throw IOException("Document changed during upload")
            }
        }
    }
    return MultipartBody.Part.createFormData("file", safeFilename, body)
}

/** OCR sends heartbeat newlines then a JSON object, including errors with HTTP 200. */
internal fun decodeOcrResult(body: ResponseBody, checkActive: () -> Unit = {}): OcrResult {
    val source = body.source()
    val buffer = okio.Buffer()
    val maxBytes = 32L * 1024 * 1024
    while (true) {
        checkActive()
        val count = source.read(buffer, minOf(8192L, maxBytes + 1 - buffer.size))
        checkActive()
        if (count < 0) break
        if (buffer.size > maxBytes) throw IOException("OCR result exceeds local size limit")
    }
    val json = Json { ignoreUnknownKeys = true }
    val value = try { json.parseToJsonElement(buffer.readUtf8()) as? JsonObject }
        catch (_: Exception) { null } ?: throw IOException("Invalid OCR response")
    if (value["error"] != null) throw IOException("OCR recognition failed; no automatic retry")
    val result = try { json.decodeFromJsonElement<OcrResult>(value) }
        catch (_: Exception) { throw IOException("Invalid OCR response") }
    if (result.pageCount !in 1..1000 || result.pages.size != result.pageCount ||
        result.pages.map { it.index }.sorted() != (0 until result.pageCount).toList() ||
        result.purpose !in DocumentRecognition.entries.map { it.wireValue } ||
        result.pages.any { it.finishReason !in setOf("stop", "length") }) throw IOException("Invalid OCR page metadata")
    return result.copy(pages = result.pages.sortedBy { it.index })
}

/** Prepared only at request time; persisted conversations must retain their original document. */
internal fun attachDocuments(messages: List<DeepSeekMessage>, references: List<RemoteAiFile>,
    key: String, now: Long = System.currentTimeMillis() / 1000): List<DeepSeekMessage> {
    if (references.isEmpty()) return messages
    references.forEach { it.checkUsable(key, now) }
    val existing = messages.sumOf { message -> (message.content as? JsonArray)?.count {
        (it as? JsonObject)?.get("type") == JsonPrimitive("file")
    } ?: 0 }
    require(existing + references.size <= 20) { "At most 20 file references per request" }
    require(messages.lastOrNull()?.role == "user") { "Documents require a user message" }
    val last = messages.last()
    val parts = when (val content = last.content) {
        is JsonArray -> content.toList()
        is JsonPrimitive -> listOf(buildJsonObject { put("type", "text"); put("text", content.content) })
        else -> error("Invalid user content")
    }
    val attachments = references.map { buildJsonObject { put("type", "file"); put("file_id", it.metadata.id) } }
    return messages.dropLast(1) + last.copy(content = JsonArray(parts + attachments))
}
