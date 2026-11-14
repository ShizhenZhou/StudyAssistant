package com.zsz.studyassistant.data

import java.security.MessageDigest
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

/** Volatile, bounded references only. Original images always remain in messages/local storage. */
internal class ReusableImageFiles(
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val upload: suspend (ByteArray, String) -> DeepSeekFile
) {
    private data class Entry(val file: DeepSeekFile? = null, val retryAfter: Long = 0)
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>(32, .75f, true)
    private var account: String? = null

    suspend fun prepare(messages: List<DeepSeekMessage>, accountKey: String): List<DeepSeekMessage> = mutex.withLock {
        // Never retain credentials, or reuse a file from a previous account.
        val scope = digest(accountKey)
        if (scope != account) { entries.clear(); account = scope }
        messages.map { message ->
            val parts = message.content as? JsonArray ?: return@map message
            message.copy(content = JsonArray(parts.map parts@{ part ->
                currentCoroutineContext().ensureActive()
                val obj = part as? JsonObject ?: return@parts part
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "image_url") return@parts part
                val image = obj["image_url"] as? JsonObject ?: return@parts part
                val url = image["url"]?.jsonPrimitive?.contentOrNull ?: return@parts part
                val marker = ";base64,"
                val split = url.indexOf(marker)
                if (!url.startsWith("data:image/") || split < 0) return@parts part
                val key = digest(url)
                val old = entries[key]
                val now = clock()
                if (old == null) {
                    entries[key] = Entry() // First use stays inline: no extra network round trip.
                } else if (old.file?.expiresAt?.let { it > now + 300 } == true) {
                    return@parts reference(old.file.id, image)
                } else if (old.retryAfter <= now) {
                    // A failed/uncertain upload is not retried immediately (it may already exist remotely).
                    entries[key] = Entry(retryAfter = now + 300)
                    try {
                        val bytes = Base64.getDecoder().decode(url.substring(split + marker.length))
                        val file = upload(bytes, url.substring(5, split))
                        currentCoroutineContext().ensureActive()
                        if (file.id.startsWith("file-") && file.expiresAt?.let { it > now + 300 } == true) {
                            entries[key] = Entry(file)
                            return@parts reference(file.id, image)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { currentCoroutineContext().ensureActive() }
                }
                while (entries.size > 32) entries.remove(entries.keys.first())
                part
            }))
        }
    }

    suspend fun invalidate() = mutex.withLock { entries.clear() }
    private fun reference(id: String, image: JsonObject) = buildJsonObject {
        put("type", "file"); put("file_id", id)
        image["detail"]?.let { put("detail", it) }
    }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal object AiImageUploads {
    val cache = ReusableImageFiles { bytes, type ->
        val text = "text/plain".toMediaType()
        ApiClient.deepSeek.uploadImage("user_data".toRequestBody(text), "created_at".toRequestBody(text),
            "86400".toRequestBody(text), MultipartBody.Part.createFormData("file", "question-image",
                bytes.toRequestBody(type.toMediaType())))
    }
}
