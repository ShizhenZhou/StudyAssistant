package com.zsz.studyassistant.data

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
enum class SaveTarget { HISTORY, NOTEBOOK }

/** Only answer content is replayed for notebook rows; category/deletion/review metadata stays live. */
@Serializable
data class SavePayload(val id: QuestionId, val createdAt: Long, val text: String, val answer: String,
    val conversationJson: String? = null, val updatedAt: Long = 0, val source: String = "",
    val imageBase64: String? = null, val imagesJson: String? = null) {
    companion object {
        fun history(row: SolveSession) = SavePayload(row.id, row.createdAt, row.text, row.answer,
            row.conversationJson, row.updatedAt, row.source,
            row.imageBytes?.let { Base64.getEncoder().encodeToString(it) }, row.imagesJson)
        fun notebook(row: Question) = SavePayload(row.id, row.createdAt, row.text, row.answer, row.conversationJson)
    }
    fun historyRow(link: QuestionId?) = SolveSession(id, createdAt, updatedAt, source, text, answer,
        conversationJson, imageBase64?.let { Base64.getDecoder().decode(it) }, imagesJson, link)
}

@Serializable
data class PendingSave(val target: SaveTarget, val payload: SavePayload, val expected: String?,
    val token: String = UUID.randomUUID().toString(), val version: Int = 1)
@Serializable
private data class PendingSaveExport(val format: String = "study-assistant-pending-saves", val version: Int = 1,
    val entries: List<PendingSave>, val interruptedDrafts: List<ConversationDraft> = emptyList())

data class PendingSaveScan(val entries: List<PendingSave>, val unreadable: Int)

object PendingSaveCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun fingerprint(value: SavePayload): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(value).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun encode(value: PendingSave): String { validate(value); return json.encodeToString(value) }
    fun decode(text: String): PendingSave = json.decodeFromString<PendingSave>(text).also(::validate)
    fun export(entries: List<PendingSave>, drafts: List<ConversationDraft> = emptyList()): String = json.encodeToString(PendingSaveExport(entries = entries, interruptedDrafts = drafts))
    private fun validate(value: PendingSave) {
        require(value.version == 1 && QuestionIdentity.valid(value.payload.id))
        require(value.payload.createdAt == QuestionIdentity.time(value.payload.id))
        require(value.expected == null || value.expected == "unavailable" || value.expected.matches(Regex("[0-9a-f]{64}")))
        require(UUID.fromString(value.token).toString() == value.token)
        if (value.target == SaveTarget.NOTEBOOK) require(value.expected != null)
    }
}

/** Per-answer atomic files, app-private and excluded from ordinary backup/cloud restore. */
internal class PendingSaveStore(context: Context) {
    companion object { private val lock = Any() }
    private val directory = File(context.noBackupFilesDir, "pending-saves-v1")
    private fun file(target: SaveTarget, id: QuestionId): PrivateJsonFile {
        require(QuestionIdentity.valid(id))
        return PrivateJsonFile(File(directory, "${target.name.lowercase()}-$id.json"), 32 * 1024 * 1024)
    }
    fun write(value: PendingSave): Unit = synchronized(lock) { file(value.target, value.payload.id).write(PendingSaveCodec.encode(value)) }
    fun remove(value: PendingSave): Unit = synchronized(lock) {
        val file = file(value.target, value.payload.id)
        val actual = file.read()?.let(PendingSaveCodec::decode) ?: return@synchronized
        if (actual.token == value.token) file.clear()
    }
    fun invalidateNotebook(ids: Collection<QuestionId>): Unit = synchronized(lock) { ids.forEach { file(SaveTarget.NOTEBOOK, it).clear() } }
    fun invalidateHistory(ids: Collection<QuestionId>): Unit = synchronized(lock) { ids.forEach { file(SaveTarget.HISTORY, it).clear() } }
    fun scan(): PendingSaveScan = synchronized(lock) {
        if (!directory.exists()) return@synchronized PendingSaveScan(emptyList(), 0)
        val files = checkNotNull(directory.listFiles()) { "Cannot read pending saves" }
            .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            .map { it.name.removeSuffix(".bak") }.distinct()
        val entries = mutableListOf<PendingSave>()
        var unreadable = 0
        for (name in files) {
            try {
                val value = PendingSaveCodec.decode(PrivateJsonFile(File(directory, name), 32 * 1024 * 1024).read()
                    ?: error("Missing pending save"))
                check(name == "${value.target.name.lowercase()}-${value.payload.id}.json")
                entries += value
            } catch (_: Exception) { unreadable++ }
        }
        PendingSaveScan(entries, unreadable)
    }
    fun discardAll(): Unit = synchronized(lock) {
        if (!directory.exists()) return@synchronized
        val names = checkNotNull(directory.listFiles()).filter {
            it.name.endsWith(".json") || it.name.endsWith(".json.bak") || it.name.endsWith(".json.new")
        }.map { it.name.removeSuffix(".bak").removeSuffix(".new") }.distinct()
        names.forEach { PrivateJsonFile(File(directory, it), 32 * 1024 * 1024).clear() }
    }
}

/** Caller serializes this repository with history deletion/import/clear. */
internal class PendingSaveRepository(private val db: AppDatabase, private val store: PendingSaveStore) {
    private val dao get() = db.questionDao()
    suspend fun saveHistory(row: SolveSession, allowCreate: Boolean, cutoff: Long) = withContext(Dispatchers.IO) {
        var pending: PendingSave? = null
        try { db.withTransaction {
            val before = dao.solveSessionOnce(row.id)
            if (before == null && (!allowCreate || row.createdAt < cutoff)) return@withTransaction
            val payload = SavePayload.history(row.copy(createdAt = QuestionIdentity.earliest(before?.createdAt ?: 0, row.createdAt)))
            pending = PendingSave(SaveTarget.HISTORY, payload, before?.let { PendingSaveCodec.fingerprint(SavePayload.history(it)) })
            store.write(pending!!)
            persistHistoryWrite(dao, payload.historyRow(null), allowCreate, cutoff)
        } } catch (e: Exception) {
            if (pending == null) store.write(PendingSave(SaveTarget.HISTORY, SavePayload.history(row),
                if (allowCreate) null else "unavailable"))
            throw e
        }
        pending?.let(store::remove)
    }
    suspend fun saveNotebook(payload: SavePayload) = withContext(Dispatchers.IO) {
        var pending: PendingSave? = null
        try { db.withTransaction {
            val before = dao.heavyOnce(payload.id)
            if (before == null || before.deleted) {
                pending = PendingSave(SaveTarget.NOTEBOOK, payload, "unavailable")
                store.write(pending!!)
                error("Pending save target unavailable")
            }
            pending = PendingSave(SaveTarget.NOTEBOOK, payload, PendingSaveCodec.fingerprint(SavePayload.notebook(before)))
            store.write(pending!!)
            dao.updateAnswerContent(payload.id, payload.text, payload.answer, payload.conversationJson)
        } } catch (e: Exception) {
            if (pending == null) store.write(PendingSave(SaveTarget.NOTEBOOK, payload, "unavailable"))
            throw e
        }
        pending?.let(store::remove)
    }
    suspend fun retryAll(cutoff: Long): PendingSaveScan = withContext(Dispatchers.IO) {
        for (entry in store.scan().entries) {
            val applied = db.withTransaction {
                when (entry.target) {
                    SaveTarget.NOTEBOOK -> {
                        val row = dao.heavyOnce(entry.payload.id) ?: return@withTransaction false
                        if (row.deleted) return@withTransaction false
                        val actual = PendingSaveCodec.fingerprint(SavePayload.notebook(row))
                        if (actual == PendingSaveCodec.fingerprint(entry.payload)) return@withTransaction true
                        if (actual != entry.expected) return@withTransaction false
                        dao.updateAnswerContent(row.id, entry.payload.text, entry.payload.answer, entry.payload.conversationJson)
                    }
                    SaveTarget.HISTORY -> {
                        if (entry.payload.createdAt < cutoff) return@withTransaction true
                        val row = dao.solveSessionOnce(entry.payload.id)
                        val actual = row?.let { PendingSaveCodec.fingerprint(SavePayload.history(it)) }
                        if (actual == PendingSaveCodec.fingerprint(entry.payload)) return@withTransaction true
                        if (actual != entry.expected) return@withTransaction false
                        persistHistoryWrite(dao, entry.payload.historyRow(null), entry.expected == null, cutoff)
                    }
                }
                true
            }
            if (applied) store.remove(entry)
        }
        store.scan()
    }
}
