package com.zsz.studyassistant.data

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class StoredTagDraft(val group: TagMergeGroup, val selected: Boolean = false)
@Serializable
data class TagDraftDocument(val version: Int = 1, val fingerprint: String, val savedAt: Long,
    val drafts: List<StoredTagDraft>)

object TagDraftCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun encode(document: TagDraftDocument): String { validate(document); return json.encodeToString(document) }
    fun decode(text: String): TagDraftDocument = json.decodeFromString<TagDraftDocument>(text).also(::validate)
    private fun validate(document: TagDraftDocument) {
        require(document.version == 1 && document.savedAt > 0)
        require(document.fingerprint.matches(Regex("[0-9a-f]{64}")))
        require(document.drafts.size <= 1000)
        document.drafts.forEach {
            require(it.group.sourceIds.size <= 10000 && it.group.sourceIds.all { id -> id > 0 })
            require(it.group.sourceIds.distinct().size == it.group.sourceIds.size)
            require(it.group.targetName.length <= 4096 && it.group.reason.length <= 4096)
        }
    }
    fun matches(document: TagDraftDocument, base: TagInventory): Boolean =
        document.fingerprint == base.fingerprint() &&
            document.drafts.all { draft -> draft.group.sourceIds.all { id -> base.tags.any { it.id == id } } }
}

/** Excluded from question backups. Malformed/stale drafts are kept until a user edit replaces them. */
internal class TagDraftStore(context: Context) {
    companion object {
        private val lock = Any()
        private val revision = AtomicLong()
        fun reserveRevision(): Long = revision.incrementAndGet()
    }
    private val file = PrivateJsonFile(File(context.noBackupFilesDir, "tag-merge-draft-v1.json"), 1_000_000)
    fun read(): TagDraftDocument? = file.read()?.let(TagDraftCodec::decode)
    fun write(document: TagDraftDocument?, ticket: Long = reserveRevision()): Boolean = synchronized(lock) {
        if (ticket != revision.get()) return@synchronized false
        if (document == null) file.clear() else file.write(TagDraftCodec.encode(document))
        ticket == revision.get()
    }
}
