package com.zsz.studyassistant.data

import android.util.JsonReader
import android.util.JsonToken
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.Reader

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

/** Mainline SQLite JSON1 path; schema and serialized data remain shared. */
internal object DatabasePlatform {
    fun installTitleCache(connection: SQLiteConnection) = installSolveSessionTitleTriggers(connection)
    fun migrateTitles(db: SQLiteConnection) {
        db.execSQL("UPDATE solve_sessions SET title = $SOLVE_SESSION_TITLE_SQL")
        installTitleCache(db)
    }
    fun prepareSession(session: SolveSession): SolveSession = session
    fun rebuildTitle(db: SupportSQLiteDatabase, row: Long, check: () -> Unit) { /* SQL trigger owns this cache. */ }
    fun boundDraftReader(reader: Reader): Reader = reader
    fun scanDocuments(source: SupportSQLiteDatabase, addHash: (String) -> Unit,
                      scanValue: (JsonReader) -> Unit, check: () -> Unit) {
        for (table in listOf("questions", "solve_sessions")) {
            check()
            source.query("SELECT 1 FROM $table WHERE conversationJson IS NOT NULL AND NOT json_valid(conversationJson) LIMIT 1").use {
                require(!it.moveToFirst())
            }
            source.query("SELECT 1 FROM $table WHERE conversationJson IS NOT NULL AND json_type(conversationJson) <> 'array' LIMIT 1").use {
                require(!it.moveToFirst())
            }
            source.query("SELECT 1 FROM $table AS t, json_tree(t.conversationJson) AS a " +
                "WHERE a.key IN ('documents','localDocuments') AND a.type <> 'array' LIMIT 1").use {
                require(!it.moveToFirst())
            }
            // All JSON expansion stays inside SQLite; CursorWindow receives only a SHA or NULL.
            source.query("SELECT CASE WHEN d.type = 'object' THEN CASE " +
                "WHEN (SELECT count(*) FROM json_each(d.value) WHERE key = 'sha256') = 1 " +
                "AND json_type(d.value,'$.sha256') = 'text' AND length(json_extract(d.value,'$.sha256')) = 64 " +
                "THEN json_extract(d.value,'$.sha256') ELSE NULL END ELSE NULL END " +
                "FROM $table AS t, json_tree(t.conversationJson) AS a, " +
                "json_each(CASE WHEN a.key IN ('documents','localDocuments') AND a.type = 'array' THEN a.value ELSE '[]' END) AS d").use {
                while (it.moveToNext()) { check(); require(!it.isNull(0)); addHash(it.getString(0)) }
            }
        }
    }
}
