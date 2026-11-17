package com.zsz.studyassistant.data

import com.zsz.studyassistant.QuoteAnchor
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class InputImageMetadata(val uri: String, val correctedText: String? = null)
@Serializable
internal data class InputDraftMetadata(
    val key: String, val text: String = "", val quote: String? = null, val anchor: QuoteAnchor? = null,
    val images: List<InputImageMetadata> = emptyList(),
    val documents: List<ConversationDocument> = emptyList(), val selection: ConversationDocument? = null,
    val excludedHashes: Set<String> = emptySet(), val version: Int = 1
)
internal data class InputDraftSnapshot(val metadata: InputDraftMetadata, val images: List<ByteArray> = emptyList()) {
    val bytes: Long get() = images.sumOf { it.size.toLong() } + metadata.text.length * 2L +
        metadata.quote.orEmpty().length * 2L + metadata.images.sumOf { it.correctedText.orEmpty().length * 2L } + 64 * 1024
    val empty: Boolean get() = metadata.text.isEmpty() && metadata.quote == null && images.isEmpty() &&
        metadata.documents.isEmpty() && metadata.selection == null && metadata.excludedHashes.isEmpty()
}

/** Separate from generated-answer recovery and Bak: exact local input, with bounded binary images. */
internal class InputDraftStore(private val directory: File) {
    companion object {
        const val NEW_ASK = "new-ask"
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGES_BYTES = 24L * 1024 * 1024
        const val MAX_METADATA = 1024 * 1024
        const val MAX_FILE = MAX_IMAGES_BYTES + MAX_METADATA + 1024
        const val MAX_TOTAL = 128L * 1024 * 1024
        const val MAX_DRAFTS = 32
        fun validKey(key: String) = key == NEW_ASK || QuestionIdentity.valid(key)
        fun validate(value: InputDraftSnapshot) {
            val m = value.metadata
            require(validKey(m.key) && m.version == 1 && m.text.length <= 200_000 && m.quote.orEmpty().length <= 200_000)
            require(value.images.size <= 3 && m.images.size == value.images.size && m.images.map { it.uri }.distinct().size == m.images.size)
            require(value.images.all { it.size in 1..MAX_IMAGE_BYTES } && value.images.sumOf { it.size.toLong() } <= MAX_IMAGES_BYTES)
            require(m.images.all { it.uri.length in 1..4096 && it.correctedText.orEmpty().length <= 100_000 })
            require(m.documents.size <= 20 && m.excludedHashes.size <= 1000)
            (m.documents + listOfNotNull(m.selection)).forEach(::validateDocument)
            require(m.excludedHashes.all { Regex("[0-9a-f]{64}").matches(it) })
            m.anchor?.let { require(it.messageKey.length <= 256 && it.scope.length <= 100 && it.sourceHash.length <= 256 &&
                it.start >= 0 && it.end >= it.start) }
        }
    }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val checked = mutableMapOf<String, Pair<Long, Long>>()
    private fun file(key: String): File { require(validKey(key)); return File(directory, "$key.input") }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun files(): List<File> = if (!directory.exists()) emptyList() else
        checkNotNull(directory.listFiles()).filter { it.name.endsWith(".input") }

    @Synchronized fun read(key: String): InputDraftSnapshot? {
        val file = file(key)
        if (!file.exists()) return null
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..MAX_FILE)
        val result = DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == 0x53414931) // SAI1
            val metadata = ByteArray(input.readInt().also { require(it in 1..MAX_METADATA) }).also(input::readFully)
            require(digest(metadata).contentEquals(ByteArray(32).also(input::readFully)))
            val value = json.decodeFromString<InputDraftMetadata>(metadata.toString(Charsets.UTF_8))
            require(value.key == key && value.images.size <= 3)
            var total = 0L
            val images = value.images.map {
                val size = input.readInt().also { require(it in 1..MAX_IMAGE_BYTES) }
                total += size; require(total <= MAX_IMAGES_BYTES)
                val bytes = ByteArray(size).also(input::readFully)
                require(digest(bytes).contentEquals(ByteArray(32).also(input::readFully)))
                bytes
            }
            require(input.read() == -1)
            InputDraftSnapshot(value, images).also(::validate)
        }
        checked[key] = file.length() to file.lastModified()
        return result
    }

    /** Commit runs under the scheduler's generation lock, after all slow serialization. */
    @Synchronized fun write(value: InputDraftSnapshot, commit: (() -> Unit) -> Unit = { it() }) {
        validate(value)
        val key = value.metadata.key
        val target = file(key)
        // A failed restore must never be silently replaced by a blank or newly typed draft.
        if (target.exists() && checked[key] != (target.length() to target.lastModified())) read(key)
        if (value.empty) { commit { clear(key) }; return }
        val metadata = json.encodeToString(value.metadata).toByteArray(Charsets.UTF_8)
        require(metadata.size <= MAX_METADATA)
        require(directory.isDirectory || directory.mkdirs())
        // An interrupted atomic write may leave a .new file; include it in the storage budget.
        val existing = checkNotNull(directory.listFiles()).filter { it.extension in setOf("input", "new") }
        require(existing.any { it.nameWithoutExtension == key } || existing.map { it.nameWithoutExtension }.distinct().size < MAX_DRAFTS)
        val required = 8L + metadata.size + 32L + value.images.sumOf { it.size + 36L }
        val temp = File(directory, "$key.new")
        require(existing.sumOf { it.length() } - temp.length() + required <= MAX_TOTAL)
        if (directory.usableSpace < required + 4L * 1024 * 1024) throw IOException("Input draft storage unavailable")
        try {
            temp.outputStream().use { raw ->
                val output = DataOutputStream(raw.buffered())
                output.writeInt(0x53414931); output.writeInt(metadata.size); output.write(metadata); output.write(digest(metadata))
                value.images.forEach { output.writeInt(it.size); output.write(it); output.write(digest(it)) }
                output.flush(); raw.fd.sync()
            }
            commit {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                checked[key] = target.length() to target.lastModified()
            }
        } finally { temp.delete() }
    }
    @Synchronized fun clear(key: String) {
        for (file in listOf(file(key), File(directory, "$key.new"))) if (file.exists()) check(file.delete())
        checked.remove(key)
    }
    @Synchronized fun clearAll() {
        if (directory.exists()) check(directory.listFiles()?.all { !it.exists() || it.delete() } == true)
        checked.clear()
    }
    @Synchronized fun documentHashes(): Set<String> = files().flatMap { file ->
        val draft = checkNotNull(read(file.name.removeSuffix(".input"))) // Corrupt drafts block cleanup.
        (draft.metadata.documents + listOfNotNull(draft.metadata.selection)).map { it.sha256 }
    }.toSet()
}

/** At most one queued snapshot per key; failed writes remain available for retry in bounded memory. */
internal data class InputDraftSaveState(val failedKeys: Set<String> = emptySet(), val revision: Long = 0)
internal class InputDraftPersistence(
    private val store: InputDraftStore, scope: CoroutineScope,
    private val onFailure: (String) -> Unit = {}, private val onSaved: (String) -> Unit = {}
) {
    companion object {
        private var shared: InputDraftPersistence? = null
        /** No Activity, ViewModel or subscriber callback is retained by this process owner. */
        @Synchronized fun shared(context: android.content.Context): InputDraftPersistence = shared ?: InputDraftPersistence(
            InputDraftStore(File(context.applicationContext.noBackupFilesDir, "input-drafts-v1")),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ).also { shared = it }
    }
    private class Pending(val key: String, val value: InputDraftSnapshot?, var failed: Boolean = false)
    private val lock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private val owners = linkedMapOf<String, String>()
    private val failureState = MutableStateFlow(InputDraftSaveState())
    val saveState: StateFlow<InputDraftSaveState> = failureState.asStateFlow()
    private fun failed(key: String) {
        failureState.update { InputDraftSaveState(it.failedKeys + key, it.revision + 1) }; onFailure(key)
    }
    private fun saved(key: String) {
        failureState.update { InputDraftSaveState(it.failedKeys - key, it.revision + 1) }; onSaved(key)
    }
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val io = kotlinx.coroutines.sync.Mutex()
    init {
        scope.launch {
            for (ignored in signal) while (true) {
                val work = synchronized(lock) { pending.values.firstOrNull { !it.failed } } ?: break
                try {
                    withContext(Dispatchers.IO) {
                        io.lock()
                        try {
                            if (work.value == null) synchronized(lock) {
                                if (pending[work.key] === work) store.clear(work.key)
                            } else store.write(work.value) { publish -> synchronized(lock) {
                                if (pending[work.key] === work) publish()
                            } }
                        } finally { io.unlock() }
                    }
                    if (synchronized(lock) { if (pending[work.key] === work) { pending.remove(work.key); true } else false })
                        saved(work.key)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (synchronized(lock) { if (pending[work.key] === work) { work.failed = true; true } else false }) failed(work.key)
                } catch (_: OutOfMemoryError) {
                    synchronized(lock) { if (pending[work.key] === work) work.failed = true }
                    failed(work.key)
                }
            }
        }
    }
    /** Opening a key elsewhere revokes an old ViewModel's delayed flushes, including after send. */
    fun reserve(key: String, owner: String) = synchronized(lock) {
        require(InputDraftStore.validKey(key))
        owners.remove(key); owners[key] = owner
        while (owners.size > InputDraftStore.MAX_DRAFTS * 2) owners.remove(owners.keys.first())
    }
    fun save(value: InputDraftSnapshot, owner: String? = null): Boolean {
        return try {
            synchronized(lock) { if (owner != null && owners[value.metadata.key] != owner) return true }
            InputDraftStore.validate(value)
            synchronized(lock) {
                if (owner != null && owners[value.metadata.key] != owner) return true
                val others = pending.values.filter { it.key != value.metadata.key }
                require(others.size < InputDraftStore.MAX_DRAFTS && others.sumOf { it.value?.bytes ?: 0 } + value.bytes <= 48L * 1024 * 1024)
                pending[value.metadata.key] = Pending(value.metadata.key, value.takeUnless { it.empty })
            }
            signal.trySend(Unit); true
        } catch (_: Exception) { failed(value.metadata.key); false }
          catch (_: OutOfMemoryError) { failed(value.metadata.key); false }
    }

    fun clear(key: String, owner: String? = null) {
        synchronized(lock) {
            if (owner != null && owners[key] != owner) return
            pending[key] = Pending(key, null)
        }
        signal.trySend(Unit)
    }
    fun retry() { synchronized(lock) { pending.values.forEach { it.failed = false } }; signal.trySend(Unit) }
    internal suspend fun awaitIdle() {
        while (synchronized(lock) { pending.values.any { !it.failed } }) delay(5)
        check(synchronized(lock) { pending.isEmpty() }) { "Input draft write failed" }
    }
    suspend fun read(key: String): InputDraftSnapshot? {
        synchronized(lock) { pending[key]?.let { return it.value } }
        return withContext(Dispatchers.IO) {
            io.lock()
            try { synchronized(lock) { pending[key]?.let { return@withContext it.value } }; store.read(key) }
            finally { io.unlock() }
        }
    }
    suspend fun documentHashes(): Set<String> = withContext(Dispatchers.IO) {
        io.lock()
        try {
            store.documentHashes() + synchronized(lock) { pending.values.flatMap { entry ->
                entry.value?.metadata?.let { (it.documents + listOfNotNull(it.selection)).map { d -> d.sha256 } }.orEmpty()
            }.toSet() }
        } finally { io.unlock() }
    }
    suspend fun clearAll() {
        synchronized(lock) { pending.clear(); owners.clear() } // Also revoke every old ViewModel's delayed flush.
        withContext(NonCancellable + Dispatchers.IO) {
            io.lock(); try { store.clearAll() } finally { io.unlock() }
        }
        failureState.update { InputDraftSaveState(revision = it.revision + 1) }
    }
}
