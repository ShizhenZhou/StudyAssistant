package com.zsz.studyassistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Disposable local auxiliary DB. No foreign keys into, or migrations of, the main Room database. */
class SqliteTagUndoStore(context: Context) : SQLiteOpenHelper(context.applicationContext,
    "tag_merge_undo.db", null, 1), TagUndoStore {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL(TagMergeSchema.CREATE) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported auxiliary undo format")
    }
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.execSQL("PRAGMA synchronous=FULL")
    }
    override fun read(slot: Int): TagMergeUndo? {
        val db = readableDatabase
        val ownTransaction = !db.inTransaction()
        if (ownTransaction) db.beginTransactionNonExclusive()
        try {
            val result = db.rawQuery(
                "SELECT createdAt,length(beforeJson),length(afterJson) FROM tag_merge_undo WHERE id=?",
                arrayOf(slot.toString())
            ).use { c ->
                if (!c.moveToFirst()) null else TagMergeUndo(c.getLong(0),
                    readJson(db, slot, "beforeJson", c.getInt(1)),
                    readJson(db, slot, "afterJson", c.getInt(2)))
            }
            if (ownTransaction) db.setTransactionSuccessful()
            return result
        } finally { if (ownTransaction) db.endTransaction() }
    }

    // SQLite substr/length count Unicode code points; do not advance by UTF-16 String.length.
    private fun readJson(db: SQLiteDatabase, slot: Int, column: String, length: Int): String {
        require(column == "beforeJson" || column == "afterJson")
        return buildString {
            var offset = 0
            while (offset < length) {
                val count = minOf(16_384, length - offset)
                val part = db.rawQuery("SELECT substr($column,?,?) FROM tag_merge_undo WHERE id=?",
                    arrayOf((offset + 1).toString(), count.toString(), slot.toString())).use { c ->
                    check(c.moveToFirst() && !c.isNull(0))
                    c.getString(0)
                }
                check(part.codePointCount(0, part.length) == count)
                append(part)
                offset += count
            }
        }
    }

    override fun stage(record: TagMergeUndo) {
        val values = ContentValues().apply {
            put("id", 2); put("createdAt", record.createdAt)
            put("beforeJson", record.beforeJson); put("afterJson", record.afterJson)
        }
        // A failed write throws before the main transaction is allowed to commit.
        check(writableDatabase.insertWithOnConflict("tag_merge_undo", null, values,
            SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    override fun resolve(promote: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (promote) {
                check(read(2) != null)
                db.execSQL("DELETE FROM tag_merge_undo WHERE id=1")
                db.execSQL("UPDATE tag_merge_undo SET id=1 WHERE id=2")
            } else db.execSQL("DELETE FROM tag_merge_undo WHERE id=2")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override fun clear() { writableDatabase.delete("tag_merge_undo", null, null) }
}
