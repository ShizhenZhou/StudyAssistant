package com.zsz.studyassistant.data

import android.content.Context
import com.zsz.studyassistant.ChatItem
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ConversationDraft(val id: QuestionId, val createdAt: Long, val generation: String,
    val model: String, val action: String, val source: String, val question: String,
    val photo: Boolean, val ask: Boolean, val grade: Boolean, val historyAllowed: Boolean,
    val notebookId: QuestionId?, val images: List<String>, val base: List<ChatItem>,
    val messages: List<DeepSeekMessage>, val raw: String = "", val thinking: String? = null,
    val baseline: String? = null, val complete: Boolean = false,
    val completedChats: List<ChatItem>? = null, val completedQuestion: String? = null, val version: Int = 1, val revision: Long = 0, val redoFor: QuestionId? = null, val gradeHistoryEnabled: Boolean = false, val webEnabled: Boolean = false,
    val requestPrefix: String = "", val continuationHistory: List<DeepSeekMessage> = emptyList(),
    val continuationAnnotations: kotlinx.serialization.json.JsonArray? = null,
    val resumableContext: AiResponseContext? = null,
    val promptInventory: PromptInventory? = null)

data class DraftSummary(val id: QuestionId, val title: String, val createdAt: Long)
object ConversationDraftCodec {
    private val json = Json { encodeDefaults=true; ignoreUnknownKeys=true }
    private val conversationJson = Json { ignoreUnknownKeys=true }
    /** A draft created before notebook save may attach only to its unchanged base/completed copy. */
    internal fun matchesNotebook(value: ConversationDraft, row: Question): Boolean {
        fun matches(chats: List<ChatItem>, text: String) = row.text == text &&
            row.answer == chats.lastOrNull { it.role == "assistant" }?.content.orEmpty() &&
            row.conversationJson == conversationJson.encodeToString(chats)
        return !row.deleted && (matches(value.base, value.question) ||
            value.completedChats?.let { matches(it, value.completedQuestion ?: value.question) } == true)
    }
    fun encode(value: ConversationDraft): String { validate(value); return json.encodeToString(value) }
    fun decode(text: String): ConversationDraft = json.decodeFromString<ConversationDraft>(text).also(::validate)
    internal fun decodeForFile(text: String, id: QuestionId) =
        decode(text).also { check(it.id == id) { "Draft ID mismatch" } }
    private fun validate(value: ConversationDraft) {
        require(value.version==1 && QuestionIdentity.valid(value.id) && QuestionIdentity.time(value.id)==value.createdAt)
        require(UUID.fromString(value.generation).toString()==value.generation)
        require(value.notebookId==null || value.notebookId==value.id)
        require(value.redoFor==null || QuestionIdentity.valid(value.redoFor))
        require(value.baseline==null || value.baseline.matches(Regex("[0-9a-f]{64}")))
        require(value.action in setOf("initial","followup","regenerate_initial","regenerate_followup","grade","restore_initial","restore_grade"))
        require(value.images.size<=3)
    }
}
/** Private, noBackup, per-problem drafts. Reservations are made before scheduling asynchronous writes. */
internal class ConversationDraftStore(private val directory: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "conversation-drafts-v1"))

    companion object {
        private const val MAX_BYTES = 32 * 1024 * 1024
        private val maintenanceLock = Any()
        // The UI takes this lock only to register a generation. Disk IO stays on workers.
        private val ownerLock = Any()
        private val writers = mutableMapOf<QuestionId, String>()
        private val revisions = mutableMapOf<QuestionId, Long>()
        internal fun <T> withMaintenanceLock(block: () -> T): T = synchronized(maintenanceLock) { block() }
    }
    private val lock = maintenanceLock
    private fun path(id: QuestionId): File {
        require(QuestionIdentity.valid(id))
        return File(directory, "$id.json")
    }
    private fun deleteFiles(primary: File) {
        for (suffix in listOf(".new", ".bak", "")) Files.deleteIfExists(File(primary.path + suffix).toPath())
    }

    fun reserve(id: QuestionId, generation: String) = synchronized(ownerLock) {
        writers[id] = generation
        revisions.remove(id)
        Unit
    }

    fun write(value: ConversationDraft, baseline: SavePayload?): ConversationDraft {
        fun current() = writers[value.id] == value.generation && value.revision >= (revisions[value.id] ?: -1)
        if (!synchronized(ownerLock) { current() }) return value
        val prepared = if (value.baseline == null && baseline != null)
            value.copy(baseline = PendingSaveCodec.fingerprint(baseline)) else value
        val text = ConversationDraftCodec.encode(prepared)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        synchronized(lock) {
            if (!synchronized(ownerLock) { current() }) return value
            val target = path(value.id)
            check(directory.isDirectory || directory.mkdirs())
            val backup = File(directory, "${target.name}.bak")
            val staged = File(directory, "${target.name}.new")
            try {
                staged.outputStream().use { it.write(bytes); it.fd.sync() }
                synchronized(ownerLock) {
                    if (!current()) return value
                    Files.move(staged.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    revisions[value.id] = value.revision
                }
                check(target.length() == bytes.size.toLong() && target.readText() == text) { "Local draft verification failed" }
                // Until publication succeeds, a legacy backup remains available to the reader.
                Files.deleteIfExists(backup.toPath())
            } finally { if (staged.exists()) staged.delete() }
        }
        return prepared
    }

    private fun readCopy(id: QuestionId): Pair<ConversationDraft, Long>? {
        val primary = path(id)
        val backup = File(primary.path + ".bak")
        var failure: Exception? = null
        // Legacy AtomicFile treated .bak as the last committed copy. Check its identity
        // before using it; leave both files untouched if either is damaged.
        for (copy in listOf(backup, primary)) {
            if (!copy.exists()) continue
            try {
                val bytes = copy.length()
                require(bytes in 1..MAX_BYTES.toLong())
                return ConversationDraftCodec.decodeForFile(copy.readText(), id) to bytes
            } catch (e: Exception) { failure = e }
        }
        failure?.let { throw it }
        return null
    }

    fun read(id: QuestionId): ConversationDraft? = synchronized(lock) { readCopy(id)?.first }

    fun clear(id: QuestionId, generation: String): Unit = synchronized(lock) {
        val registered = synchronized(ownerLock) { writers[id] }
        val current = registered ?: read(id)?.generation
        if (current != generation) return@synchronized
        synchronized(ownerLock) {
            if (writers[id] != registered) return
            writers.remove(id)
            revisions.remove(id)
        }
        deleteFiles(path(id))
    }

    fun invalidate(ids: Collection<QuestionId>) = synchronized(lock) {
        synchronized(ownerLock) { ids.forEach { writers.remove(it); revisions.remove(it) } }
        ids.forEach { deleteFiles(path(it)) }
    }

    fun clearAll() = synchronized(lock) {
        synchronized(ownerLock) { writers.clear(); revisions.clear() }
        val files=if(directory.exists()) checkNotNull(directory.listFiles()).toList() else emptyList()
        val names=files.filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") || it.name.endsWith(".json.new") }
            .map { it.name.removeSuffix(".bak").removeSuffix(".new") }.distinct()
        names.forEach { deleteFiles(File(directory,it)) }
    }
    private fun draftIds(): List<String> {
        if (!directory.exists()) return emptyList()
        return checkNotNull(directory.listFiles()).asSequence().map { it.name }
            .filter { it.endsWith(".json") || it.endsWith(".json.bak") }
            .map { it.removeSuffix(".bak").removeSuffix(".json") }.distinct().sorted().toList()
    }

    private fun <T> scan(transform: (String) -> T): Pair<List<T>, Int> = synchronized(lock) {
        val entries = mutableListOf<T>()
        var unreadable = 0
        for (id in draftIds()) {
            try { entries += transform(id) } catch (_: Exception) { unreadable++ }
        }
        entries to unreadable
    }

    fun summaries(): Pair<List<DraftSummary>, Int> {
        val (entries, unreadable) = scan { id ->
            val draft = checkNotNull(read(id))
            DraftSummary(id, draft.question.take(100), draft.createdAt)
        }
        return entries.sortedByDescending { it.createdAt } to unreadable
    }

    fun exportable(): Pair<List<ConversationDraft>, Int> {
        var remaining = MAX_BYTES.toLong()
        return scan { id ->
            val (draft, bytes) = checkNotNull(readCopy(id))
            require(bytes <= remaining)
            remaining -= bytes
            draft
        }
    }
}

/** Keep every previous question/answer until a replacement has completed successfully. */
fun regenerationBase(items: List<ChatItem>): List<ChatItem> {
    val lastQuestion=items.indexOfLast { it.role=="question" || it.role=="user" }
    return if(lastQuestion<0) items else items.take(lastQuestion+1)
}
