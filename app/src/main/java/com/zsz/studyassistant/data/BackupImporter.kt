package com.zsz.studyassistant.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Caller provides a DB transaction. This core also runs against an isolated test store. */
object BackupImporter {
    private val json = Json { encodeDefaults = true }
    suspend fun merge(dao: QuestionDao, file: BackupFile): ImportResult {
        BackupValidation.validate(file)
        val newQuestions = file.questions.filter { !dao.questionExists(it.id) }
        val newIds = newQuestions.map { it.id }.toSet()
        val neededCategories = newQuestions.mapNotNull { it.categoryId }.toSet()
        val neededTags = file.questionTags.filter { it.questionId in newIds }.map { it.tagId }.toSet()
        val cats = dao.allCategoriesOnce().associateBy { it.name }.toMutableMap()
        val tags = dao.getAllTagsOnce().associateBy { it.name }.toMutableMap()
        val catMap = mutableMapOf<Long, Long>()
        val tagMap = mutableMapOf<Long, Long>()
        var newCats = 0
        var newTags = 0
        file.categories.filter { it.id in neededCategories }.forEach { c ->
            val cat = cats[c.name] ?: Category(name = c.name, createdAt = c.createdAt).let {
                it.copy(id = dao.insertCategory(it)).also { added -> cats[c.name] = added; newCats++ }
            }
            catMap[c.id] = cat.id
        }
        file.tags.filter { it.id in neededTags }.forEach { t ->
            val tag = tags[t.name] ?: Tag(name = t.name, createdAt = t.createdAt).let {
                it.copy(id = dao.insertTag(it)).also { added -> tags[t.name] = added; newTags++ }
            }
            tagMap[t.id] = tag.id
        }
        val inserted = mutableSetOf<QuestionId>()
        var imageCount = 0
        for (q in newQuestions) {
            val images = BackupValidation.images(q.images)
            dao.insert(Question(id=q.id, text=q.text, answer=q.answer, createdAt=q.createdAt,
                imageBytes=images.firstOrNull(), conversationJson=q.conversationJson,
                deleted=q.deleted, categoryId=q.categoryId?.let { catMap.getValue(it) }))
            dao.insertImages(images.mapIndexed { pos, bytes ->
                QuestionImage(questionId=q.id, position=pos, bytes=bytes, source="import", createdAt=q.createdAt)
            })
            inserted += q.id
            imageCount += images.size
        }
        dao.insertQuestionTags(file.questionTags.filter { it.questionId in inserted }.map {
            QuestionTag(it.questionId,tagMap.getValue(it.tagId))
        })
        dao.insertReviews(file.reviews.filter { it.questionId in inserted }.map {
            Review(it.questionId,it.intervalStep,it.nextReviewAt,it.lastReviewedAt,it.reviewCount)
        })
        var sessionCount = 0
        for (s in file.solveSessions) {
            if (dao.solveSessionOnce(s.id) != null) continue
            val images = BackupValidation.images(s.images)
            dao.insertSolveSession(SolveSession(id=s.id, createdAt=s.createdAt, updatedAt=s.updatedAt,
                source=s.source, text=s.text, answer=s.answer, conversationJson=s.conversationJson,
                imageBytes=images.firstOrNull(), imagesJson=if (s.images.size>1) json.encodeToString(s.images) else null,
                savedQuestionId=s.id.takeIf { dao.questionExists(it) }))
            sessionCount++
            imageCount += images.size
        }
        // Existing histories may now have a restored notebook counterpart. Link only by UUID.
        file.questions.forEach { dao.linkExistingSession(it.id) }
        return ImportResult(inserted.size,newCats,newTags,file.questions.size-inserted.size,sessionCount,imageCount)
    }
}
