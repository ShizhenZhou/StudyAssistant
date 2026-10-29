package com.zsz.studyassistant.data

import android.content.Context
import com.zsz.studyassistant.ChatItem
import java.io.File
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
    val completedChats: List<ChatItem>? = null, val completedQuestion: String? = null, val version: Int = 1, val revision: Long = 0, val redoFor: QuestionId? = null, val gradeHistoryEnabled: Boolean = false)

data class DraftSummary(val id: QuestionId, val title: String, val createdAt: Long)
object ConversationDraftCodec {
    private val json = Json { encodeDefaults=true; ignoreUnknownKeys=true }
    fun encode(value: ConversationDraft): String { validate(value); return json.encodeToString(value) }
    fun decode(text: String): ConversationDraft = json.decodeFromString<ConversationDraft>(text).also(::validate)
    private fun validate(value: ConversationDraft) {
        require(value.version==1 && QuestionIdentity.valid(value.id) && QuestionIdentity.time(value.id)==value.createdAt)
        require(UUID.fromString(value.generation).toString()==value.generation)
        require(value.notebookId==null || value.notebookId==value.id)
        require(value.redoFor==null || QuestionIdentity.valid(value.redoFor))
        require(value.baseline==null || value.baseline.matches(Regex("[0-9a-f]{64}")))
        require(value.action in setOf("initial","followup","regenerate_initial","regenerate_followup","grade"))
        require(value.images.size<=3)
    }
}
/** Private, noBackup, per-problem drafts. Reservations are made before scheduling asynchronous writes. */
internal class ConversationDraftStore(context: Context) {
    private val directory=File(context.noBackupFilesDir,"conversation-drafts-v1")
    private val lock=Any()
    private val writers=mutableMapOf<QuestionId,String>()
    private val revisions=mutableMapOf<QuestionId,Long>()
    private fun file(id: QuestionId): PrivateJsonFile {
        require(QuestionIdentity.valid(id)); return PrivateJsonFile(File(directory,"$id.json"),32*1024*1024)
    }
    fun reserve(id: QuestionId, generation: String) = synchronized(lock) { writers[id]=generation; revisions.remove(id) }
    fun write(value: ConversationDraft, baseline: SavePayload?): ConversationDraft = synchronized(lock) {
        if(writers[value.id]!=value.generation || value.revision < (revisions[value.id] ?: -1)) return@synchronized value
        val prepared=if(value.baseline==null && baseline!=null) value.copy(baseline=PendingSaveCodec.fingerprint(baseline)) else value
        file(value.id).write(ConversationDraftCodec.encode(prepared)); revisions[value.id]=value.revision; prepared
    }
    fun read(id: QuestionId): ConversationDraft? = synchronized(lock) { file(id).read()?.let(ConversationDraftCodec::decode) }
    fun clear(id: QuestionId, generation: String) = synchronized(lock) {
        val current = writers[id] ?: read(id)?.generation
        if(current != generation) return@synchronized
        file(id).clear(); writers[id]="closed-$generation"; revisions.remove(id)
    }
    fun invalidate(ids: Collection<QuestionId>) = synchronized(lock) {
        ids.forEach { writers[it]="deleted"; file(it).clear() }
    }
    /** Explicit erase only. Tombstones reject writes already queued by a stopped request. */
    fun clearAll() = synchronized(lock) {
        writers.keys.toList().forEach { writers[it]="deleted" }
        revisions.clear()
        val files=if(directory.exists()) checkNotNull(directory.listFiles()).toList() else emptyList()
        val names=files.filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") || it.name.endsWith(".json.new") }
            .map { it.name.removeSuffix(".bak").removeSuffix(".new") }.distinct()
        names.forEach { PrivateJsonFile(File(directory,it),32*1024*1024).clear() }
    }
    fun summaries(): Pair<List<DraftSummary>,Int> = synchronized(lock) {
        if(!directory.exists()) return@synchronized emptyList<DraftSummary>() to 0
        val entries=mutableListOf<DraftSummary>(); var bad=0
        val names=checkNotNull(directory.listFiles()).filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            .map { it.name.removeSuffix(".bak").removeSuffix(".json") }.distinct()
        for(id in names) try {
            val value=read(id) ?: error("Missing draft")
            entries+=DraftSummary(id,value.question.take(100),value.createdAt)
        } catch (_: Exception) { bad++ }
        entries.sortedByDescending { it.createdAt } to bad
    }
    fun exportable(): List<ConversationDraft> = synchronized(lock) {
        val files=directory.listFiles()?.filter { it.name.endsWith(".json") }.orEmpty()
        check(files.sumOf { it.length() }<=32L*1024*1024)
        files.mapNotNull { runCatching { read(it.name.removeSuffix(".json")) }.getOrNull() }
    }
}

/** Keep every previous question/answer until a replacement has completed successfully. */
fun regenerationBase(items: List<ChatItem>): List<ChatItem> {
    val lastQuestion=items.indexOfLast { it.role=="question" || it.role=="user" }
    return if(lastQuestion<0) items else items.take(lastQuestion+1)
}
