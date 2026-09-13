package com.zsz.studyassistant.data

import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 数据备份 / 恢复。
 *
 * 格式：单个 JSON 文件，图片以 base64 内嵌（自用场景题量不大，够用且便于手动搬运）。
 * 恢复策略（合并式，不覆盖现有数据）：
 *   · 分类、标签：同名复用，否则新建
 *   · 错题：题干完全相同的跳过（避免重复导入），否则新建
 *   · 关联与复习进度：按新旧 id 映射写入
 */
@Serializable
data class BackupFile(
    @SerialName("format") val format: String = FORMAT,
    @SerialName("version") val version: Int = 1,
    @SerialName("exportedAt") val exportedAt: Long = System.currentTimeMillis(),
    @SerialName("categories") val categories: List<BackupCategory> = emptyList(),
    @SerialName("tags") val tags: List<BackupTag> = emptyList(),
    @SerialName("questions") val questions: List<BackupQuestion> = emptyList(),
    @SerialName("questionTags") val questionTags: List<BackupQuestionTag> = emptyList(),
    @SerialName("reviews") val reviews: List<BackupReview> = emptyList()
) {
    companion object { const val FORMAT = "study-assistant-backup" }
}

@Serializable
data class BackupCategory(val id: Long, val name: String, val createdAt: Long = 0)

@Serializable
data class BackupTag(val id: Long, val name: String, val createdAt: Long = 0)

@Serializable
data class BackupQuestion(
    val id: Long,
    val text: String,
    val answer: String,
    val createdAt: Long = 0,
    /** 原题图片（base64 JPEG，无图则 null） */
    val imageBase64: String? = null,
    val conversationJson: String? = null,
    val deleted: Boolean = false,
    val categoryId: Long? = null
)

@Serializable
data class BackupQuestionTag(val questionId: Long, val tagId: Long)

@Serializable
data class BackupReview(
    val questionId: Long,
    val intervalStep: Int = 0,
    val nextReviewAt: Long = 0,
    val lastReviewedAt: Long = 0,
    val reviewCount: Int = 0
)

/** 导入结果统计（用于提示） */
data class ImportResult(val questions: Int, val categories: Int, val tags: Int, val skipped: Int)

object BackupManager {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 导出：把整库读成 JSON 文本 */
    suspend fun export(dao: QuestionDao): String {
        val cats = dao.allCategoriesOnce()
        val tags = dao.getAllTagsOnce()
        val qs = dao.allQuestionsOnce()
        val qTags = dao.allQuestionTagsOnce()
        val reviews = dao.allReviewsOnce()

        val file = BackupFile(
            categories = cats.map { BackupCategory(it.id, it.name, it.createdAt) },
            tags = tags.map { BackupTag(it.id, it.name, it.createdAt) },
            questions = qs.map {
                BackupQuestion(
                    id = it.id,
                    text = it.text,
                    answer = it.answer,
                    createdAt = it.createdAt,
                    imageBase64 = it.imageBytes?.let { b -> Base64.encodeToString(b, Base64.NO_WRAP) },
                    conversationJson = it.conversationJson,
                    deleted = it.deleted,
                    categoryId = it.categoryId
                )
            },
            questionTags = qTags.map { BackupQuestionTag(it.questionId, it.tagId) },
            reviews = reviews.map { BackupReview(it.questionId, it.intervalStep, it.nextReviewAt, it.lastReviewedAt, it.reviewCount) }
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /** 导入：合并式恢复，返回新增数量统计 */
    suspend fun import(dao: QuestionDao, text: String): ImportResult {
        val file = json.decodeFromString(BackupFile.serializer(), text)
        require(file.format == BackupFile.FORMAT) { "不是本应用的备份文件" }

        // 分类：同名复用
        val existingCats = dao.allCategoriesOnce().associateBy { it.name }
        val catMap = HashMap<Long, Long>()
        var newCats = 0
        for (c in file.categories) {
            val exist = existingCats[c.name]
            if (exist != null) {
                catMap[c.id] = exist.id
            } else {
                val newId = dao.insertCategory(Category(name = c.name, createdAt = c.createdAt))
                catMap[c.id] = newId
                newCats++
            }
        }

        // 标签：同名复用
        val existingTags = dao.getAllTagsOnce().associateBy { it.name }
        val tagMap = HashMap<Long, Long>()
        var newTags = 0
        for (t in file.tags) {
            val exist = existingTags[t.name]
            if (exist != null) {
                tagMap[t.id] = exist.id
            } else {
                val newId = dao.insertTag(Tag(name = t.name, createdAt = t.createdAt))
                tagMap[t.id] = newId
                newTags++
            }
        }

        // 错题：题干相同则跳过
        val existingTexts = dao.allQuestionsOnce().map { it.text }.toHashSet()
        val qMap = HashMap<Long, Long>()
        val toInsert = mutableListOf<Question>()
        val pendingOldIds = mutableListOf<Long>()
        var skipped = 0
        for (q in file.questions) {
            if (existingTexts.contains(q.text)) { skipped++; continue }
            toInsert += Question(
                text = q.text,
                answer = q.answer,
                createdAt = q.createdAt,
                imageBytes = q.imageBase64?.let { b -> runCatching { Base64.decode(b, Base64.NO_WRAP) }.getOrNull() },
                conversationJson = q.conversationJson,
                deleted = q.deleted,
                categoryId = q.categoryId?.let { catMap[it] }
            )
            pendingOldIds += q.id
        }
        if (toInsert.isNotEmpty()) {
            val newIds = dao.insertQuestions(toInsert)
            newIds.forEachIndexed { i, nid -> qMap[pendingOldIds[i]] = nid }
        }

        // 关联与复习进度
        val qts = file.questionTags.mapNotNull { qt ->
            val nq = qMap[qt.questionId] ?: return@mapNotNull null
            val nt = tagMap[qt.tagId] ?: return@mapNotNull null
            QuestionTag(nq, nt)
        }
        if (qts.isNotEmpty()) dao.insertQuestionTags(qts)

        val rvs = file.reviews.mapNotNull { r ->
            val nq = qMap[r.questionId] ?: return@mapNotNull null
            Review(nq, r.intervalStep, r.nextReviewAt, r.lastReviewedAt, r.reviewCount)
        }
        if (rvs.isNotEmpty()) dao.insertReviews(rvs)

        return ImportResult(questions = toInsert.size, categories = newCats, tags = newTags, skipped = skipped)
    }

    /** 清空：全部业务表 */
    suspend fun clearAll(dao: QuestionDao) {
        dao.clearAllQuestionTags()
        dao.clear()
        dao.clearCategories()
        dao.clearTags()
        dao.clearReviews()
    }
}
