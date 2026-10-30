package com.zsz.studyassistant.data

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.ByteArrayOutputStream

/** Both databases stay separate. Only the business DB participates in the Room transaction. */
internal class BackupStagedMerge(
    private val source:SQLiteDatabase,
    private val index:SQLiteDatabase,
    private val target:SupportSQLiteDatabase,
    private val check:()->Unit
) {
    private var categories=0; private var tags=0
    init {
        index.execSQL("PRAGMA temp_store=FILE")
        index.execSQL("PRAGMA cache_size=-2048")
        index.execSQL("CREATE TABLE new_ids(id TEXT PRIMARY KEY)")
        index.execSQL("CREATE TABLE category_map(old INTEGER PRIMARY KEY,mapped INTEGER NOT NULL)")
        index.execSQL("CREATE TABLE tag_map(old INTEGER PRIMARY KEY,mapped INTEGER NOT NULL)")
    }
    private fun exists(table:String,id:String):Boolean {
        check()
        return target.query("SELECT 1 FROM $table WHERE id=? LIMIT 1",arrayOf(id)).use { it.moveToFirst() }
    }
    private fun isNew(id:String):Boolean=index.rawQuery("SELECT 1 FROM new_ids WHERE id=?",arrayOf(id)).use { it.moveToFirst() }
    private fun insert(table:String,values:Map<String,Any?>):Long {
        check()
        val columns=values.keys.toList()
        target.compileStatement("INSERT INTO $table(${columns.joinToString(",")}) VALUES(${columns.joinToString(",") { "?" }})").use { statement ->
            columns.forEachIndexed { i,column -> when(val value=values[column]) {
                null -> statement.bindNull(i+1)
                is String -> statement.bindString(i+1,value)
                is ByteArray -> statement.bindBlob(i+1,value)
                is Number -> statement.bindLong(i+1,value.toLong())
                else -> BackupTree.invalid()
            } }
            return statement.executeInsert()
        }
    }
    /** No row cursor contains text or images. The only assembled field is a bounded definition name. */
    private fun definitionName(table:String,row:Long):String {
        val length=source.rawQuery("SELECT length(CAST(name AS BLOB)) FROM $table WHERE __row=?",arrayOf(row.toString())).use { it.moveToFirst(); it.getLong(0) }
        BackupTree.fieldSize(length)
        val output=ByteArrayOutputStream(minOf(length.toInt(),BackupTree.CHUNK))
        var offset=0L
        while(offset<length) {
            check()
            val chunk=source.rawQuery("SELECT substr(CAST(name AS BLOB),?,?) FROM $table WHERE __row=?",arrayOf((offset+1).toString(),minOf(BackupTree.CHUNK.toLong(),length-offset).toString(),row.toString())).use { it.moveToFirst(); it.getBlob(0) }
            if(chunk.isEmpty()) BackupTree.invalid()
            output.write(chunk); offset+=chunk.size
        }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
    }
    private fun definition(table:String,old:Long):Long {
        check()
        val map=if(table=="categories") "category_map" else "tag_map"
        index.rawQuery("SELECT mapped FROM $map WHERE old=?",arrayOf(old.toString())).use { if(it.moveToFirst()) return it.getLong(0) }
        val (row,created)=source.rawQuery("SELECT __row,createdAt FROM $table WHERE id=?",arrayOf(old.toString())).use { if(!it.moveToFirst()) BackupTree.invalid(); it.getLong(0) to it.getLong(1) }
        val name=definitionName(table,row)
        val existing=target.query("SELECT id FROM $table WHERE name=? ORDER BY id LIMIT 1",arrayOf(name)).use { if(it.moveToFirst()) it.getLong(0) else null }
        val mapped=existing ?: insert(table,linkedMapOf("name" to name,"createdAt" to created)).also { if(table=="categories") categories++ else tags++ }
        index.execSQL("INSERT INTO $map VALUES(?,?)",arrayOf(old,mapped))
        return mapped
    }
    private fun copyFields(tableName:String,sourceRow:Long,targetRow:Long,skip:Set<String>) {
        val table=BackupTree.tables.first { it.name==tableName }
        for(column in table.columns) {
            if(column.type=="INTEGER" || column.name in skip) continue
            check()
            val length=source.rawQuery("SELECT length(CAST(${column.name} AS BLOB)) FROM $tableName WHERE __row=?",arrayOf(sourceRow.toString())).use { if(!it.moveToFirst()) BackupTree.invalid(); if(it.isNull(0)) null else it.getLong(0) }
            if(length==null) { target.execSQL("UPDATE $tableName SET ${column.name}=NULL WHERE rowid=?",arrayOf(targetRow)); continue }
            BackupTree.fieldSize(length)
            target.execSQL("UPDATE $tableName SET ${column.name}=? WHERE rowid=?",arrayOf(ByteArray(0),targetRow))
            var offset=0L
            while(offset<length) {
                check()
                val bytes=source.rawQuery("SELECT substr(CAST(${column.name} AS BLOB),?,?) FROM $tableName WHERE __row=?",arrayOf((offset+1).toString(),minOf(BackupTree.CHUNK.toLong(),length-offset).toString(),sourceRow.toString())).use { if(!it.moveToFirst()) BackupTree.invalid(); it.getBlob(0) }
                if(bytes.isEmpty()) BackupTree.invalid()
                target.execSQL("UPDATE $tableName SET ${column.name}=CAST(${column.name} AS BLOB)||? WHERE rowid=?",arrayOf(bytes,targetRow))
                offset+=bytes.size
            }
            target.execSQL("UPDATE $tableName SET ${column.name}=CAST(${column.name} AS ${column.type}) WHERE rowid=?",arrayOf(targetRow))
        }
    }
    fun merge():ImportResult {
        var questions=0; var skipped=0; var images=0; var sessions=0
        source.rawQuery("SELECT __row,id,createdAt,deleted,categoryId FROM questions ORDER BY __row",null).use { rows ->
            while(rows.moveToNext()) {
                check(); val row=rows.getLong(0); val id=rows.getString(1)
                if(exists("questions",id)) { skipped++; continue }
                val category=if(rows.isNull(4)) null else definition("categories",rows.getLong(4))
                val targetRow=insert("questions",linkedMapOf("id" to id,"text" to "","answer" to "","createdAt" to rows.getLong(2),"imageBytes" to null,"conversationJson" to null,"deleted" to rows.getLong(3),"categoryId" to category))
                copyFields("questions",row,targetRow,setOf("id"))
                index.execSQL("INSERT INTO new_ids VALUES(?)",arrayOf(id)); questions++
            }
        }
        source.rawQuery("SELECT __row,questionId,position,createdAt FROM question_images ORDER BY __row",null).use { rows ->
            while(rows.moveToNext()) {
                check(); val id=rows.getString(1); if(!isNew(id)) continue
                val targetRow=insert("question_images",linkedMapOf("questionId" to id,"position" to rows.getLong(2),"bytes" to ByteArray(0),"source" to null,"createdAt" to rows.getLong(3)))
                copyFields("question_images",rows.getLong(0),targetRow,setOf("questionId")); images++
            }
        }
        source.rawQuery("SELECT questionId,tagId FROM question_tags ORDER BY __row",null).use { rows ->
            while(rows.moveToNext()) {
                check(); val id=rows.getString(0); if(!isNew(id)) continue
                val mapped=definition("tags",rows.getLong(1))
                target.execSQL("INSERT OR IGNORE INTO question_tags(questionId,tagId) VALUES(?,?)",arrayOf<Any>(id,mapped))
            }
        }
        source.rawQuery("SELECT questionId,intervalStep,nextReviewAt,lastReviewedAt,reviewCount FROM review ORDER BY __row",null).use { rows ->
            while(rows.moveToNext()) {
                check(); val id=rows.getString(0); if(!isNew(id)) continue
                insert("review",linkedMapOf("questionId" to id,"intervalStep" to rows.getLong(1),"nextReviewAt" to rows.getLong(2),"lastReviewedAt" to rows.getLong(3),"reviewCount" to rows.getLong(4)))
            }
        }
        source.rawQuery("SELECT __row,id,createdAt,updatedAt,${BackupTree.sessionImageExpression} FROM solve_sessions ORDER BY __row",null).use { rows ->
            while(rows.moveToNext()) {
                check(); val id=rows.getString(1); if(exists("solve_sessions",id)) continue
                val targetRow=insert("solve_sessions",linkedMapOf("id" to id,"createdAt" to rows.getLong(2),"updatedAt" to rows.getLong(3),"source" to "","text" to "","answer" to "","conversationJson" to null,"imageBytes" to null,"imagesJson" to null,"savedQuestionId" to id.takeIf { exists("questions",id) }))
                copyFields("solve_sessions",rows.getLong(0),targetRow,setOf("id","savedQuestionId")); sessions++; images+=rows.getInt(4)
            }
        }
        source.rawQuery("SELECT id FROM questions ORDER BY __row",null).use { rows ->
            while(rows.moveToNext()) {
                check(); val id=rows.getString(0)
                target.execSQL("UPDATE solve_sessions SET savedQuestionId=id WHERE id=? AND EXISTS(SELECT 1 FROM questions WHERE id=?)",arrayOf(id,id))
            }
        }
        check() // The final cancellation boundary is before Room commits the one business transaction.
        return ImportResult(questions,categories,tags,skipped,sessions,images)
    }
}
