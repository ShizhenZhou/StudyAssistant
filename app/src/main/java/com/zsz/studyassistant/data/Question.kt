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
    @Query("SELECT q.* FROM questions q INNER JOIN review r ON q.id = r.questionId WHERE q.deleted = 0 AND r.nextReviewAt <= :until ORDER BY r.nextReviewAt ASC")
    fun dueQuestions(until: Long): Flow<List<Question>>

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

@Database(entities = [Question::class, Category::class, Tag::class, QuestionTag::class, Review::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "study_assistant.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { INSTANCE = it }
            }
    }
}
