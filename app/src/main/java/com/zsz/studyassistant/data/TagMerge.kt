package com.zsz.studyassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class TagMergeException(val messageKey: String) : IllegalArgumentException(messageKey)

@Entity(tableName = "tag_merge_undo")
data class TagMergeUndo(@PrimaryKey val id: Int = 1, val createdAt: Long, val beforeJson: String, val afterJson: String)

/** Additive migration only; exported v3 backups intentionally do not contain the local undo journal. */
object TagMergeSchema {
    const val CREATE = "CREATE TABLE IF NOT EXISTS tag_merge_undo (id INTEGER NOT NULL, createdAt INTEGER NOT NULL, beforeJson TEXT NOT NULL, afterJson TEXT NOT NULL, PRIMARY KEY(id))"
}

@Serializable
data class TagMergeGroup(val sourceIds: List<Long>, val targetName: String, val reason: String = "")

@Serializable
data class TagInventory(val tags: List<Tag>, val links: List<QuestionTag>) {
    fun normalized(): TagInventory = copy(tags = tags.sortedBy { it.id },
        links = links.sortedWith(compareBy<QuestionTag> { it.questionId }.thenBy { it.tagId }))
    fun subset(ids: Set<Long>): TagInventory = TagInventory(tags.filter { it.id in ids }, links.filter { it.tagId in ids }).normalized()
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest(TagMergeEngine.json.encodeToString(normalized()).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

data class TagMergePreview(val baseline: TagInventory, val groups: List<TagMergeGroup>, val affectedQuestions: Int)
data class TagMergeResult(val groups: Int, val removedTags: Int, val affectedQuestions: Int)

/** Every mutating entry point must be enclosed in one Room transaction by its caller. */
object TagMergeEngine {
    internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    @Serializable private data class JournalTag(val id: Long, val name: String, val createdAt: Long)
    @Serializable private data class Journal(val version: Int, val tags: List<JournalTag>, val links: List<QuestionTag>)
    private fun encodeJournal(state: TagInventory): String = json.encodeToString(
        Journal(1, state.tags.map { JournalTag(it.id, it.name, it.createdAt) }, state.links))
    private fun decodeJournal(text: String): TagInventory {
        val value = json.decodeFromString<Journal>(text)
        if (value.version != 1) fail("tagMerge.undoInvalid")
        return TagInventory(value.tags.map { Tag(it.id, it.name, it.createdAt) }, value.links).normalized()
    }
    suspend fun inventory(dao: QuestionDao): TagInventory = TagInventory(dao.getAllTagsOnce(), dao.allQuestionTagsOnce()).normalized()

    fun preview(base: TagInventory, requested: List<TagMergeGroup>): TagMergePreview {
        if (requested.isEmpty()) fail("tagMerge.noSelection")
        val used = mutableSetOf<Long>()
        val names = mutableSetOf<String>()
        val existing = base.tags.associateBy { it.id }
        val groups = requested.map { group ->
            val ids = group.sourceIds.distinct().sorted()
            val name = group.targetName.trim()
            if (ids.size < 2 || ids.any { it <= 0 || it !in existing } || group.sourceIds.size != ids.size) fail("tagMerge.invalidGroup")
            if (name.isEmpty() || name.length > 120 || name.any { Character.isISOControl(it) }) fail("tagMerge.invalidName")
            if (ids.any { !used.add(it) } || !names.add(name)) fail("tagMerge.overlap")
            if (base.tags.any { it.name == name && it.id !in ids }) fail("tagMerge.targetConflict")
            group.copy(sourceIds = ids, targetName = name, reason = group.reason.take(240))
        }
        val count = base.links.filter { it.tagId in used }.map { it.questionId }.distinct().size
        return TagMergePreview(base.normalized(), groups, count)
    }

    suspend fun apply(dao: QuestionDao, prepared: TagMergePreview, now: Long = System.currentTimeMillis()): TagMergeResult {
        val before = inventory(dao)
        if (before.fingerprint() != prepared.baseline.fingerprint()) fail("tagMerge.changed")
        val plan = preview(before, prepared.groups)
        val touched = mutableSetOf<Long>()
        val affected = before.links.filter { link -> plan.groups.any { link.tagId in it.sourceIds } }.map { it.questionId }.toSet()
        for (id in affected) if (!dao.questionExists(id)) fail("tagMerge.changed")
        for (group in plan.groups) {
            val sources = group.sourceIds.toSet()
            val target = before.tags.filter { it.id in sources && it.name == group.targetName }.minByOrNull { it.id }?.id
                ?: dao.insertTag(Tag(name = group.targetName, createdAt = now))
            touched += sources; touched += target
            val links = before.links.filter { it.tagId in sources }.map { QuestionTag(it.questionId, target) }.distinct()
            dao.deleteLinksForTags(group.sourceIds)
            dao.insertQuestionTags(links)
            dao.deleteTagsByIds(group.sourceIds.filter { it != target })
        }
        val after = inventory(dao)
        dao.putTagMergeUndo(TagMergeUndo(createdAt = now,
            beforeJson = encodeJournal(before.subset(touched)), afterJson = encodeJournal(after.subset(touched))))
        return TagMergeResult(plan.groups.size, before.tags.size - after.tags.size, affected.size)
    }

    private fun journal(record: TagMergeUndo): Pair<TagInventory, TagInventory> {
        try {
            val before = decodeJournal(record.beforeJson)
            val after = decodeJournal(record.afterJson)
            for (state in listOf(before, after)) {
                val ids = state.tags.map { it.id }.toSet()
                if (ids.size != state.tags.size || ids.any { it <= 0 } || state.links.distinct().size != state.links.size ||
                    state.links.any { it.tagId !in ids || !QuestionIdentity.valid(it.questionId) }) fail("tagMerge.undoInvalid")
            }
            if (before.tags.isEmpty() || after.tags.isEmpty()) fail("tagMerge.undoInvalid")
            if (before.links.map { it.questionId }.toSet() != after.links.map { it.questionId }.toSet()) fail("tagMerge.undoInvalid")
            return before to after
        } catch (e: TagMergeException) { throw e }
        catch (_: Exception) { fail("tagMerge.undoInvalid") }
    }

    private fun checkUndo(current: TagInventory, before: TagInventory, after: TagInventory): Set<Long> {
        val touched = (before.tags + after.tags).map { it.id }.toSet()
        if (current.subset(touched) != after || current.tags.any { t -> t.id !in touched && before.tags.any { it.name == t.name } }) {
            fail("tagMerge.undoChanged")
        }
        return touched
    }

    fun canUndo(current: TagInventory, record: TagMergeUndo?): Boolean {
        if (record == null) return false
        return try { val (before, after) = journal(record); checkUndo(current, before, after); true }
        catch (_: TagMergeException) { false }
    }

    suspend fun undo(dao: QuestionDao): Int {
        val record = dao.tagMergeUndoOnce() ?: fail("tagMerge.undoMissing")
        val (before, after) = journal(record)
        val touched = checkUndo(inventory(dao), before, after)
        for (id in before.links.map { it.questionId }.distinct()) if (!dao.questionExists(id)) fail("tagMerge.undoChanged")
        dao.deleteLinksForTags(touched.toList())
        dao.deleteTagsByIds(touched.toList())
        dao.insertTags(before.tags)
        dao.insertQuestionTags(before.links)
        dao.clearTagMergeUndo()
        return before.links.map { it.questionId }.distinct().size
    }

    private fun fail(key: String): Nothing = throw TagMergeException(key)
}
