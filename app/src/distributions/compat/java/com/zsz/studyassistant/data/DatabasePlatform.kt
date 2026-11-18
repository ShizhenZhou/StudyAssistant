package com.zsz.studyassistant.data

import android.util.JsonReader
import android.util.JsonToken
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.Reader

/** API 28–35: JSON1 is optional, so title writes and scans use bounded streaming. */
internal object DatabasePlatform {
    fun installTitleCache(connection: SQLiteConnection) {
        connection.execSQL("DROP TRIGGER IF EXISTS solve_sessions_title_insert")
        connection.execSQL("DROP TRIGGER IF EXISTS solve_sessions_title_update")
    }
    fun migrateTitles(db: SQLiteConnection) = SolveSessionTitle.migrate(db)
    fun prepareSession(session: SolveSession): SolveSession =
        session.copy(title = SolveSessionTitle.fromJson(session.conversationJson))
    fun rebuildTitle(db: SupportSQLiteDatabase, row: Long, check: () -> Unit) = SolveSessionTitle.rebuild(db, row, check)
    fun boundDraftReader(reader: Reader): Reader = StoredConversationJson.bounded(reader)
    fun scanDocuments(source: SupportSQLiteDatabase, addHash: (String) -> Unit,
                      scanValue: (JsonReader) -> Unit, check: () -> Unit) {
        for (table in listOf("questions", "solve_sessions")) {
            var after: Long? = null
            while (true) {
                check()
                val rows = source.query("SELECT rowid FROM $table WHERE conversationJson IS NOT NULL" +
                    (if (after == null) "" else " AND rowid>?") + " ORDER BY rowid LIMIT 64",
                    after?.let { arrayOf<Any>(it) } ?: emptyArray()).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
                }
                if (rows.isEmpty()) break
                for (row in rows) {
                    check()
                    val sourceReader = requireNotNull(StoredConversationJson.open(source, table, row, check))
                    JsonReader(StoredConversationJson.bounded(sourceReader)).use { reader ->
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(reader.peek() == JsonToken.BEGIN_OBJECT)
                            scanValue(reader)
                        }
                        reader.endArray()
                        require(reader.peek() == JsonToken.END_DOCUMENT)
                    }
                }
                after = rows.last()
            }
        }
    }
}
