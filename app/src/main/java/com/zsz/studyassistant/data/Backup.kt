package com.zsz.studyassistant.data

import android.util.Base64
import androidx.room.withTransaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 数据备份 / 恢复。
 *
 * ## 格式版本
 *  - **v1**（`0.6.5` 及更早）：图片只有 `imageBase64`（**单张主图**），且**不含搜题记录**；
 *  - **v2**（`0.6.6` 起，本文件）：
 *    · `questions[].images` —— **一题多图**完整数组（来源 DB v9 的 `question_images`）；
 *    · `solveSessions` —— 搜题记录（含多图）；
 *    · `stats` —— 条目统计，便于人工核对；
 *    · 导入**事务化**（失败整体回滚），并**拒绝比当前应用更新的格式**。
 *
 * ## 兼容性（用户 2026-10-16 硬要求）
 *  - **v1 旧备份仍可导入**：`images` 为空时自动回落 `imageBase64` / `conversationJson`；
 *  - **v2 备份被旧版 App 打开**会因 `version` 被拒绝（提示升级），**不会写坏数据**；
 *  - 导出时 `imageBase64` 仍写一份（旧版 App 至少能读到主图）。
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

        /** 当前导出格式版本；**导入时接受 <= 此值**（旧备份可导入） */
        const val VERSION = 2
    }
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
    /** v1 字段：主图 base64（v2 起仍写一份，供旧版 App 读取；导入时作为 `images` 的兜底） */
    val imageBase64: String? = null,
    val conversationJson: String? = null,
    val deleted: Boolean = false,
    val categoryId: Long? = null,
    /** v2 字段：**全部原图**（base64，0 = 主图） */
    val images: List<String> = emptyList()
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

/** v2 新增：搜题记录（含多图） */
@Serializable
data class BackupSolveSession(
    val id: Long,
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
    val savedQuestionId: Long? = null
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

    /** 导出：把整库（含一题多图 + 搜题记录）读成 JSON 文本 */
    suspend fun export(dao: QuestionDao): String {
        val cats = dao.allCategoriesOnce()
        val tags = dao.getAllTagsOnce()
        val qs = dao.allQuestionsOnce()
        val qTags = dao.allQuestionTagsOnce()
        val reviews = dao.allReviewsOnce()
        val sessions = dao.allSolveSessionsOnce()

        var imageCount = 0
        val bqs = qs.map { q ->
            // 优先 DB v9 新表（一题多图）；没有则回落旧列/会话 —— 老数据也完整带得走
            var imgs = dao.imagesForQuestion(q.id).sortedBy { it.position }.take(MAX_QUESTION_IMAGES)
                .map { Base64.encodeToString(it.bytes, Base64.NO_WRAP) }
            if (imgs.isEmpty()) {
                imgs = mergeImageKeys(
                    q.imageBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                    questionImageBase64(q.conversationJson)
                )
            }
            imageCount += imgs.size
            BackupQuestion(
                id = q.id,
                text = q.text,
                answer = q.answer,
                createdAt = q.createdAt,
                imageBase64 = imgs.firstOrNull(),     // v1 兼容字段
                conversationJson = q.conversationJson,
                deleted = q.deleted,
                categoryId = q.categoryId,
                images = imgs
            )
        }

        val bs = sessions.map { s ->
            val imgs = runCatching { s.imagesJson?.let { j -> json.decodeFromString<List<String>>(j) } }
                .getOrNull().orEmpty()
                .ifEmpty { s.imageBytes?.let { listOf(Base64.encodeToString(it, Base64.NO_WRAP)) } ?: emptyList() }
            imageCount += imgs.size
            BackupSolveSession(
                id = s.id,
                createdAt = s.createdAt,
                updatedAt = s.updatedAt,
                source = s.source,
                text = s.text,
                answer = s.answer,
                conversationJson = s.conversationJson,
                imageBase64 = imgs.firstOrNull(),
                images = imgs,
                savedQuestionId = s.savedQuestionId
            )
        }

        val file = BackupFile(
            categories = cats.map { BackupCategory(it.id, it.name, it.createdAt) },
            tags = tags.map { BackupTag(it.id, it.name, it.createdAt) },
            questions = bqs,
            questionTags = qTags.map { BackupQuestionTag(it.questionId, it.tagId) },
            reviews = reviews.map { BackupReview(it.questionId, it.intervalStep, it.nextReviewAt, it.lastReviewedAt, it.reviewCount) },
            solveSessions = bs,
            stats = BackupStats(
                questions = bqs.size,
                images = imageCount,
                sessions = bs.size,
                categories = cats.size,
                tags = tags.size
            )
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /**
     * 导入：合并式恢复（不覆盖现有数据），**整个导入在一个事务里** —— 失败会整体回滚，不留半份数据。
     *
     * 兼容 v1（只有 `imageBase64`）与 v2（`images` 数组 + `solveSessions`）；
     * `version` 比当前应用新时直接拒绝，避免旧版 App 把数据写坏。
     */
    suspend fun import(db: AppDatabase, text: String): ImportResult {
        val dao = db.questionDao()
        val file = json.decodeFromString(BackupFile.serializer(), text)
        require(file.format == BackupFile.FORMAT) { "不是本应用的备份文件" }
        require(file.version <= BackupFile.VERSION) {
            "备份格式版本 ${file.version} 比当前应用新，请先升级 App 再导入"
        }

        var result = ImportResult(0, 0, 0, 0, 0, 0)
        db.withTransaction {
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

            // 错题：题干相同则跳过（含图：v2 用 images，v1 回落 imageBase64/会话）
            val existingTexts = dao.allQuestionsOnce().map { it.text }.toHashSet()
            val qMap = HashMap<Long, Long>()
            val toInsert = mutableListOf<Question>()
            val pendingOldIds = mutableListOf<Long>()
            val pendingImages = mutableListOf<List<ByteArray>>()
            var skipped = 0
            var imageCount = 0
            for (q in file.questions) {
                if (existingTexts.contains(q.text)) { skipped++; continue }
                val imgs = runCatching {
                    (if (q.images.isNotEmpty()) q.images else listOfNotNull(q.imageBase64))
                        .take(MAX_QUESTION_IMAGES)
                        .map { Base64.decode(it, Base64.NO_WRAP) }
                }.getOrNull().orEmpty()
                imageCount += imgs.size
                toInsert += Question(
                    text = q.text,
                    answer = q.answer,
                    createdAt = q.createdAt,
                    imageBytes = imgs.firstOrNull(),
                    conversationJson = q.conversationJson,
                    deleted = q.deleted,
                    categoryId = q.categoryId?.let { catMap[it] }
                )
                pendingOldIds += q.id
                pendingImages += imgs
            }
            if (toInsert.isNotEmpty()) {
                val newIds = dao.insertQuestions(toInsert)
                newIds.forEachIndexed { i, nid ->
                    qMap[pendingOldIds[i]] = nid
                    val imgs = pendingImages[i]
                    if (imgs.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        dao.insertImages(
                            imgs.mapIndexed { pos, b ->
                                QuestionImage(questionId = nid, position = pos, bytes = b, source = "import", createdAt = now)
                            }
                        )
                    }
                }
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

            // 搜题记录（v2）：整表追加，`savedQuestionId` 按新旧 id 映射
            var sessionCount = 0
            if (file.solveSessions.isNotEmpty()) {
                val rows = file.solveSessions.map { s ->
                    val imgs = runCatching {
                        (if (s.images.isNotEmpty()) s.images else listOfNotNull(s.imageBase64))
                            .take(MAX_QUESTION_IMAGES)
                    }.getOrNull().orEmpty()
                    imageCount += imgs.size
                    SolveSession(
                        createdAt = s.createdAt,
                        updatedAt = s.updatedAt,
                        source = s.source,
                        text = s.text,
                        answer = s.answer,
                        conversationJson = s.conversationJson,
                        imageBytes = imgs.firstOrNull()?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() },
                        imagesJson = if (imgs.size > 1) json.encodeToString<List<String>>(imgs) else null,
                        savedQuestionId = s.savedQuestionId?.let { qMap[it] }
                    )
                }
                dao.insertSolveSessions(rows)
                sessionCount = rows.size
            }

            result = ImportResult(
                questions = toInsert.size,
                categories = newCats,
                tags = newTags,
                skipped = skipped,
                sessions = sessionCount,
                images = imageCount
            )
        }
        return result
    }

    /** 清空：全部业务表（含一题多图与搜题记录） */
    suspend fun clearAll(db: AppDatabase) {
        val dao = db.questionDao()
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
