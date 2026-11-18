package com.zsz.studyassistant.data

import android.util.JsonReader
import android.util.JsonToken
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import kotlinx.coroutines.CancellationException

/** Read within the caller's transaction. Only a 64 KiB BLOB slice enters CursorWindow,
 * including when old conversations contain multi-megabyte base64 images. No JSON1 dependency. */
internal object StoredConversationJson {
    private const val CHUNK = 64 * 1024

    fun open(db: SupportSQLiteDatabase, table: String, row: Long, check: () -> Unit): Reader? {
        require(table == "questions" || table == "solve_sessions")
        val length = db.query("SELECT length(CAST(conversationJson AS BLOB)) FROM $table WHERE rowid=?", arrayOf(row)).use {
            require(it.moveToFirst()); if (it.isNull(0)) return null else it.getLong(0)
        }
        return stream(length, check) { offset, count ->
            db.query("SELECT substr(CAST(conversationJson AS BLOB),?,?) FROM $table WHERE rowid=?",
                arrayOf<Any>(offset, count, row)).use { require(it.moveToFirst()); it.getBlob(0) }
        }
    }

    fun open(db: SQLiteConnection, row: Long): Reader? {
        val length = db.prepare("SELECT length(CAST(conversationJson AS BLOB)) FROM solve_sessions WHERE rowid=?").use {
            it.bindLong(1, row); require(it.step()); if (it.isNull(0)) return null else it.getLong(0)
        }
        return stream(length, {}) { offset, count ->
            db.prepare("SELECT substr(CAST(conversationJson AS BLOB),?,?) FROM solve_sessions WHERE rowid=?").use {
                it.bindLong(1, offset); it.bindLong(2, count.toLong()); it.bindLong(3, row)
                require(it.step()); it.getBlob(0)
            }
        }
    }

    private fun stream(length: Long, check: () -> Unit, chunk: (Long, Int) -> ByteArray): Reader {
        require(length >= 0)
        val input = object : InputStream() {
            private var position = 0L
            private var pending = ByteArray(0)
            private var pendingOffset = 0
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            }
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                require(offset >= 0 && count >= 0 && offset <= buffer.size - count)
                check()
                if (count == 0) return 0
                if (position == length) return -1
                if (pendingOffset == pending.size) {
                    val size = minOf(CHUNK.toLong(), length - position).toInt()
                    pending = chunk(position + 1, size)
                    require(pending.size == size)
                    pendingOffset = 0
                }
                val size = minOf(count, pending.size - pendingOffset)
                pending.copyInto(buffer, offset, pendingOffset, pendingOffset + size)
                pendingOffset += size
                position += size
                return size
            }
        }
        return InputStreamReader(input, Charsets.UTF_8.newDecoder())
    }

    /** Bounds even a malicious single JSON token; skipped images are never materialized. */
    fun bounded(reader: Reader): Reader = object : Reader() {
        private var remaining = minOf(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 32)
        override fun read(buffer: CharArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (remaining == 0L) {
                if (reader.read() == -1) return -1
                throw IOException("Conversation exceeds JSON scan budget")
            }
            val size = reader.read(buffer, offset, minOf(count.toLong(), remaining).toInt())
            if (size > 0) remaining -= size
            return size
        }
        override fun close() = reader.close()
    }
}

/** Derived only at writes/migration/restore. Invalid or oversized JSON falls back to the
 * existing question preview; source JSON is never rewritten. */
internal object SolveSessionTitle {
    fun fromJson(value: String?): String? = value?.let { fromReader(StringReader(it)) }

    fun fromReader(source: Reader): String? = try {
        JsonReader(StoredConversationJson.bounded(source)).use { reader ->
            reader.beginArray()
            var title: String? = null
            if (reader.hasNext()) {
                if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                    reader.beginObject()
                    var seenTitle = false
                    while (reader.hasNext()) {
                        if (reader.nextName() == "conversationTitle") {
                            require(!seenTitle); seenTitle = true
                            if (reader.peek() == JsonToken.STRING) {
                                val value = reader.nextString()
                                title = value.substring(0, value.offsetByCodePoints(0, minOf(256, value.codePointCount(0, value.length))))
                            } else skip(reader, 1)
                        } else skip(reader, 1)
                    }
                    reader.endObject()
                } else skip(reader, 1)
            }
            while (reader.hasNext()) skip(reader, 1)
            reader.endArray()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            title
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: IOException) { null }
      catch (_: IllegalArgumentException) { null }
      catch (_: IllegalStateException) { null }

    private fun skip(reader: JsonReader, depth: Int) {
        require(depth <= 128)
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray(); while (reader.hasNext()) skip(reader, depth + 1); reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) { reader.nextName(); skip(reader, depth + 1) }
                reader.endObject()
            }
            else -> reader.skipValue()
        }
    }

    fun rebuild(db: SupportSQLiteDatabase, row: Long, check: () -> Unit) {
        val title = StoredConversationJson.open(db, "solve_sessions", row, check)?.let(::fromReader)
        check()
        db.execSQL("UPDATE solve_sessions SET title=? WHERE rowid=?", arrayOf<Any?>(title, row))
    }

    fun migrate(db: SQLiteConnection) {
        var after: Long? = null
        while (true) {
            val rows = db.prepare("SELECT rowid FROM solve_sessions" +
                (if (after == null) "" else " WHERE rowid>?") + " ORDER BY rowid LIMIT 64").use {
                after?.let { row -> it.bindLong(1, row) }
                buildList { while (it.step()) add(it.getLong(0)) }
            }
            if (rows.isEmpty()) return
            for (row in rows) {
                val title = StoredConversationJson.open(db, row)?.let(::fromReader)
                db.prepare("UPDATE solve_sessions SET title=? WHERE rowid=?").use {
                    if (title == null) it.bindNull(1) else it.bindText(1, title)
                    it.bindLong(2, row); it.step()
                }
            }
            after = rows.last()
        }
    }
}
