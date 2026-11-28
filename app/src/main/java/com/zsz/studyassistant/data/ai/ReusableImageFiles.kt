package com.zsz.studyassistant.data

import java.security.MessageDigest
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
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
    data class References(val scope: String, val path: File?, val files: Map<String, DeepSeekFile>)
    data class Prepared(val messages: List<DeepSeekMessage>, val references: References? = null)

    @Serializable private data class Entry(val file: DeepSeekFile? = null, val retryAfter: Long = 0) {
        val persistent get() = file != null || retryAfter > 0
    }
    @Serializable private data class State(val version: Int = 1, val scope: String, val entries: Map<String, Entry>)
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>(32, .75f, true)
    private var account: String? = null
    private var storage: File? = null
    private var loaded = false
    private var dirty = false
    private val json = Json { ignoreUnknownKeys = true }

    private fun persistChanges() {
        if (!dirty) return
        val path = storage ?: return
        val scope = account ?: return
        // Failure merely disables reuse after restart; original image requests remain valid.
        runCatching {
            val state = State(scope = scope, entries = entries.filterValues { it.persistent })
            writeState(path, json.encodeToString(state))
        }.onSuccess { dirty = false }
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

    private fun inlineImageUrl(part: JsonElement): String? {
        val obj = part as? JsonObject ?: return null
        if (obj["type"] != JsonPrimitive("image_url")) return null
        val image = obj["image_url"] as? JsonObject ?: return null
        // The file content block cannot express explicit low-resolution detail.
        if (image["detail"] == JsonPrimitive("low")) return null
        val url = (image["url"] as? JsonPrimitive)?.contentOrNull ?: return null
        return url.takeIf { it.startsWith("data:image/") && it.contains(";base64,") }
    }

    suspend fun prepare(messages: List<DeepSeekMessage>, accountKey: String, path: File? = null,
                        timeoutMillis: Long = 15_000): Prepared {
        currentCoroutineContext().ensureActive()
        if (messages.none { (it.content as? JsonArray)?.any { part -> inlineImageUrl(part) != null } == true })
            return Prepared(messages)

        // Reusing uploads is optional. A slow reference must not hold up the original question.
        return withTimeoutOrNull(timeoutMillis) { prepareReferences(messages, accountKey, path) } ?: Prepared(messages)
    }

    private suspend fun prepareReferences(messages: List<DeepSeekMessage>, accountKey: String, path: File?): Prepared =
        mutex.withLock {
            val scope = aiAccountScope(accountKey)
            if (scope != account || storage != path) {
                entries.clear()
                account = scope
                storage = path
                loaded = false
                dirty = false
            }
            restore(scope)
            val used = mutableMapOf<String, DeepSeekFile>()
            try {
                val prepared = messages.map { message ->
                    val parts = message.content as? JsonArray ?: return@map message
                    var replaced: MutableList<JsonElement>? = null
                    parts.forEachIndexed { index, part ->
                        currentCoroutineContext().ensureActive()
                        val url = inlineImageUrl(part) ?: return@forEachIndexed
                        val key = digest(url)
                        val file = resolve(key, url, accountKey) ?: return@forEachIndexed
                        used[key] = file
                        val updated = replaced ?: parts.toMutableList().also { replaced = it }
                        updated[index] = reference(file.id)
                    }
                    replaced?.let { message.copy(content = JsonArray(it)) } ?: message
                }
                if (used.isEmpty()) Prepared(messages) else Prepared(prepared, References(scope, path, used))
            } finally { persistChanges() }
        }
    private suspend fun resolve(key: String, url: String, accountKey: String): DeepSeekFile? {
        val old = entries[key]
        if (old == null) {
            if (entries.size >= 32) {
                val evicted = entries.remove(entries.keys.first())
                if (evicted?.persistent == true) dirty = true
            }
            entries[key] = Entry() // The first use is inline. Its marker lives only in this process.
            return null
        }
        val now = clock()
        old.file?.takeIf { (it.expiresAt ?: 0) > now + 300 }?.let { return it }
        if (old.retryAfter > now) return null

        // An interrupted upload may already exist remotely, even across process restarts.
        entries[key] = Entry(retryAfter = now + 300)
        dirty = true
        persistChanges()
        try {
            val marker = ";base64,"
            val split = url.indexOf(marker)
            val bytes = Base64.getDecoder().decode(url.substring(split + marker.length))
            val file = upload(bytes, url.substring(5, split), accountKey)
            currentCoroutineContext().ensureActive()
            if (validAiFileId(file.id) && (file.expiresAt ?: 0) > now + 300) {
                entries[key] = Entry(file)
                dirty = true
                return file
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { currentCoroutineContext().ensureActive() }
        return null
    }

    suspend fun invalidate(references: References) = mutex.withLock {
        if (account != references.scope || storage != references.path) return@withLock
        val changed = entries.entries.removeAll { (key, entry) ->
            entry.file != null && entry.file == references.files[key]
        }
        if (changed) {
            dirty = true
            persistChanges()
        }
    }
    suspend fun clear(path: File) = mutex.withLock {
        entries.clear()
        loaded = false
        dirty = false
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
