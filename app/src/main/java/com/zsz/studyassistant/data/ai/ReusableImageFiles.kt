package com.zsz.studyassistant.data

import java.security.MessageDigest
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Bounded references only. Original images always remain in messages/local storage. */
internal class ReusableImageFiles(
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val readState: (File) -> String? = { PrivateJsonFile(it, 128 * 1024).read() },
    private val writeState: (File, String) -> Unit = { path, text -> PrivateJsonFile(path, 128 * 1024).write(text) },
    private val upload: suspend (ByteArray, String, String) -> DeepSeekFile
) {
    @Serializable private data class Entry(val file: DeepSeekFile? = null, val retryAfter: Long = 0)
    @Serializable private data class State(val version: Int = 1, val scope: String, val entries: Map<String, Entry>)
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>(32, .75f, true)
    private var account: String? = null
    private var storage: File? = null
    private var loaded = false
    private val json = Json { ignoreUnknownKeys = true }

    private fun persist() {
        val path = storage ?: return
        val scope = account ?: return
        // Failure merely disables reuse after restart; original image requests remain valid.
        runCatching { writeState(path, json.encodeToString(State(scope = scope, entries = entries))) }
    }

    private fun restore(scope: String) {
        if (loaded) return
        loaded = true
        val path = storage ?: return
        runCatching {
            val state = readState(path)?.let { json.decodeFromString<State>(it) } ?: return
            require(state.version == 1 && state.entries.size <= 32)
            if (state.scope != scope) return
            require(state.entries.all { (key, value) -> key.matches(Regex("[0-9a-f]{64}")) && value.retryAfter >= 0 &&
                (value.file == null || (validAiFileId(value.file.id) && value.file.expiresAt != null)) })
            entries.putAll(state.entries.filterValues { it.file?.expiresAt?.let { expiry -> expiry > clock() + 300 } == true || it.retryAfter > clock() })
        }
    }

    suspend fun prepare(messages: List<DeepSeekMessage>, accountKey: String, path: File? = null): List<DeepSeekMessage> = mutex.withLock {
        // Never retain credentials, or reuse a file from a previous account.
        val scope = digest(accountKey)
        if (scope != account || storage != path) { entries.clear(); account = scope; storage = path; loaded = false }
        restore(scope)
        try { messages.map { message ->
            val parts = message.content as? JsonArray ?: return@map message
            message.copy(content = JsonArray(parts.map parts@{ part ->
                currentCoroutineContext().ensureActive()
                val obj = part as? JsonObject ?: return@parts part
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "image_url") return@parts part
                val image = obj["image_url"] as? JsonObject ?: return@parts part
                // The file content block has no detail option; retain explicit low-resolution semantics.
                if (image["detail"] == JsonPrimitive("low")) return@parts part
                val url = image["url"]?.jsonPrimitive?.contentOrNull ?: return@parts part
                val marker = ";base64,"
                val split = url.indexOf(marker)
                if (!url.startsWith("data:image/") || split < 0) return@parts part
                val key = digest(url)
                val old = entries[key]
                val now = clock()
                if (old == null) {
                    if (entries.size >= 32) entries.remove(entries.keys.first())
                    entries[key] = Entry() // First use stays inline: no extra network round trip.
                } else if (old.file?.expiresAt?.let { it > now + 300 } == true) {
                    return@parts reference(old.file.id)
                } else if (old.retryAfter <= now) {
                    // A failed/uncertain upload is not retried immediately (it may already exist remotely).
                    entries[key] = Entry(retryAfter = now + 300)
                    persist()
                    try {
                        val bytes = Base64.getDecoder().decode(url.substring(split + marker.length))
                        val file = upload(bytes, url.substring(5, split), accountKey)
                        currentCoroutineContext().ensureActive()
                        if (file.id.startsWith("file-") && file.expiresAt?.let { it > now + 300 } == true) {
                            entries[key] = Entry(file)
                            return@parts reference(file.id)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { currentCoroutineContext().ensureActive() }
                }
                while (entries.size > 32) entries.remove(entries.keys.first())
                part
            }))
        } } finally { persist() }
    }

    suspend fun invalidate(accountKey: String) = mutex.withLock {
        if (account == digest(accountKey)) { entries.clear(); persist() }
    }
    suspend fun clear(path: File) = mutex.withLock {
        entries.clear(); loaded = false
        runCatching { PrivateJsonFile(path, 128 * 1024).clear() }
    }
    private fun reference(id: String) = buildJsonObject {
        put("type", "file"); put("file_id", id)
    }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal object AiImageUploads {
    val cache = ReusableImageFiles { bytes, type, key ->
        val text = "text/plain".toMediaType()
        ApiClient.imageUpload.uploadImage("user_data".toRequestBody(text), "created_at".toRequestBody(text),
            "86400".toRequestBody(text), MultipartBody.Part.createFormData("file", "question-image",
                bytes.toRequestBody(type.toMediaType())), "Bearer $key")
    }
}
