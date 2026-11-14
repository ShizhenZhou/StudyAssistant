package com.zsz.studyassistant.data

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Update
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// Derived only on writes/migration. List queries read the small title column directly.
private const val SOLVE_SESSION_TITLE_SQL = "CASE WHEN typeof(conversationJson) = 'text' THEN " +
    "CASE WHEN json_valid(conversationJson) THEN " +
    "CASE WHEN json_type(conversationJson, '$[0].conversationTitle') = 'text' THEN " +
    "substr(json_extract(conversationJson, '$[0].conversationTitle'),1,256) END END END"

/** Covers DAO writes and raw SQL backup restores without changing Bak v4's row format. */
private fun installSolveSessionTitleTriggers(connection: SQLiteConnection) {
    for ((suffix, event) in listOf("insert" to "INSERT", "update" to "UPDATE OF conversationJson")) {
        connection.execSQL("CREATE TRIGGER IF NOT EXISTS solve_sessions_title_$suffix AFTER $event ON solve_sessions " +
            "BEGIN UPDATE solve_sessions SET title = $SOLVE_SESSION_TITLE_SQL WHERE id = NEW.id; END")
    }
}

val SOLVE_SESSION_TITLE_CALLBACK = object : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) = installSolveSessionTitleTriggers(connection)
    override suspend fun onOpen(connection: SQLiteConnection) = installSolveSessionTitleTriggers(connection)
}

@Entity(tableName = "questions")
data class Question(
    @PrimaryKey val id: QuestionId,
    val text: String,
    val answer: String,
    val createdAt: Long = System.currentTimeMillis(),
    // 框选出的错题图片（JPEG 字节，可空；纯文字题为 null）
    val imageBytes: ByteArray? = null,
    // 完整对话会话（chatItems 的 JSON，用于续答）
    val conversationJson: String? = null,
    // 是否已软删除（可从错题本恢复）
    val deleted: Boolean = false,
    // 所属分类（categories.id；null = 未分类）
    val categoryId: Long? = null
)

/** 用户自定义的错题分类（如：高等数学、线性代数…），单选一个 */
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** 知识点标签（独立于科目分类，全局不分科），一道题可多个 */
@Serializable
@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** 错题 ↔ 知识点标签 的多对多关联表 */
@Serializable
@Entity(tableName = "question_tags", primaryKeys = ["questionId", "tagId"])
data class QuestionTag(
    val questionId: QuestionId,
    val tagId: Long
)

/** 复习调度（艾宾浩斯）；questionId 为主键，一题一条 */
@Entity(tableName = "review")
data class Review(
    @PrimaryKey val questionId: QuestionId,
    // 当前间隔档位（0..6，对应 intervalDays）
    val intervalStep: Int = 0,
    // 下次复习时间戳
    val nextReviewAt: Long = System.currentTimeMillis(),
    val lastReviewedAt: Long = 0,
    val reviewCount: Int = 0
)

/** Search-only projection; never use these potentially truncated fields for content saves. */
data class QuestionSearchRow(val id: QuestionId, val text: String, val answer: String)

@Dao
interface QuestionDao {
    @Query("UPDATE questions SET text = :text, answer = :answer, conversationJson = :conversation WHERE id = :id AND deleted = 0")
    suspend fun updateAnswerContent(id: QuestionId, text: String, answer: String, conversation: String?)

    @Query("SELECT id,substr(text,1,256) AS text,substr(answer,1,256) AS answer,createdAt,deleted,categoryId FROM questions WHERE deleted = 0 ORDER BY createdAt DESC")
    fun getAll(): Flow<List<QuestionPreview>>

    @Query("SELECT id,createdAt,categoryId FROM questions WHERE deleted = 0 ORDER BY createdAt DESC")
    fun questionStats(): Flow<List<QuestionStatsRow>>

    /** Room reruns this tiny query on table invalidation, including edits with unchanged count.
     * Do not distinct the Boolean: it is a change signal, not the search input.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM questions LIMIT 1)")
    fun questionSearchChanges(): Flow<Boolean>

    @Query("SELECT * FROM categories ORDER BY createdAt ASC")
    fun categories(): Flow<List<Category>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun tags(): Flow<List<Tag>>

    @Query("SELECT * FROM tags")
    suspend fun getAllTagsOnce(): List<Tag>

    @Query("SELECT tagId FROM question_tags WHERE questionId = :questionId")
    suspend fun tagIdsForQuestion(questionId: QuestionId): List<Long>

    @Query("SELECT * FROM question_tags")
    fun allQuestionTags(): Flow<List<QuestionTag>>

    @Insert
    suspend fun insert(q: Question): Long

    @androidx.room3.Transaction
    suspend fun insertQuestionWithId(q: Question): QuestionId {
        insert(q)
        return q.id
    }

    @Insert
    suspend fun insertCategory(c: Category): Long

    @Insert
    suspend fun insertTag(t: Tag): Long

    @Insert
    suspend fun insertQuestionTagRaw(qt: QuestionTag)

    @Query("SELECT COUNT(*) FROM tags WHERE id IN (:ids)")
    suspend fun existingTagCount(ids: List<Long>): Int

    @androidx.room3.Transaction
    suspend fun insertQuestionTag(qt: QuestionTag) {
        if (existingTagCount(listOf(qt.tagId)) != 1) throw TagMergeException("tagMerge.changed")
        insertQuestionTagRaw(qt)
    }

    @Query("DELETE FROM question_tags WHERE questionId = :questionId AND tagId = :tagId")
    suspend fun deleteQuestionTag(questionId: QuestionId, tagId: Long)

    @Query("DELETE FROM question_tags WHERE questionId = :questionId")
    suspend fun clearQuestionTags(questionId: QuestionId)

    @Query("SELECT COUNT(*) FROM questions q INNER JOIN review r ON q.id=r.questionId WHERE q.deleted=0 AND r.nextReviewAt<=:until")
    suspend fun dueCount(until: Long): Int

    // ---- 复习调度 ----
    /** 到期错题（nextReviewAt <= until），按下次复习时间升序 */
    // ★ 只取轻字段：不要 SELECT q.*（含 imageBytes 大 BLOB 与会话 JSON），
//   否则复习队列题一多，结果集超过 CursorWindow 的 2MB 上限会直接崩：
//   IllegalStateException: Couldn't read row N, col 0 from CursorWindow
//   图片/会话在真正进入某题时用 heavyOnce(id) 单独取。
@Query("SELECT DISTINCT q.id, substr(q.text,1,256) AS text, substr(q.answer,1,256) AS answer, q.createdAt, q.deleted, q.categoryId FROM questions q INNER JOIN review r ON q.id = r.questionId WHERE q.deleted = 0 AND r.nextReviewAt <= :until AND (:onlyWeak = 0 OR r.intervalStep <= 1) ORDER BY r.nextReviewAt ASC")
    fun dueQuestions(until: Long, onlyWeak: Boolean = false): Flow<List<QuestionPreview>>

    /** 按 id 取完整一行（含图片 BLOB 与会话 JSON）——列表查询不用它，避免撑爆 CursorWindow */
    @androidx.room3.Transaction
    suspend fun heavyOnce(id: QuestionId): Question? {
        val row = questionLightOnce(id) ?: return null
        return row.copy(imageBytes = questionImageOnce(id), conversationJson =
            readChunkedText(questionConversationSize(id)) { offset, count -> questionConversationPart(id, offset, count) })
    }
    @Query("SELECT LENGTH(imageBytes) FROM questions WHERE id=:id")
    suspend fun questionImageSize(id: QuestionId): Int?
    @Query("SELECT substr(imageBytes,:offset,:count) FROM questions WHERE id=:id")
    suspend fun questionImagePart(id: QuestionId, offset: Int, count: Int): ByteArray?
    @androidx.room3.Transaction
    suspend fun questionImageOnce(id: QuestionId): ByteArray? =
        readChunkedBlob(questionImageSize(id)) { a,b -> questionImagePart(id,a,b) }
    @Query("SELECT LENGTH(conversationJson) FROM questions WHERE id=:id")
    suspend fun questionConversationSize(id: QuestionId): Int?
    @Query("SELECT substr(conversationJson,:offset,:count) FROM questions WHERE id=:id")
    suspend fun questionConversationPart(id: QuestionId, offset: Int, count: Int): String?

    @Query("SELECT id,substr(text,1,256) AS text,substr(answer,1,256) AS answer,createdAt,deleted,categoryId FROM questions WHERE id=:id")
    suspend fun questionPreviewOnce(id: QuestionId): QuestionPreview?
    @Query("SELECT LENGTH(CASE :field WHEN 'text' THEN text WHEN 'answer' THEN answer END) FROM questions WHERE id=:id")
    suspend fun questionTextSize(id: QuestionId, field: String): Int?
    @Query("SELECT substr(CASE :field WHEN 'text' THEN text WHEN 'answer' THEN answer END,:offset,:count) FROM questions WHERE id=:id")
    suspend fun questionTextPart(id: QuestionId, field: String, offset: Int, count: Int): String?
    /** Full text, no images: duplicate checks and content saves must never use list previews. */
    @androidx.room3.Transaction
    suspend fun questionLightOnce(id: QuestionId): Question? {
        val row=questionPreviewOnce(id) ?: return null
        return Question(id = row.id, createdAt = row.createdAt, deleted = row.deleted, categoryId = row.categoryId,
            text=checkNotNull(readChunkedText(questionTextSize(id,"text")) { a,b -> questionTextPart(id,"text",a,b) }),
            answer=checkNotNull(readChunkedText(questionTextSize(id,"answer")) { a,b -> questionTextPart(id,"answer",a,b) }))
    }

    @Query("SELECT * FROM review WHERE questionId = :questionId")
    suspend fun reviewFor(questionId: QuestionId): Review?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReview(r: Review)

    @Query("UPDATE review SET intervalStep = :step, nextReviewAt = :next, lastReviewedAt = :last, reviewCount = reviewCount + 1 WHERE questionId = :questionId")
    suspend fun updateReview(questionId: QuestionId, step: Int, next: Long, last: Long)

    @Update
    suspend fun update(q: Question)

    @Delete
    suspend fun delete(q: Question)

    @Query("DELETE FROM questions WHERE categoryId = :categoryId")
    suspend fun deleteByCategory(categoryId: Long)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun deleteById(id: QuestionId)

    /** 物理清除软删除的题（App 启动时定期清理，避免数据库无限增长） */
    @Query("DELETE FROM questions WHERE deleted = 1")
    suspend fun purgeDeletedQuestions(): Int

    /** 清除孤儿复习记录（题目已被物理删除） */
    @Query("DELETE FROM review WHERE questionId NOT IN (SELECT id FROM questions)")
    suspend fun purgeOrphanReviews(): Int

    /** 清除孤儿标签关联（题目已被物理删除） */
    @Query("DELETE FROM question_tags WHERE questionId NOT IN (SELECT id FROM questions)")
    suspend fun purgeOrphanQuestionTags(): Int

    // 批量：改分类（cid = null → 暂不分类），支持空列表直接返回
    @Query("UPDATE questions SET categoryId = :cid WHERE id IN (:ids)")
    suspend fun setCategoryForIds(ids: List<QuestionId>, cid: Long?)

    // 批量：彻底删除
    @Query("DELETE FROM questions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<QuestionId>)

    @Query("UPDATE questions SET deleted = 1 WHERE id = :id")
    suspend fun softDelete(id: QuestionId)

    @Query("UPDATE questions SET deleted = 0 WHERE id = :id")
    suspend fun restore(id: QuestionId)

    @Query("DELETE FROM questions")
    suspend fun clear()

    // ---- 备份 / 恢复 / 数据管理 ----
    @Query("SELECT id FROM questions ORDER BY id ASC")
    suspend fun allQuestionIdsOnce(): List<QuestionId>
    @androidx.room3.Transaction
    suspend fun allQuestionsOnce(): List<Question> = allQuestionIdsOnce().mapNotNull { heavyOnce(it) }

    @Query("SELECT COALESCE(SUM(LENGTH(text)+LENGTH(answer)+COALESCE(LENGTH(conversationJson),0)+COALESCE(LENGTH(imageBytes),0)),0) FROM questions")
    suspend fun questionBackupSize(): Long
    @Query("SELECT COALESCE(SUM(LENGTH(text)+LENGTH(answer)+COALESCE(LENGTH(conversationJson),0)+COALESCE(LENGTH(imageBytes),0)+COALESCE(LENGTH(imagesJson),0)),0) FROM solve_sessions")
    suspend fun historyBackupSize(): Long
    @Query("SELECT COALESCE(SUM(LENGTH(bytes)),0) FROM question_images")
    suspend fun imageBackupSize(): Long

    /** 轻查询（不含图片 BLOB / 会话 JSON）：查重（B9）等只要题干的场景用，避免大字段进出 */
    @Query("SELECT id FROM questions WHERE deleted = 0")
    suspend fun activeQuestionIdsOnce(): List<QuestionId>
    @androidx.room3.Transaction
    suspend fun allLightOnce(): List<Question> = activeQuestionIdsOnce().mapNotNull { questionLightOnce(it) }

    @Query("SELECT id, substr(text,1,:fieldLimit) AS text, substr(answer,1,:fieldLimit) AS answer FROM questions WHERE deleted=0 AND id>:afterId ORDER BY id LIMIT :pageSize")
    suspend fun questionSearchPage(afterId: QuestionId, fieldLimit: Int, pageSize: Int): List<QuestionSearchRow>

    @Query("SELECT EXISTS(SELECT 1 FROM questions WHERE id=:id AND deleted=0)")
    suspend fun activeQuestionExists(id: QuestionId): Boolean

    /** Long fields share one fresh per-question snapshot, including deletion and text-before-answer priority. */
    @androidx.room3.Transaction
    suspend fun matchingQuestionSnippet(id: QuestionId, query: String): SearchSnippet? {
        currentCoroutineContext().ensureActive()
        if (!activeQuestionExists(id)) return null
        return searchChunks(query, answer = false) { offset, count -> questionTextPart(id, "text", offset, count) }
            ?: searchChunks(query, answer = true) { offset, count -> questionTextPart(id, "answer", offset, count) }
    }

    /**
     * Short fields use bounded pages rather than two queries per question. Only long questions
     * hold a transaction; each result is internally consistent, while table invalidation restarts
     * the search if edits occur between pages. No images, conversations or whole long fields load.
     */
    suspend fun matchingQuestionSnippets(query: String): Map<QuestionId, SearchSnippet> {
        if (query.isBlank()) return emptyMap()
        return buildMap {
            val pageSize = 32
            // Permanent question IDs are nonempty ASCII UUID/time strings; use the primary-key order.
            var afterId = ""
            while (true) {
                currentCoroutineContext().ensureActive()
                val rows = questionSearchPage(afterId, SEARCH_PROJECTION_CHARS + 1, pageSize)
                currentCoroutineContext().ensureActive()
                for (row in rows) {
                    val hit = searchQuestionProjection(row.text, row.answer, query) {
                        matchingQuestionSnippet(row.id, query)
                    }
                    if (hit != null) put(row.id, hit)
                }
                if (rows.size < pageSize) break
                afterId = rows.last().id
            }
        }
    }

    suspend fun matchingQuestionIds(query: String): Set<QuestionId> =
        if (query.isBlank()) activeQuestionIdsOnce().toSet() else matchingQuestionSnippets(query).keys

    /**
     * 「原图回填」用（2026-10-14）：找出 `imageBytes` 为空、但会话 JSON 还在的错题。
     * 只取必要列（**不含 BLOB**），避免把大图整批读进内存。
     * ⚠️ `createdAt`/`deleted` 是**非空基元**，Room 要求它们必须出现在投影里（Kotlin 默认值不算数）。
     */
    @Query("SELECT id FROM questions WHERE deleted = 0 AND imageBytes IS NULL AND conversationJson IS NOT NULL")
    suspend fun questionIdsMissingImageOnce(): List<QuestionId>
    @androidx.room3.Transaction
    suspend fun questionsMissingImageOnce(): List<Question> = questionIdsMissingImageOnce().mapNotNull { heavyOnce(it) }

    /** 「原图回填」用：只写回主图，不动其它字段（避免整行 UPDATE 覆盖并发修改） */
    @Query("UPDATE questions SET imageBytes = :img WHERE id = :id")
    suspend fun setQuestionImage(id: QuestionId, img: ByteArray)

    /** 维护候选只读取轻字段；调用方逐题 heavyOnce 分段读取会话，避免批量 CursorWindow 溢出。 */
    @Query("SELECT id, substr(text,1,256) AS text, substr(answer,1,256) AS answer, createdAt, deleted, categoryId FROM questions WHERE deleted = 0")
    suspend fun questionsWithConversationOnce(): List<QuestionPreview>

    /** 「多图修复」用：把补好的会话 JSON 写回（只动这一列） */
    @Query("UPDATE questions SET conversationJson = :cj WHERE id = :id")
    suspend fun setConversationJson(id: QuestionId, cj: String)

    /** 「多图修复」用：按"已存错题 id"找到对应搜题记录（记录里 `imagesJson` 有全部图） */
    @androidx.room3.Transaction
    suspend fun solveSessionForQuestion(qid: QuestionId): SolveSession? =
        solveSessionOnce(qid)?.takeIf { it.savedQuestionId == qid }

    /** 「多图修复」用（兜底）：按题干文本匹配搜题记录 */
    @Query("SELECT id FROM solve_sessions WHERE text = :text AND imagesJson IS NOT NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun sessionIdByText(text: String): QuestionId?
    @androidx.room3.Transaction
    suspend fun solveSessionByText(text: String): SolveSession? = sessionIdByText(text)?.let { solveSessionOnce(it) }

    // ── DB v9：一题多图（`question_images` 表，0=主图）────────────────────────

    /** 读某题的全部原图（按 position 升序，0 = 主图） */
    @Query("SELECT id,questionId,position,X'' AS bytes,source,createdAt FROM question_images WHERE questionId = :qid ORDER BY position ASC")
    suspend fun imageHeadersForQuestion(qid: QuestionId): List<QuestionImage>
    @Query("SELECT LENGTH(bytes) FROM question_images WHERE id=:id")
    suspend fun imageSize(id: Long): Int?
    @Query("SELECT substr(bytes,:offset,:count) FROM question_images WHERE id=:id")
    suspend fun imagePart(id: Long, offset: Int, count: Int): ByteArray?
    @androidx.room3.Transaction
    suspend fun imagesForQuestion(qid: QuestionId): List<QuestionImage> = imageHeadersForQuestion(qid).map {
        it.copy(bytes=checkNotNull(readChunkedBlob(imageSize(it.id)) { a,b -> imagePart(it.id,a,b) }))
    }

    /** 批量读"主图"（列表页兜底；只取 position=0，避免把全部大图读进内存） */
    @Query("SELECT id,questionId,position,X'' AS bytes,source,createdAt FROM question_images WHERE position = 0 AND questionId IN (:qids)")
    suspend fun mainImageHeadersFor(qids: List<QuestionId>): List<QuestionImage>
    @androidx.room3.Transaction
    suspend fun mainImagesFor(qids: List<QuestionId>): List<QuestionImage> = mainImageHeadersFor(qids).map {
        it.copy(bytes=checkNotNull(readChunkedBlob(imageSize(it.id)) { a,b -> imagePart(it.id,a,b) }))
    }

    @Insert
    suspend fun insertImages(list: List<QuestionImage>): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM questions WHERE id = :qid)")
    suspend fun questionExists(qid: QuestionId): Boolean

    /** 同一事务内检查并追加，不清除、不重排已有图片。 */
    @androidx.room3.Transaction
    suspend fun appendMissingImages(qid: QuestionId, candidates: List<ByteArray>, source: String): Int {
        if (!questionExists(qid)) return 0
        val existing = imagesForQuestion(qid)
        val missing = additionalQuestionImages(existing.map { it.bytes }, candidates)
        if (missing.isEmpty()) return 0
        val firstPosition = (existing.maxOfOrNull { it.position } ?: -1) + 1
        val now = System.currentTimeMillis()
        insertImages(missing.mapIndexed { index, bytes ->
            QuestionImage(questionId = qid, position = firstPosition + index,
                bytes = bytes, source = source, createdAt = now)
        })
        return missing.size
    }

    @Query("DELETE FROM question_images WHERE questionId = :qid")
    suspend fun clearImages(qid: QuestionId)

    @Query("DELETE FROM question_images WHERE questionId IN (:qids)")
    suspend fun clearImagesFor(qids: List<QuestionId>)

    /** 清理孤儿图片（题已被彻底删除） */
    @Query("DELETE FROM question_images WHERE questionId NOT IN (SELECT id FROM questions)")
    suspend fun purgeOrphanImages()

    /** 「是否需要升级数据」判定用：已有原图进新表的题 id（一次查询，避免 N 次） */
    @Query("SELECT DISTINCT questionId FROM question_images")
    suspend fun questionIdsWithImages(): List<QuestionId>

    // ── 备份 v2：搜题记录整表搬 ────────────────────────────────────────────

    @Query("SELECT id FROM solve_sessions ORDER BY createdAt ASC")
    suspend fun allSessionIdsOnce(): List<QuestionId>
    @androidx.room3.Transaction
    suspend fun allSolveSessionsOnce(): List<SolveSession> = allSessionIdsOnce().mapNotNull { solveSessionOnce(it) }

    @Insert
    suspend fun insertSolveSessions(list: List<SolveSession>): List<Long>

    @Query("DELETE FROM solve_sessions")
    suspend fun clearSolveSessions()

    @Query("SELECT * FROM categories ORDER BY id ASC")
    suspend fun allCategoriesOnce(): List<Category>

    @Query("SELECT * FROM question_tags")
    suspend fun allQuestionTagsOnce(): List<QuestionTag>

    @Query("SELECT * FROM review")
    suspend fun allReviewsOnce(): List<Review>

    /** 全部复习记录的实时流（学习统计页用） */
    @Query("SELECT * FROM review")
    fun allReviews(): Flow<List<Review>>

    @Query("SELECT COUNT(*) FROM questions WHERE deleted = 0")
    suspend fun countQuestions(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(list: List<Question>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(list: List<Category>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(list: List<Tag>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuestionTagsRaw(list: List<QuestionTag>)

    @androidx.room3.Transaction
    suspend fun insertQuestionTags(list: List<QuestionTag>) {
        val ids = list.map { it.tagId }.distinct()
        if (ids.isNotEmpty() && existingTagCount(ids) != ids.size) throw TagMergeException("tagMerge.changed")
        insertQuestionTagsRaw(list)
    }

    @Query("DELETE FROM question_tags WHERE tagId IN (:ids)")
    suspend fun deleteLinksForTags(ids: List<Long>)

    @Query("DELETE FROM tags WHERE id IN (:ids)")
    suspend fun deleteTagsByIds(ids: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReviews(list: List<Review>)

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Query("DELETE FROM tags")
    suspend fun clearTags()

    @Query("DELETE FROM question_tags")
    suspend fun clearAllQuestionTags()

    @Query("DELETE FROM review")
    suspend fun clearReviews()

    // ---- 科目（分类）管理 ----
    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun renameCategory(id: Long, name: String)

    /** 不删题：把该科目下的错题改为「未分类」 */
    @Query("UPDATE questions SET categoryId = NULL WHERE categoryId IN (:ids)")
    suspend fun clearCategoryForCategories(ids: List<Long>)

    @Query("SELECT id FROM questions WHERE categoryId IN (:ids)")
    suspend fun questionIdsInCategories(ids: List<Long>): List<QuestionId>

    /** 删题：连同这些科目的错题一起删（调用方需先清关联与复习记录） */
    @Query("DELETE FROM questions WHERE categoryId IN (:ids)")
    suspend fun deleteQuestionsInCategories(ids: List<Long>)

    @Query("DELETE FROM question_tags WHERE questionId IN (:qids)")
    suspend fun deleteQuestionTagsForQuestions(qids: List<QuestionId>)

    @Query("DELETE FROM review WHERE questionId IN (:qids)")
    suspend fun deleteReviewsForQuestions(qids: List<QuestionId>)

    @Query("DELETE FROM categories WHERE id IN (:ids)")
    suspend fun deleteCategories(ids: List<Long>)

    // ---- 搜题记录（solve_sessions，方案 B：与 questions 完全隔离）----

    @Insert
    suspend fun insertSolveSession(s: SolveSession): Long

    /** 按 id 更新整条记录（追问/继续生成时把最新 text/answer/conversationJson 写回同一条） */
    @Update
    suspend fun updateSolveSession(s: SolveSession)

    /**
     * ★ 列表用的**轻查询**：**绝不能 `SELECT *`** —— 本表同样有 `imageBytes`(BLOB)
     *   与 `conversationJson`（`ChatItem.images` 是 base64），条数一多结果集就会超过
     *   CursorWindow 的 2MB 上限并崩：IllegalStateException: Couldn't read row N, col 0 from CursorWindow
     *   （与 `dueQuestions` / `allLightOnce` 同一个坑）。
     *   重字段（原图 / 会话 JSON）在真正进入某条记录时用 `solveSessionOnce(id)` 单独取。
     */
    @Query("SELECT id, createdAt, updatedAt, source, substr(text,1,256) AS text, substr(answer,1,256) AS answer, savedQuestionId, title FROM solve_sessions ORDER BY createdAt DESC")
    fun solveSessionsLight(): Flow<List<SolveSessionPreview>>

    /** 按 id 取**重字段**（含原图 BLOB 与会话 JSON）——列表查询不用它，避免撑爆 CursorWindow */
    @Query("SELECT id,createdAt,updatedAt,source,substr(text,1,256) AS text,substr(answer,1,256) AS answer,savedQuestionId,title FROM solve_sessions WHERE id=:id")
    suspend fun sessionLightOnce(id: QuestionId): SolveSessionPreview?
    @Query("SELECT LENGTH(CASE :field WHEN 'text' THEN text WHEN 'answer' THEN answer END) FROM solve_sessions WHERE id=:id")
    suspend fun sessionTextSize(id: QuestionId, field: String): Int?
    @Query("SELECT substr(CASE :field WHEN 'text' THEN text WHEN 'answer' THEN answer END,:offset,:count) FROM solve_sessions WHERE id=:id")
    suspend fun sessionTextPart(id: QuestionId, field: String, offset: Int, count: Int): String?
    @Query("SELECT LENGTH(imageBytes) FROM solve_sessions WHERE id=:id")
    suspend fun sessionImageSize(id: QuestionId): Int?
    @Query("SELECT substr(imageBytes,:offset,:count) FROM solve_sessions WHERE id=:id")
    suspend fun sessionImagePart(id: QuestionId, offset: Int, count: Int): ByteArray?
    @androidx.room3.Transaction
    suspend fun sessionImageOnce(id: QuestionId): ByteArray? =
        readChunkedBlob(sessionImageSize(id)) { a,b -> sessionImagePart(id,a,b) }
    @Query("SELECT LENGTH(conversationJson) FROM solve_sessions WHERE id=:id")
    suspend fun sessionConversationSize(id: QuestionId): Int?
    @Query("SELECT substr(conversationJson,:offset,:count) FROM solve_sessions WHERE id=:id")
    suspend fun sessionConversationPart(id: QuestionId, offset: Int, count: Int): String?
    @Query("SELECT LENGTH(imagesJson) FROM solve_sessions WHERE id=:id")
    suspend fun sessionImagesSize(id: QuestionId): Int?
    @Query("SELECT substr(imagesJson,:offset,:count) FROM solve_sessions WHERE id=:id")
    suspend fun sessionImagesPart(id: QuestionId, offset: Int, count: Int): String?
    @androidx.room3.Transaction
    suspend fun solveSessionOnce(id: QuestionId): SolveSession? {
        val row = sessionLightOnce(id) ?: return null
        return SolveSession(id = row.id, createdAt = row.createdAt, updatedAt = row.updatedAt, source = row.source,
            savedQuestionId = row.savedQuestionId, title = row.title, imageBytes = sessionImageOnce(id),
            text=checkNotNull(readChunkedText(sessionTextSize(id,"text")) { a,b -> sessionTextPart(id,"text",a,b) }),
            answer=checkNotNull(readChunkedText(sessionTextSize(id,"answer")) { a,b -> sessionTextPart(id,"answer",a,b) }),
            conversationJson = readChunkedText(sessionConversationSize(id)) { a,b -> sessionConversationPart(id,a,b) },
            imagesJson = readChunkedText(sessionImagesSize(id)) { a,b -> sessionImagesPart(id,a,b) })
    }

    @Query("UPDATE solve_sessions SET savedQuestionId = NULL WHERE id = :id")
    suspend fun unlinkExistingSession(id: QuestionId)

    @Query("UPDATE solve_sessions SET savedQuestionId = :id WHERE id = :id AND EXISTS(SELECT 1 FROM questions WHERE questions.id = :id)")
    suspend fun linkExistingSession(id: QuestionId)

    @Query("SELECT COUNT(*) FROM solve_sessions")
    suspend fun countSolveSessions(): Int

    /** 全部记录的轻字段（裁剪策略用：只判 id 与 createdAt） */
    @Query("SELECT id, createdAt, updatedAt, source, substr(text,1,256) AS text, substr(answer,1,256) AS answer, savedQuestionId, title FROM solve_sessions")
    suspend fun solveSessionsLightOnce(): List<SolveSessionPreview>

    @Query("DELETE FROM solve_sessions WHERE id IN (:ids)")
    suspend fun deleteSolveSessionsByIds(ids: List<QuestionId>)

}

/** 数据库 1 -> 2：给 questions 表加 imageBytes 列，保留现有错题 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(db: SQLiteConnection) {
        db.execSQL("ALTER TABLE questions ADD COLUMN imageBytes BLOB")
    }
}

/** 数据库 2 -> 3：加 conversationJson 列（对话会话） */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(db: SQLiteConnection) {
        db.execSQL("ALTER TABLE questions ADD COLUMN conversationJson TEXT")
    }
}

/** 数据库 3 -> 4：加 deleted 列（软删除/恢复） */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(db: SQLiteConnection) {
        db.execSQL("ALTER TABLE questions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
    }
}

/** 数据库 4 -> 5：加 categories 表 + questions.categoryId 列（错题分类） */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override suspend fun migrate(db: SQLiteConnection) {
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE questions ADD COLUMN categoryId INTEGER")
    }
}

/** 数据库 5 -> 6：加 tags 表 + question_tags 关联表（知识点 tag，多对多） */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override suspend fun migrate(db: SQLiteConnection) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS question_tags (questionId INTEGER NOT NULL, tagId INTEGER NOT NULL, PRIMARY KEY(questionId, tagId))")
    }
}

/** 数据库 6 -> 7：加 review 表（艾宾浩斯复习调度） */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override suspend fun migrate(db: SQLiteConnection) {
        db.execSQL("CREATE TABLE IF NOT EXISTS review (questionId INTEGER PRIMARY KEY NOT NULL, intervalStep INTEGER NOT NULL, nextReviewAt INTEGER NOT NULL, lastReviewedAt INTEGER NOT NULL, reviewCount INTEGER NOT NULL)")
    }
}

/** 数据库 7 -> 8：加 solve_sessions 表（搜题记录；与 questions 完全隔离，现有查询一律不动） */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override suspend fun migrate(db: SQLiteConnection) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS solve_sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "source TEXT NOT NULL, " +
                "text TEXT NOT NULL, " +
                "answer TEXT NOT NULL, " +
                "conversationJson TEXT, " +
                "imageBytes BLOB, " +
                "imagesJson TEXT, " +
                "savedQuestionId INTEGER)"
        )
    }
}

/**
 * 数据库 8 -> 9：新增 `question_images` 表（一题多图，二进制、有序）。
 *
 * **旧数据原地升级**（用户 2026-10-16 硬要求）：
 *  - 建表 + 索引；
 *  - 把现有 `questions.imageBytes`（主图）**纯 SQL 搬进** `position = 0` —— 迁移即可用，不依赖任何后台任务；
 *  - 其余图（历史上以 base64 存在 `conversationJson` 里的）由启动时的"补全任务"追加（幂等，失败可重试）；
 *  - 只做加法：`questions.imageBytes` 列保留不删，读取一律「新表 → imageBytes → conversationJson」三层兜底。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override suspend fun migrate(db: SQLiteConnection) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS question_images (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "questionId INTEGER NOT NULL, " +
                "position INTEGER NOT NULL, " +
                "bytes BLOB NOT NULL, " +
                "source TEXT, " +
                "createdAt INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_question_images_questionId_position " +
                "ON question_images(questionId, position)"
        )
        // 旧主图 → position=0（纯 SQL，天然幂等：表刚建好时为空）
        db.execSQL(
            "INSERT INTO question_images (questionId, position, bytes, source, createdAt) " +
                "SELECT id, 0, imageBytes, 'migrate', createdAt FROM questions WHERE imageBytes IS NOT NULL"
        )
    }
}

/** Room executes this plan in its migration transaction. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override suspend fun migrate(db: SQLiteConnection) {
        fun times(sql: String): List<LegacyQuestionTime> = db.prepare(sql).use { cursor ->
            buildList { while (cursor.step()) add(LegacyQuestionTime(cursor.getLong(0), cursor.getLong(1))) }
        }
        val rows = times("SELECT id,createdAt FROM questions ORDER BY id")
        val orphans = db.prepare("SELECT questionId FROM question_tags UNION SELECT questionId FROM review UNION SELECT questionId FROM question_images").use { cursor ->
            buildList { while (cursor.step()) add(cursor.getLong(0)) }
        }
        val sessions = times("SELECT id,createdAt FROM solve_sessions ORDER BY id")
        IdentityMigrationPlan.statements(rows, orphans, sessions, System.currentTimeMillis()).forEach(db::execSQL)
    }
}

/** Keep all source fields and permanent IDs; the new column is a rebuildable list cache. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override suspend fun migrate(db: SQLiteConnection) {
        db.execSQL("ALTER TABLE solve_sessions ADD COLUMN title TEXT")
        db.execSQL("UPDATE solve_sessions SET title = $SOLVE_SESSION_TITLE_SQL")
        installSolveSessionTitleTriggers(db)
    }
}

@Database(entities = [Question::class, Category::class, Tag::class, QuestionTag::class, Review::class, SolveSession::class, QuestionImage::class], version = AppDatabase.SCHEMA_VERSION, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao
    lateinit var tagUndoStore: TagUndoStore
        internal set

    companion object {
        const val SCHEMA_VERSION = 11

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    // Before opening an older DB, checkpoint and durably copy it.
                    // Backup failure must abort opening instead of migrating without a copy.
                    backupDbBeforeMigration(context)
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "study_assistant.db"
                    ).setDriver(AndroidSQLiteDriver())
                        .addCallback(SOLVE_SESSION_TITLE_CALLBACK)
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).build().also {
                        it.tagUndoStore = SqliteTagUndoStore(context)
                        INSTANCE = it
                    }
                }
            }

        /** Checkpoint the old DB before copying; a failed backup aborts opening/migration. */
        private fun backupDbBeforeMigration(context: Context) {
            val dbFile = context.getDatabasePath("study_assistant.db")
            if (!dbFile.exists()) return
            val old = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            val sourceVersion = old.use { database ->
                if (database.version >= SCHEMA_VERSION) return
                database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0 && cursor.getInt(1) == cursor.getInt(2)) {
                        "Cannot checkpoint database before migration"
                    }
                }
                database.version
            }
            val target = java.io.File(dbFile.path + ".pre-v${SCHEMA_VERSION}-from-v${sourceVersion}-" + System.currentTimeMillis())
            check(!target.exists())
            dbFile.inputStream().use { input ->
                java.io.FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            check(target.length() == dbFile.length()) { "Incomplete pre-migration backup" }
        }

    }
}
