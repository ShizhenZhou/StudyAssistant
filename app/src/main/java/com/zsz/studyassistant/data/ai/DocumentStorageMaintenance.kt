package com.zsz.studyassistant.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import androidx.room3.support.getSupportWrapper
import androidx.room3.withWriteTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class DocumentStorageSummary(val totalBytes: Long, val totalCount: Int,
    val removedBytes: Long = 0, val removedCount: Int = 0)

/** Explicit local maintenance only. The caller must pause draft/save writers and hold its data
 * maintenance locks throughout this call. No cloud, remote file or external backup is touched. */
internal object DocumentStorageMaintenance {
    private const val LIMIT = 10000
    private const val GRACE_MS = 7L * 24 * 60 * 60 * 1000
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Observation(val sha256: String, val bytes: Long, val modifiedAt: Long, val firstUnreferencedAt: Long)
    @Serializable
    private data class Registry(val version: Int = 1, val entries: List<Observation> = emptyList())
    private data class Snapshot(val file: File, val bytes: Long, val modifiedAt: Long, val identity: String?)

    suspend fun inspect(context: Context, db: AppDatabase, activeHashes: Set<String>, clean: Boolean): DocumentStorageSummary =
        withContext(Dispatchers.IO) {
            try { lock.withLock {
                val coroutine = currentCoroutineContext()
                val checkActive = { coroutine.ensureActive() }
                require(activeHashes.size <= LIMIT && activeHashes.all(hashPattern::matches))
                val originals = File(context.filesDir, "ai-documents")
                val drafts = File(context.noBackupFilesDir, "conversation-drafts-v1")
                val pending = File(context.noBackupFilesDir, "pending-saves-v1")
                val registry = PrivateJsonFile(File(context.noBackupFilesDir, "ai-document-maintenance-v1/state.json"), 2 * 1024 * 1024)
                val source = db.getSupportWrapper()
                // A write transaction prevents a new persisted reference appearing after the scan.
                db.withWriteTransaction {
                    val files = listFiles(originals, originalsOnly = true, checkActive)
                    val totalBytes = files.fold(0L) { sum, file -> Math.addExact(sum, file.bytes) }
                    val pendingFiles = listFiles(pending, originalsOnly = false, checkActive)
                    if (pendingFiles.isNotEmpty()) {
                        if (clean) error("Pending saves must be resolved first")
                        // Occupancy remains useful; do not start orphan observations without a complete reference scan.
                        return@withWriteTransaction DocumentStorageSummary(totalBytes, files.size)
                    }
                    val references = activeHashes.toMutableSet()
                    scanDatabase(source, references, checkActive)
                    val draftFiles = listFiles(drafts, originalsOnly = false, checkActive)
                    draftFiles.forEach { scanDraft(it.file, references, checkActive) }
                    val now = System.currentTimeMillis()
                    val previous = registry.read()?.let { json.decodeFromString<Registry>(it) } ?: Registry()
                    require(previous.version == 1 && previous.entries.size <= LIMIT)
                    require(previous.entries.map { it.sha256 }.distinct().size == previous.entries.size)
                    previous.entries.forEach {
                        require(hashPattern.matches(it.sha256) && it.bytes > 0 && it.modifiedAt > 0 && it.firstUnreferencedAt > 0)
                    }
                    val indexed = previous.entries.associateBy { it.sha256 }
                    val observed = files.filter { it.file.name !in references }.map { file ->
                        val old = indexed[file.file.name]
                        val since = old?.takeIf { it.bytes == file.bytes && it.modifiedAt == file.modifiedAt &&
                            it.firstUnreferencedAt <= now }?.firstUnreferencedAt ?: now
                        Observation(file.file.name, file.bytes, file.modifiedAt, since)
                    }
                    val eligible = observed.filter { now - it.firstUnreferencedAt >= GRACE_MS &&
                        now >= it.modifiedAt && now - it.modifiedAt >= GRACE_MS }.map { it.sha256 }.toSet()
                    DocumentStore.withOriginalsLock { ConversationDraftStore.withMaintenanceLock {
                        checkActive()
                        // Recheck the whole snapshot before the first deletion, including atomic .bak/.new files.
                        require(listFiles(originals, true, checkActive) == files)
                        require(listFiles(drafts, false, checkActive) == draftFiles)
                        require(listFiles(pending, false, checkActive).isEmpty())
                        val candidates = if (clean) files.filter { it.file.name in eligible } else emptyList()
                        candidates.forEach { candidate ->
                            require(candidate.file.parentFile.canonicalFile == originals.canonicalFile)
                            require(snapshot(candidate.file) == candidate)
                            checkActive()
                        }
                        // A verified observation is durable before any original is removed.
                        registry.write(json.encodeToString(Registry(entries = observed)))
                        var removedBytes = 0L
                        var removedCount = 0
                        for (candidate in candidates) {
                            checkActive()
                            require(candidate.file.delete())
                            removedBytes += candidate.bytes
                            removedCount++
                        }
                        if (removedCount > 0) registry.write(json.encodeToString(Registry(entries = observed.filter { it.sha256 !in eligible })))
                        DocumentStorageSummary(totalBytes - removedBytes, files.size - removedCount, removedBytes, removedCount)
                    } }
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw DocumentProblem("document.cleanupFailed") }
        }

    /** Only SHA originals count as document storage. Temporary/unknown files are never removed. */
    private fun listFiles(directory: File, originalsOnly: Boolean, check: () -> Unit): List<Snapshot> {
        check()
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return emptyList()
        require(!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory)
        val entries = ArrayList<Snapshot>()
        Files.newDirectoryStream(directory.toPath()).use { paths ->
            for (path in paths) {
                check()
                val name = path.fileName.toString()
                if (originalsOnly && !hashPattern.matches(name)) continue
                if (!originalsOnly) require(name.endsWith(".json") || name.endsWith(".json.bak") || name.endsWith(".json.new"))
                require(entries.size < LIMIT)
                val entry = snapshot(path.toFile())
                require(entry.file.parentFile.canonicalFile == directory.canonicalFile)
                if (originalsOnly) require(entry.bytes in 1..DocumentStore.MAX_BYTES)
                else require(entry.bytes in 1..32L * 1024 * 1024)
                entries += entry
            }
        }
        return entries.sortedBy { it.file.name }
    }

    private fun snapshot(file: File): Snapshot {
        val info = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(info.isRegularFile && !info.isSymbolicLink && info.lastModifiedTime().toMillis() > 0)
        return Snapshot(file, info.size(), info.lastModifiedTime().toMillis(), info.fileKey()?.toString())
    }

    private fun addHash(references: MutableSet<String>, hash: String) {
        require(hashPattern.matches(hash))
        references += hash
        require(references.size <= LIMIT)
    }

    private fun scanDatabase(source: SupportSQLiteDatabase, references: MutableSet<String>, check: () -> Unit) {
        for (table in listOf("questions", "solve_sessions")) {
            check()
            source.query("SELECT 1 FROM $table WHERE conversationJson IS NOT NULL AND NOT json_valid(conversationJson) LIMIT 1").use {
                require(!it.moveToFirst())
            }
            source.query("SELECT 1 FROM $table WHERE conversationJson IS NOT NULL AND json_type(conversationJson) <> 'array' LIMIT 1").use {
                require(!it.moveToFirst())
            }
            source.query("SELECT 1 FROM $table AS t, json_tree(t.conversationJson) AS a " +
                "WHERE a.key IN ('documents','localDocuments') AND a.type <> 'array' LIMIT 1").use {
                require(!it.moveToFirst())
            }
            // All JSON expansion stays inside SQLite; CursorWindow receives only a SHA or NULL.
            source.query("SELECT CASE WHEN d.type = 'object' THEN CASE " +
                "WHEN (SELECT count(*) FROM json_each(d.value) WHERE key = 'sha256') = 1 " +
                "AND json_type(d.value,'$.sha256') = 'text' AND length(json_extract(d.value,'$.sha256')) = 64 " +
                "THEN json_extract(d.value,'$.sha256') ELSE NULL END ELSE NULL END " +
                "FROM $table AS t, json_tree(t.conversationJson) AS a, " +
                "json_each(CASE WHEN a.key IN ('documents','localDocuments') AND a.type = 'array' THEN a.value ELSE '[]' END) AS d").use {
                while (it.moveToNext()) { check(); require(!it.isNull(0)); addHash(references, it.getString(0)) }
            }
        }
    }

    private fun scanDraft(file: File, references: MutableSet<String>, check: () -> Unit) {
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { stream ->
            JsonReader(InputStreamReader(stream, Charsets.UTF_8.newDecoder())).use { reader ->
                reader.beginObject()
                var id: String? = null
                var createdAt: Long? = null
                val seen = mutableSetOf<String>()
                while (reader.hasNext()) {
                    check()
                    val name = reader.nextName()
                    require(seen.add(name))
                    when (name) {
                        "id" -> { require(reader.peek() == JsonToken.STRING); id = reader.nextString() }
                        "createdAt" -> createdAt = reader.nextLong()
                        "version" -> require(reader.nextInt() == 1)
                        "documents", "localDocuments" -> scanDocuments(reader, references, check)
                        "generation" -> {
                            require(reader.peek() == JsonToken.STRING)
                            val value = reader.nextString()
                            require(java.util.UUID.fromString(value).toString() == value)
                        }
                        "base", "messages", "completedChats" -> {
                            if (name == "completedChats" && reader.peek() == JsonToken.NULL) reader.nextNull()
                            else {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    require(reader.peek() == JsonToken.BEGIN_OBJECT)
                                    scanValue(reader, references, check, 1)
                                }
                                reader.endArray()
                            }
                        }
                        "model", "action", "source", "question" -> { require(reader.peek() == JsonToken.STRING); reader.skipValue() }
                        "photo", "ask", "grade", "historyAllowed" -> { require(reader.peek() == JsonToken.BOOLEAN); reader.nextBoolean() }
                        "images" -> {
                            reader.beginArray()
                            while (reader.hasNext()) { check(); require(reader.peek() == JsonToken.STRING); reader.skipValue() }
                            reader.endArray()
                        }
                        else -> scanValue(reader, references, check, 1)
                    }
                }
                reader.endObject()
                val required = setOf("id", "createdAt", "generation", "base", "messages", "model", "action", "source",
                    "question", "photo", "ask", "grade", "historyAllowed", "images", "notebookId")
                require(seen.containsAll(required))
                val identity = requireNotNull(id)
                require(QuestionIdentity.valid(identity) && createdAt == QuestionIdentity.time(identity))
                require(file.name.removeSuffix(".bak").removeSuffix(".new").removeSuffix(".json") == identity)
                require(reader.peek() == JsonToken.END_DOCUMENT)
            }
        }
    }

    /** Stream past image/base64/text values; collect every documents/localDocuments array, including
     * completed/old conversations. Never decode a complete draft into an in-memory ChatItem list. */
    private fun scanValue(reader: JsonReader, references: MutableSet<String>, check: () -> Unit, depth: Int) {
        check(); require(depth <= 128)
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    check()
                    when (reader.nextName()) {
                        "documents", "localDocuments" -> scanDocuments(reader, references, check)
                        else -> scanValue(reader, references, check, depth + 1)
                    }
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) scanValue(reader, references, check, depth + 1)
                reader.endArray()
            }
            else -> reader.skipValue()
        }
    }

    private fun scanDocuments(reader: JsonReader, references: MutableSet<String>, check: () -> Unit) {
        reader.beginArray()
        while (reader.hasNext()) {
            check()
            reader.beginObject()
            var found = false
            while (reader.hasNext()) {
                if (reader.nextName() == "sha256") {
                    require(reader.peek() == JsonToken.STRING)
                    addHash(references, reader.nextString())
                    found = true
                } else reader.skipValue()
            }
            reader.endObject()
            require(found)
        }
        reader.endArray()
    }
}
