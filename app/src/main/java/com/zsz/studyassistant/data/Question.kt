package com.zsz.studyassistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "questions")
data class Question(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** 错题 ↔ 知识点标签 的多对多关联表 */
@Entity(tableName = "question_tags", primaryKeys = ["questionId", "tagId"])
data class QuestionTag(
    val questionId: Long,
    val tagId: Long
)

/** 复习调度（艾宾浩斯）；questionId 为主键，一题一条 */
@Entity(tableName = "review")
data class Review(
    @PrimaryKey val questionId: Long,
    // 当前间隔档位（0..6，对应 intervalDays）
    val intervalStep: Int = 0,
    // 下次复习时间戳
    val nextReviewAt: Long = System.currentTimeMillis(),
    val lastReviewedAt: Long = 0,
    val reviewCount: Int = 0
)

@Dao
interface QuestionDao {
    @Query("SELECT * FROM questions WHERE deleted = 0 ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Question>>

    @Query("SELECT * FROM categories ORDER BY createdAt ASC")
    fun categories(): Flow<List<Category>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun tags(): Flow<List<Tag>>

    @Query("SELECT * FROM tags")
    suspend fun getAllTagsOnce(): List<Tag>

    @Query("SELECT tagId FROM question_tags WHERE questionId = :questionId")
    suspend fun tagIdsForQuestion(questionId: Long): List<Long>

    @Query("SELECT * FROM question_tags")
    fun allQuestionTags(): Flow<List<QuestionTag>>

    @Insert
    suspend fun insert(q: Question): Long

    @Insert
    suspend fun insertCategory(c: Category): Long

    @Insert
    suspend fun insertTag(t: Tag): Long

    @Insert
    suspend fun insertQuestionTag(qt: QuestionTag)

    @Query("DELETE FROM question_tags WHERE questionId = :questionId AND tagId = :tagId")
    suspend fun deleteQuestionTag(questionId: Long, tagId: Long)

    @Query("DELETE FROM question_tags WHERE questionId = :questionId")
    suspend fun clearQuestionTags(questionId: Long)

    // ---- 复习调度 ----
    /** 到期错题（nextReviewAt <= until），按下次复习时间升序 */
    // ★ 只取轻字段：不要 SELECT q.*（含 imageBytes 大 BLOB 与会话 JSON），
//   否则复习队列题一多，结果集超过 CursorWindow 的 2MB 上限会直接崩：
//   IllegalStateException: Couldn't read row N, col 0 from CursorWindow
//   图片/会话在真正进入某题时用 heavyOnce(id) 单独取。
@Query("SELECT DISTINCT q.id, q.text, q.answer, q.createdAt, q.deleted, q.categoryId FROM questions q INNER JOIN review r ON q.id = r.questionId WHERE q.deleted = 0 AND r.nextReviewAt <= :until ORDER BY r.nextReviewAt ASC")
    fun dueQuestions(until: Long): Flow<List<Question>>

    /** 按 id 取完整一行（含图片 BLOB 与会话 JSON）——列表查询不用它，避免撑爆 CursorWindow */
    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun heavyOnce(id: Long): Question?

    @Query("SELECT * FROM review WHERE questionId = :questionId")
    suspend fun reviewFor(questionId: Long): Review?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReview(r: Review)

    @Query("UPDATE review SET intervalStep = :step, nextReviewAt = :next, lastReviewedAt = :last, reviewCount = reviewCount + 1 WHERE questionId = :questionId")
    suspend fun updateReview(questionId: Long, step: Int, next: Long, last: Long)

    @Update
    suspend fun update(q: Question)

    @Delete
    suspend fun delete(q: Question)

    @Query("DELETE FROM questions WHERE categoryId = :categoryId")
    suspend fun deleteByCategory(categoryId: Long)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun deleteById(id: Long)

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
    suspend fun setCategoryForIds(ids: List<Long>, cid: Long?)

    // 批量：彻底删除
    @Query("DELETE FROM questions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE questions SET deleted = 1 WHERE id = :id")
    suspend fun softDelete(id: Long)

    @Query("UPDATE questions SET deleted = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM questions")
    suspend fun clear()

    // ---- 备份 / 恢复 / 数据管理 ----
    @Query("SELECT * FROM questions ORDER BY id ASC")
    suspend fun allQuestionsOnce(): List<Question>

    /** 轻查询（不含图片 BLOB / 会话 JSON）：查重（B9）等只要题干的场景用，避免大字段进出 */
    @Query("SELECT id, text, answer, createdAt, deleted, categoryId FROM questions WHERE deleted = 0")
    suspend fun allLightOnce(): List<Question>

    /**
     * 「原图回填」用（2026-10-14）：找出 `imageBytes` 为空、但会话 JSON 还在的错题。
     * 只取必要列（**不含 BLOB**），避免把大图整批读进内存。
     * ⚠️ `createdAt`/`deleted` 是**非空基元**，Room 要求它们必须出现在投影里（Kotlin 默认值不算数）。
     */
    @Query("SELECT id, text, answer, createdAt, deleted, conversationJson FROM questions WHERE deleted = 0 AND imageBytes IS NULL AND conversationJson IS NOT NULL")
    suspend fun questionsMissingImageOnce(): List<Question>

    /** 「原图回填」用：只写回主图，不动其它字段（避免整行 UPDATE 覆盖并发修改） */
    @Query("UPDATE questions SET imageBytes = :img WHERE id = :id")
    suspend fun setQuestionImage(id: Long, img: ByteArray)

    /**
     * 「多图修复」用（2026-10-14）：连会话 JSON 一起取出来，用于判断「这道题存下的图比它的搜题记录少」。
     * 仍**不含 BLOB**（不取 `imageBytes`），避免把大图整批读进内存。
     */
    @Query("SELECT id, text, answer, createdAt, deleted, categoryId, conversationJson FROM questions WHERE deleted = 0")
    suspend fun questionsWithConversationOnce(): List<Question>

    /** 「多图修复」用：把补好的会话 JSON 写回（只动这一列） */
    @Query("UPDATE questions SET conversationJson = :cj WHERE id = :id")
    suspend fun setConversationJson(id: Long, cj: String)

    /** 「多图修复」用：按"已存错题 id"找到对应搜题记录（记录里 `imagesJson` 有全部图） */
    @Query("SELECT * FROM solve_sessions WHERE savedQuestionId = :qid ORDER BY createdAt DESC LIMIT 1")
    suspend fun solveSessionForQuestion(qid: Long): SolveSession?

    /** 「多图修复」用（兜底）：按题干文本匹配搜题记录 */
    @Query("SELECT * FROM solve_sessions WHERE text = :text AND imagesJson IS NOT NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun solveSessionByText(text: String): SolveSession?

    // ── DB v9：一题多图（`question_images` 表，0=主图）────────────────────────

    /** 读某题的全部原图（按 position 升序，0 = 主图） */
    @Query("SELECT * FROM question_images WHERE questionId = :qid ORDER BY position ASC")
    suspend fun imagesForQuestion(qid: Long): List<QuestionImage>

    /** 批量读"主图"（列表页兜底；只取 position=0，避免把全部大图读进内存） */
    @Query("SELECT * FROM question_images WHERE position = 0 AND questionId IN (:qids)")
    suspend fun mainImagesFor(qids: List<Long>): List<QuestionImage>

    @Insert
    suspend fun insertImages(list: List<QuestionImage>): List<Long>

    @Query("DELETE FROM question_images WHERE questionId = :qid")
    suspend fun clearImages(qid: Long)

    @Query("DELETE FROM question_images WHERE questionId IN (:qids)")
    suspend fun clearImagesFor(qids: List<Long>)

    /** 清理孤儿图片（题已被彻底删除） */
    @Query("DELETE FROM question_images WHERE questionId NOT IN (SELECT id FROM questions)")
    suspend fun purgeOrphanImages()

    // ── 备份 v2：搜题记录整表搬 ────────────────────────────────────────────

    @Query("SELECT * FROM solve_sessions ORDER BY createdAt ASC")
    suspend fun allSolveSessionsOnce(): List<SolveSession>

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
    suspend fun insertQuestionTags(list: List<QuestionTag>)

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
    suspend fun questionIdsInCategories(ids: List<Long>): List<Long>

    /** 删题：连同这些科目的错题一起删（调用方需先清关联与复习记录） */
    @Query("DELETE FROM questions WHERE categoryId IN (:ids)")
    suspend fun deleteQuestionsInCategories(ids: List<Long>)

    @Query("DELETE FROM question_tags WHERE questionId IN (:qids)")
    suspend fun deleteQuestionTagsForQuestions(qids: List<Long>)

    @Query("DELETE FROM review WHERE questionId IN (:qids)")
    suspend fun deleteReviewsForQuestions(qids: List<Long>)

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
    @Query("SELECT id, createdAt, updatedAt, source, text, answer, savedQuestionId FROM solve_sessions ORDER BY createdAt DESC")
    fun solveSessionsLight(): Flow<List<SolveSession>>

    /** 按 id 取**重字段**（含原图 BLOB 与会话 JSON）——列表查询不用它，避免撑爆 CursorWindow */
    @Query("SELECT * FROM solve_sessions WHERE id = :id")
    suspend fun solveSessionOnce(id: Long): SolveSession?

    @Query("SELECT COUNT(*) FROM solve_sessions")
    suspend fun countSolveSessions(): Int

    /** 全部记录的轻字段（裁剪策略用：只判 id 与 createdAt） */
    @Query("SELECT id, createdAt, updatedAt, source, text, answer, savedQuestionId FROM solve_sessions")
    suspend fun solveSessionsLightOnce(): List<SolveSession>

    @Query("DELETE FROM solve_sessions WHERE id IN (:ids)")
    suspend fun deleteSolveSessionsByIds(ids: List<Long>)

    /** 删除某时间点之前（不含）的记录，返回删除条数 */
    @Query("DELETE FROM solve_sessions WHERE createdAt < :ts")
    suspend fun deleteSolveSessionsBefore(ts: Long): Int
}

/** 数据库 1 -> 2：给 questions 表加 imageBytes 列，保留现有错题 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE questions ADD COLUMN imageBytes BLOB")
    }
}

/** 数据库 2 -> 3：加 conversationJson 列（对话会话） */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE questions ADD COLUMN conversationJson TEXT")
    }
}

/** 数据库 3 -> 4：加 deleted 列（软删除/恢复） */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE questions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
    }
}

/** 数据库 4 -> 5：加 categories 表 + questions.categoryId 列（错题分类） */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE questions ADD COLUMN categoryId INTEGER")
    }
}

/** 数据库 5 -> 6：加 tags 表 + question_tags 关联表（知识点 tag，多对多） */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS question_tags (questionId INTEGER NOT NULL, tagId INTEGER NOT NULL, PRIMARY KEY(questionId, tagId))")
    }
}

/** 数据库 6 -> 7：加 review 表（艾宾浩斯复习调度） */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS review (questionId INTEGER PRIMARY KEY NOT NULL, intervalStep INTEGER NOT NULL, nextReviewAt INTEGER NOT NULL, lastReviewedAt INTEGER NOT NULL, reviewCount INTEGER NOT NULL)")
    }
}

/** 数据库 7 -> 8：加 solve_sessions 表（搜题记录；与 questions 完全隔离，现有查询一律不动） */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
    override fun migrate(db: SupportSQLiteDatabase) {
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

@Database(entities = [Question::class, Category::class, Tag::class, QuestionTag::class, Review::class, SolveSession::class, QuestionImage::class], version = 9, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    // ★ 2026-10-16：**迁移前先把 DB 文件复制一份**（一次性、失败不阻塞启动）。
                    //   用户库里可能有 v1/v5/v8 等老版本数据，MIGRATION_* 会一路升到 v9；
                    //   万一某一环在特定老库上出问题（Room 校验失败会直接抛异常 → 应用起不来），
                    //   有 `study_assistant.db.pre-v9` 就能直接拷回，不至于丢数据。
                    backupDbBeforeMigration(context)
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "study_assistant.db"
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build().also { INSTANCE = it }
                }
            }

        /** 迁移前把 `study_assistant.db`（含 -wal/-shm）复制为 `.pre-v9`；只做一次 */
        private fun backupDbBeforeMigration(context: Context) {
            runCatching {
                val sp = context.getSharedPreferences("db_backup", Context.MODE_PRIVATE)
                if (sp.getBoolean("pre_v9_done", false)) return
                val dbFile = context.getDatabasePath("study_assistant.db")
                if (dbFile.exists()) {
                    val dir = dbFile.parentFile ?: return
                    for (suffix in listOf("", "-wal", "-shm")) {
                        val src = java.io.File(dbFile.path + suffix)
                        if (src.exists()) {
                            src.copyTo(java.io.File(dir, "study_assistant.db.pre-v9$suffix"), overwrite = true)
                        }
                    }
                    android.util.Log.i("StudyAssistant", "迁移前已备份 DB → study_assistant.db.pre-v9")
                }
                sp.edit().putBoolean("pre_v9_done", true).apply()
            }
        }
    }
}
