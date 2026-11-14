package com.zsz.studyassistant.ui

import android.content.Context
import android.util.AtomicFile
import com.zsz.studyassistant.data.DocumentRecognition
import com.zsz.studyassistant.data.OcrResult
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

@Serializable
internal data class OcrDraft(
    val updatedAt: Long = System.currentTimeMillis(),
    val result: OcrResult? = null,
    val text: String = "",
    val retryAfter: Long = 0,
    val errorKey: String? = null
)

/** Disposable private drafts, never included in backups or remote requests. */
@OptIn(ExperimentalSerializationApi::class)
internal object OcrDraftCache {
    const val REQUEST_WINDOW_MS = 180_000L
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 32
    private const val MAX_ENTRY_BYTES = 36L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val json = Json { ignoreUnknownKeys = true }
    private data class Pending(val draft: OcrDraft)
    private val pending = mutableMapOf<String, Pending>()

    fun key(image: ByteArray, accountScope: String, purpose: DocumentRecognition): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(accountScope.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(purpose.wireValue.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(MessageDigest.getInstance("SHA-256").digest(image))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun read(context: Context, key: String): OcrDraft? = withContext(dispatcher) {
        synchronized(pending) { pending[key]?.draft }?.let { return@withContext it }
        val directory = directory(context)
        prune(directory)
        val file = File(directory, "$key.json")
        if (!file.exists() && !File(directory, "$key.json.bak").exists()) return@withContext null
        try {
            AtomicFile(file).openRead().use { input ->
                if (file.length() > MAX_ENTRY_BYTES) throw IOException("OCR draft exceeds local limit")
                val draft = json.decodeFromStream<OcrDraft>(input)
                val now = System.currentTimeMillis()
                if (draft.updatedAt > now + REQUEST_WINDOW_MS || now - draft.updatedAt > MAX_AGE_MS) null else draft
            }
        } catch (_: Exception) {
            // A damaged draft may have contained an unresolved request. Recover with a safe delay.
            val recovery = OcrDraft(retryAfter = System.currentTimeMillis() + REQUEST_WINDOW_MS,
                errorKey = "ocr.cacheFailed")
            writeNow(context, key, recovery)
            recovery
        }
    }

    /** Must finish before a billable request starts, including the uncertain-request deadline. */
    suspend fun write(context: Context, key: String, draft: OcrDraft) = withContext(dispatcher) {
        synchronized(pending) { pending.remove(key) }
        writeNow(context, key, draft)
    }

    /** Save editing independently of the dialog job so closing/rotation cannot discard the last edit. */
    fun saveEdit(context: Context, key: String, draft: OcrDraft, onFailure: () -> Unit) {
        val item = Pending(draft)
        synchronized(pending) {
            pending[key] = item
            while (pending.size > MAX_ENTRIES) {
                pending.entries.minByOrNull { it.value.draft.updatedAt }?.let { pending.remove(it.key) }
            }
        }
        scope.launch {
            delay(300)
            if (synchronized(pending) { pending[key] !== item }) return@launch
            try {
                writeNow(context, key, draft)
                synchronized(pending) { if (pending[key] === item) pending.remove(key) }
            } catch (_: Exception) {
                // Keep the most recent edit available in memory for reopening; show a safe warning.
                withContext(Dispatchers.Main) { onFailure() }
            }
        }
    }

    private fun directory(context: Context): File = File(context.noBackupFilesDir, "ocr_drafts").also {
        if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create private OCR draft directory")
    }

    private fun writeNow(context: Context, key: String, draft: OcrDraft) {
        require(key.matches(Regex("[0-9a-f]{64}")))
        val directory = directory(context)
        val file = File(directory, "$key.json")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            json.encodeToStream(draft, BoundedOutput(output, MAX_ENTRY_BYTES))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        prune(directory, file)
    }

    private fun prune(directory: File, preserve: File? = null) {
        val now = System.currentTimeMillis()
        // The single IO dispatcher guarantees no live writer while removing interrupted temp files.
        directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}\\.json\\.new")) }
            ?.forEach { it.delete() }
        val files = directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}\\.json")) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        var count = if (preserve != null) 1 else 0
        var bytes = preserve?.length() ?: 0L
        files.filter { it != preserve }.forEach { file ->
            if (now - file.lastModified() > MAX_AGE_MS || file.length() > MAX_ENTRY_BYTES ||
                count >= MAX_ENTRIES || bytes + file.length() > MAX_TOTAL_BYTES) {
                AtomicFile(file).delete()
            } else {
                count++
                bytes += file.length()
            }
        }
    }

    private class BoundedOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) {
            if (++count > limit) throw IOException("OCR draft exceeds local limit")
            out.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            count += length
            if (count > limit) throw IOException("OCR draft exceeds local limit")
            out.write(bytes, offset, length)
        }
    }
}
