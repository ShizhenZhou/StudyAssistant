package com.zsz.studyassistant.data

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
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
    internal val accountScope = aiAccountScope(key)
    private val authorization get() = "Bearer $key"
    private fun bind(file: DeepSeekFile) = RemoteAiFile(file, accountScope).also {
        it.checkUsable(key, clock())
    }

    /** Explicit upload; no hidden retries. An interrupted upload may still exist/bill remotely. */
    suspend fun uploadDocument(original: File, purpose: DocumentRecognition = DocumentRecognition.TEXT_AND_FIGURES,
        lifetimeSeconds: Long = 86400, filename: String = original.name): RemoteAiFile = withContext(Dispatchers.IO) {
        require(lifetimeSeconds in 3600..2592000)
        documentRequest {
            val part = documentPart(original, filename)
            val metadata = files.upload(authorization, textPart(purpose.wireValue), textPart("created_at"),
                textPart(lifetimeSeconds.toString()), part)
            require(metadata.purpose == purpose.wireValue && metadata.expiresAt != null)
            bind(metadata)
        }
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
            documentRequest {
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
}

/** Only fixed local keys escape this boundary; neither API bodies nor their causes are retained. */
private suspend fun <T> documentRequest(block: suspend () -> T): T = try { block() }
catch (error: kotlinx.coroutines.CancellationException) { throw error }
catch (error: DocumentProblem) { throw error }
catch (error: retrofit2.HttpException) {
    val payload = try {
        error.response()?.errorBody()?.use { body ->
            val source = body.source()
            val buffer = okio.Buffer()
            while (buffer.size <= 16384) {
                if (source.read(buffer, minOf(4096L, 16385L - buffer.size)) < 0) break
            }
            if (buffer.size > 16384) null else Json.parseToJsonElement(buffer.readUtf8()) as? JsonObject
        }
    } catch (_: Exception) { null }
    throw DocumentProblem(documentApiErrorKey(error.code(), payload?.get("error") as? JsonObject),
        uploadRejected = error.code() in setOf(400, 401, 402, 403, 404, 413, 415, 422, 429))
}
catch (_: InterruptedIOException) { throw DocumentProblem("document.timeout") }
catch (_: Exception) { throw DocumentProblem("document.requestFailed") }

/** Interpret only documented fixed phrases/codes; never render a server-supplied message. */
internal fun documentApiErrorKey(status: Int?, error: JsonObject?): String {
    val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
    val type = (error?.get("type") as? JsonPrimitive)?.contentOrNull
    val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
    return when {
        status == 402 -> "document.insufficientBalance"
        status == 413 -> "document.tooLarge"
        status == 429 -> "document.rateLimited"
        status == 408 || status == 504 || code == "gateway_timeout_error" || type == "gateway_timeout_error" -> "document.timeout"
        status != null && status >= 500 -> "document.serviceUnavailable"
        "password-protected" in message -> "document.passwordProtected"
        "the document has more than 1000 pages" in message -> "document.tooManyPages"
        "file is too large: max 100 mib" in message -> "document.tooLarge"
        "the file could not be parsed or its format is not supported" in message ||
            "the file format is not supported" in message || "the image could not be decoded" in message -> "document.unsupportedFormat"
        message == "ocr did not finish in time" || message == "ocr did not finish within 180 seconds" -> "document.timeout"
        message == "the ocr service is temporarily unavailable. please try again later." || message == "internal server error" -> "document.serviceUnavailable"
        else -> "document.requestFailed"
    }
}

private fun textPart(value: String) = value.toRequestBody("text/plain".toMediaType())

/** Stream bounded files; a 100 MiB document must not create another 100 MiB byte array. */
internal fun documentPart(file: File, filename: String = file.name): MultipartBody.Part {
    if (!file.isFile || !file.canRead()) throw DocumentProblem("document.readFailed")
    val bytes = file.length()
    if (bytes > 100L * 1024 * 1024) throw DocumentProblem("document.tooLarge")
    if (bytes <= 0) throw DocumentProblem("document.unsupportedFormat")
    if (filename.substringAfterLast('.', "").lowercase() !in setOf("pdf", "doc", "docx", "ppt", "pptx", "png", "jpg", "jpeg", "webp", "gif", "bmp", "tif", "tiff"))
        throw DocumentProblem("document.unsupportedFormat")
    val modified = file.lastModified()
    val safeFilename = documentUploadFilename(filename)
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

/** Multipart-only alias: preserve the original local name and the uploaded extension. */
internal fun documentUploadFilename(filename: String): String {
    val safe = filename.map { if (it.code < 32 || it.code == 127 || it in charArrayOf('/', '\\', '"')) '_' else it }.joinToString("")
    if (safe.toByteArray(Charsets.UTF_8).size <= 512) return safe
    val dot = safe.lastIndexOf('.')
    val extension = if (dot >= 0) safe.substring(dot) else ""
    require(extension.toByteArray(Charsets.UTF_8).size < 512)
    val stem = if (dot >= 0) safe.substring(0, dot) else safe
    val budget = 512 - extension.toByteArray(Charsets.UTF_8).size
    var used = 0
    var end = 0
    while (end < stem.length) {
        val chars = Character.charCount(stem.codePointAt(end))
        val bytes = stem.substring(end, end + chars).toByteArray(Charsets.UTF_8).size
        if (used + bytes > budget) break
        used += bytes
        end += chars
    }
    return stem.substring(0, end) + extension
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
        if (buffer.size > maxBytes) throw DocumentProblem("document.requestFailed")
    }
    val json = Json { ignoreUnknownKeys = true }
    val value = try { json.parseToJsonElement(buffer.readUtf8()) as? JsonObject }
        catch (_: Exception) { null } ?: throw DocumentProblem("document.requestFailed")
    if (value["error"] != null) throw DocumentProblem(documentApiErrorKey(null, value["error"] as? JsonObject))
    val result = try { json.decodeFromJsonElement<OcrResult>(value) }
        catch (_: Exception) { throw DocumentProblem("document.requestFailed") }
    if (result.pageCount !in 1..1000 || result.pages.size != result.pageCount ||
        result.pages.map { it.index }.sorted() != (0 until result.pageCount).toList() ||
        result.purpose !in DocumentRecognition.entries.map { it.wireValue } ||
        result.pages.any { it.finishReason !in setOf("stop", "length") }) throw DocumentProblem("document.requestFailed")
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
