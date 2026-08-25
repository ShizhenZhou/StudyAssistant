package com.zsz.studyassistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
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

@Dao
interface QuestionDao {
    @Query("SELECT * FROM questions WHERE deleted = 0 ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Question>>

    @Query("SELECT * FROM categories ORDER BY createdAt ASC")
    fun categories(): Flow<List<Category>>

    @Insert
    suspend fun insert(q: Question): Long

    @Insert
    suspend fun insertCategory(c: Category): Long

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

@Database(entities = [Question::class, Category::class], version = 5, exportSchema = false)
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { INSTANCE = it }
            }
    }
}
