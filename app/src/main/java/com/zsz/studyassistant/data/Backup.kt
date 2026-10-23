package com.zsz.studyassistant.data

import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Backup v3: permanent String problem IDs shared by notebook and search history.
 * Export uses a single DB snapshot. Import validates first, then merges by ID in
 * one transaction, preserving local content when an ID already exists.
 * Per the user's 2026-10-23 decision, v1/v2 backup files are explicitly rejected.
 */
@Serializable
data class BackupFile(
    @SerialName("format") val format: String = FORMAT,
    @SerialName("version") val version: Int = VERSION,
    @SerialName("exportedAt") val exportedAt: Long = System.currentTimeMillis(),
    @SerialName("categories") val categories: List<BackupCategory> = emptyList(),
    @SerialName("tags") val tags: List<BackupTag> = emptyList(),
    @SerialName("questions") val questions: List<BackupQuestion> = emptyList(),
    @SerialName("questionTags") val questionTags: List<BackupQuestionTag> = emptyList(),
    @SerialName("reviews") val reviews: List<BackupReview> = emptyList(),
    @SerialName("solveSessions") val solveSessions: List<BackupSolveSession> = emptyList(),
    @SerialName("stats") val stats: BackupStats = BackupStats()
) {
    companion object {
        const val FORMAT = "study-assistant-backup"

        /** Only this exact format is accepted; unsupported older backups are rejected. */
        const val VERSION = 3
    }
}

@Serializable
data class BackupCategory(val id: Long, val name: String, val createdAt: Long = 0)

@Serializable
data class BackupTag(val id: Long, val name: String, val createdAt: Long = 0)

@Serializable
data class BackupQuestion(
    val id: QuestionId,
    val text: String,
    val answer: String,
    val createdAt: Long = 0,
    /** v1 字段：主图 base64（v2 起仍写一份，供旧版 App 读取；导入时作为 `images` 的兜底） */
    val imageBase64: String? = null,
    val conversationJson: String? = null,
    val deleted: Boolean = false,
    val categoryId: Long? = null,
    /** v2 字段：**全部原图**（base64，0 = 主图） */
    val images: List<String> = emptyList()
)

@Serializable
data class BackupQuestionTag(val questionId: QuestionId, val tagId: Long)

@Serializable
data class BackupReview(
    val questionId: QuestionId,
    val intervalStep: Int = 0,
    val nextReviewAt: Long = 0,
    val lastReviewedAt: Long = 0,
    val reviewCount: Int = 0
)

/** v2 新增：搜题记录（含多图） */
@Serializable
data class BackupSolveSession(
    val id: QuestionId,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val source: String = "camera",
    val text: String = "",
    val answer: String = "",
    val conversationJson: String? = null,
    /** 主图（兼容 v1 的读法） */
    val imageBase64: String? = null,
    /** 全部图（base64 数组） */
    val images: List<String> = emptyList(),
    val savedQuestionId: QuestionId? = null
)

/** v2 新增：导出统计（人工核对 / 排查用） */
@Serializable
data class BackupStats(
    val questions: Int = 0,
    val images: Int = 0,
    val sessions: Int = 0,
    val categories: Int = 0,
    val tags: Int = 0
)

/** 导入结果统计（用于提示） */
data class ImportResult(
    val questions: Int,
    val categories: Int,
    val tags: Int,
    val skipped: Int,
    val sessions: Int = 0,
    val images: Int = 0
)

object BackupManager {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** A consistent snapshot; expensive encoding is never done on the UI thread. */
    suspend fun export(db: AppDatabase): String = withContext(Dispatchers.IO) {
        val dao = db.questionDao()
        val file = db.withTransaction {
            val cats = dao.allCategoriesOnce()
            val tags = dao.getAllTagsOnce()
            val qs = dao.allQuestionsOnce()
            val questionIds = qs.map { it.id }.toSet()
            fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
            val bqs = qs.map { q ->
                val keys = (dao.imagesForQuestion(q.id).sortedBy { it.position }.map { encode(it.bytes) } +
                    listOfNotNull(q.imageBytes?.let(::encode)) + questionImageBase64(q.conversationJson)).distinct()
                BackupQuestion(q.id, q.text, q.answer, q.createdAt, keys.firstOrNull(),
                    q.conversationJson, q.deleted, q.categoryId, keys)
            }
            val bs = dao.allSolveSessionsOnce().map { s ->
                val keys = if (s.imagesJson != null) json.decodeFromString<List<String>>(s.imagesJson)
                    else listOfNotNull(s.imageBytes?.let(::encode))
                BackupSolveSession(s.id, s.createdAt, s.updatedAt, s.source, s.text, s.answer,
                    s.conversationJson, keys.firstOrNull(), keys,
                    s.id.takeIf { it in questionIds })
            }
            BackupFile(
                categories = cats.map { BackupCategory(it.id, it.name, it.createdAt) },
                tags = tags.map { BackupTag(it.id, it.name, it.createdAt) },
                questions = bqs,
                questionTags = dao.allQuestionTagsOnce().map { BackupQuestionTag(it.questionId, it.tagId) },
                reviews = dao.allReviewsOnce().map { BackupReview(it.questionId,it.intervalStep,it.nextReviewAt,it.lastReviewedAt,it.reviewCount) },
                solveSessions = bs,
                stats = BackupStats(bqs.size, bqs.sumOf { it.images.size } + bs.sumOf { it.images.size }, bs.size, cats.size, tags.size)
            )
        }
        BackupValidation.validate(file)
        json.encodeToString(BackupFile.serializer(), file)
    }

    /** v3 only. Validate before writes, then apply all related data atomically. */
    suspend fun import(db: AppDatabase, text: String): ImportResult = withContext(Dispatchers.IO) {
        val file = BackupValidation.decode(text)
        TagMergeRepository.invalidateForImport(db) {
            db.withTransaction { BackupImporter.merge(db.questionDao(), file) }
        }
    }

    suspend fun importFile(db: AppDatabase, file: BackupFile): ImportResult = withContext(Dispatchers.IO) {
        TagMergeRepository.invalidateForImport(db) {
            db.withTransaction { BackupImporter.merge(db.questionDao(), file) }
        }
    }

    /** 清空：全部业务表（含一题多图与搜题记录） */
    suspend fun clearAll(db: AppDatabase) {
        val dao = db.questionDao()
        TagMergeRepository.invalidateForImport(db) {
            db.withTransaction {
                dao.clearAllQuestionTags()
                dao.clear()
                dao.clearCategories()
                dao.clearTags()
                dao.clearReviews()
                dao.clearSolveSessions()
                dao.purgeOrphanImages()
            }
        }
    }
}
