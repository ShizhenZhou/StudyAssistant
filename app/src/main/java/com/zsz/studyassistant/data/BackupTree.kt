package com.zsz.studyassistant.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.*
import java.security.MessageDigest
import java.nio.file.Files
import java.util.Base64

/** Private tree version 1 inside Bak4: fixed-schema framed rows, SHA-256 image resources. */
internal object BackupTree {
    const val CHUNK=64*1024
    const val sessionImageExpression="CASE WHEN imagesJson IS NOT NULL THEN (length(imagesJson)-length(replace(imagesJson,'\"','')))/2 WHEN imageBytes IS NOT NULL THEN 1 ELSE 0 END"
    val residentLimit: Int get() = minOf(16L*1024*1024,Runtime.getRuntime().maxMemory()/16).toInt()
    data class Column(val name:String,val type:String,val nullable:Boolean=false)
    data class Table(val name:String,val columns:List<Column>,val key:String)
    private fun cols(vararg definitions:String)=definitions.map { value ->
        val split=value.split(':'); Column(split[0],split[1].removeSuffix("?"),split[1].endsWith('?'))
    }
    val tables=listOf(
        Table("categories",cols("id:INTEGER","name:TEXT","createdAt:INTEGER"),"id"),
        Table("tags",cols("id:INTEGER","name:TEXT","createdAt:INTEGER"),"id"),
        Table("questions",cols("id:TEXT","text:TEXT","answer:TEXT","createdAt:INTEGER","imageBytes:BLOB?","conversationJson:TEXT?","deleted:INTEGER","categoryId:INTEGER?"),"id"),
        Table("question_images",cols("id:INTEGER","questionId:TEXT","position:INTEGER","bytes:BLOB","source:TEXT?","createdAt:INTEGER"),"id"),
        Table("question_tags",cols("questionId:TEXT","tagId:INTEGER"),"questionId,tagId"),
        Table("review",cols("questionId:TEXT","intervalStep:INTEGER","nextReviewAt:INTEGER","lastReviewedAt:INTEGER","reviewCount:INTEGER"),"questionId"),
        Table("solve_sessions",cols("id:TEXT","createdAt:INTEGER","updatedAt:INTEGER","source:TEXT","text:TEXT","answer:TEXT","conversationJson:TEXT?","imageBytes:BLOB?","imagesJson:TEXT?","savedQuestionId:TEXT?"),"id")
    )
    fun space(directory:File,required:Long) {
        if(directory.usableSpace < required + 8L*1024*1024) throw BackupValidationException("data.backupSpaceInsufficient")
    }
    fun invalid():Nothing=throw BackupValidationException("data.backupInvalid")
    fun fieldSize(size:Long) { if(size<0 || size>residentLimit) throw BackupValidationException("data.backupFieldTooLarge") }
    fun copy(input:InputStream,output:OutputStream,check:()->Unit):Long {
        val buffer=ByteArray(CHUNK); var total=0L
        while(true) { check(); val n=input.read(buffer); if(n<0) return total; output.write(buffer,0,n); total+=n }
    }
    fun digest(file:File,check:()->Unit):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b=ByteArray(CHUNK); while(true) { check(); val n=input.read(b); if(n<0) break; hash.update(b,0,n) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    /** A canonical content digest with its whole-tree path index kept on disk. */
    fun contentDigest(root:File,check:()->Unit):String {
        val indexFile=File(root.parentFile,"checkpoint-index-${java.util.UUID.randomUUID()}.db")
        try {
            val result=MessageDigest.getInstance("SHA-256")
            SQLiteDatabase.openOrCreateDatabase(indexFile,null).use { index ->
                index.execSQL("PRAGMA temp_store=FILE"); index.execSQL("PRAGMA cache_size=-2048")
                index.execSQL("CREATE TABLE files(path TEXT PRIMARY KEY, bytes INTEGER NOT NULL, sha TEXT NOT NULL)")
                index.beginTransaction()
                try {
                    Files.walkFileTree(root.toPath(),object:java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                        override fun visitFile(path:java.nio.file.Path,attributes:java.nio.file.attribute.BasicFileAttributes):java.nio.file.FileVisitResult {
                            check(); if(!attributes.isRegularFile || attributes.isSymbolicLink) invalid()
                            val relative=root.toPath().relativize(path).joinToString("/") { it.toString() }
                            if(relative.toByteArray(Charsets.UTF_8).size>4096) invalid()
                            val file=path.toFile()
                            index.execSQL("INSERT INTO files VALUES(?,?,?)",arrayOf(relative,file.length(),digest(file,check)))
                            return java.nio.file.FileVisitResult.CONTINUE
                        }
                        override fun preVisitDirectory(path:java.nio.file.Path,attributes:java.nio.file.attribute.BasicFileAttributes):java.nio.file.FileVisitResult {
                            check(); if(attributes.isSymbolicLink) invalid()
                            return java.nio.file.FileVisitResult.CONTINUE
                        }
                    })
                    index.setTransactionSuccessful()
                } finally { index.endTransaction() }
                index.rawQuery("SELECT path,bytes,sha FROM files ORDER BY path",null).use { files ->
                    while(files.moveToNext()) {
                        check(); val path=files.getString(0).toByteArray(Charsets.UTF_8)
                        result.update(java.nio.ByteBuffer.allocate(4).putInt(path.size).array()); result.update(path)
                        result.update(java.nio.ByteBuffer.allocate(8).putLong(files.getLong(1)).array())
                        result.update(files.getString(2).toByteArray(Charsets.US_ASCII))
                    }
                }
            }
            return result.digest().joinToString("") { "%02x".format(it) }
        } finally {
            indexFile.delete(); File(indexFile.path+"-journal").delete()
        }
    }
    private fun rowFile(root:File,table:Table,index:Long)=File(root,"${table.name}/${index.toString().padStart(12,'0')}.row")
    fun writeCounts(root:File,counts:LongArray) {
        FileOutputStream(File(root,"manifest")).use { file -> DataOutputStream(file).let { out ->
            out.writeInt(0x53415431); out.writeInt(tables.size); counts.forEach(out::writeLong); out.flush(); file.fd.sync()
        } }
    }
    fun counts(root:File):LongArray {
        val manifest=File(root,"manifest")
        // A non-business ZIP may have no manifest; this is a format rejection, not a disk read failure.
        if(!manifest.isFile) invalid()
        return DataInputStream(manifest.inputStream()).use { input ->
            if(input.readInt()!=0x53415431 || input.readInt()!=tables.size) invalid()
            LongArray(tables.size) { input.readLong().also { n -> if(n<0 || n>100_000_000) invalid() } }.also { if(input.read()!=-1) invalid() }
        }
    }

    /** Query only one key/length/chunk; no CursorWindow ever contains a full text or image. */
    fun capture(root:File,query:(String,Array<String>)->Cursor,check:()->Unit,progress:(Long)->Unit) {
        val counts=LongArray(tables.size); var completed=0L
        for((ti,table) in tables.withIndex()) {
            File(root,table.name).mkdir()
            query("SELECT rowid FROM ${table.name} ORDER BY ${table.key}",emptyArray()).use { ids ->
                while(ids.moveToNext()) {
                    check(); val rowid=ids.getLong(0)
                    FileOutputStream(rowFile(root,table,counts[ti]++)).use { file ->
                        val output=DataOutputStream(BufferedOutputStream(file))
                        for(column in table.columns) {
                            // The notebook counterpart is defined by permanent ID; old startup purge
                            // can leave a stale savedQuestionId, as did the previous exporter.
                            val expression=if(table.name=="solve_sessions" && column.name=="savedQuestionId")
                                "CASE WHEN EXISTS(SELECT 1 FROM questions q WHERE q.id=solve_sessions.id) THEN solve_sessions.id ELSE NULL END"
                                else column.name
                            val length=query("SELECT length(CAST($expression AS BLOB)) FROM ${table.name} WHERE rowid=?",arrayOf(rowid.toString())).use { c ->
                                if(!c.moveToFirst()) invalid(); if(c.isNull(0)) -1 else c.getLong(0)
                            }
                            if(length<0) { if(!column.nullable) invalid(); output.writeInt(-1); continue }
                            fieldSize(length); space(root,length); output.writeInt(length.toInt())
                            var offset=0L
                            while(offset<length) {
                                check()
                                val bytes=query("SELECT substr(CAST($expression AS BLOB),?,?) FROM ${table.name} WHERE rowid=?",arrayOf((offset+1).toString(),minOf(CHUNK.toLong(),length-offset).toString(),rowid.toString())).use { c -> if(!c.moveToFirst()) invalid(); c.getBlob(0) }
                                if(bytes.isEmpty()) invalid()
                                output.write(bytes); offset+=bytes.size
                            }
                        }
                        output.flush(); file.fd.sync()
                    }
                    progress(++completed)
                }
            }
        }
        writeCounts(root,counts)
    }

    fun normalize(raw:File,tree:File,check:()->Unit,progress:(Long)->Unit) {
        val counts=counts(raw); File(tree,"resources").mkdir(); var completed=0L
        tables.forEachIndexed { ti,table ->
            File(tree,table.name).mkdir()
            for(index in 0 until counts[ti]) {
                check()
                DataInputStream(BufferedInputStream(rowFile(raw,table,index).inputStream())).use { input ->
                    FileOutputStream(rowFile(tree,table,index)).use { file ->
                        val out=DataOutputStream(BufferedOutputStream(file))
                        for(column in table.columns) {
                            val length=input.readInt()
                            if(length<0) { out.writeInt(-1); continue }
                            fieldSize(length.toLong())
                            if(column.type=="BLOB") {
                                val temp=File(tree,"resource.tmp")
                                FileOutputStream(temp).use { output -> copyExact(input,output,length,check) }
                                val reference=resource(temp,tree,check).toByteArray(Charsets.US_ASCII)
                                out.writeInt(reference.size); out.write(reference)
                            } else if(column.name=="conversationJson" || column.name=="imagesJson") {
                                val text=readString(input,length)
                                val normalized=imagesJson(text,tree,false,column.name=="conversationJson",check)
                                val bytes=normalized.toByteArray(Charsets.UTF_8); fieldSize(bytes.size.toLong()); out.writeInt(bytes.size); out.write(bytes)
                            } else { out.writeInt(length); copyExact(input,out,length,check) }
                        }
                        if(input.read()!=-1) invalid()
                        out.flush(); file.fd.sync()
                    }
                }
                progress(++completed)
            }
        }
        writeCounts(tree,counts)
    }
    private fun copyExact(input:InputStream,out:OutputStream,length:Int,check:()->Unit) {
        val buffer=ByteArray(minOf(CHUNK,maxOf(length,1))); var left=length
        while(left>0) { check(); val n=input.read(buffer,0,minOf(left,buffer.size)); if(n<=0) invalid(); out.write(buffer,0,n); left-=n }
    }
    private fun readString(input:DataInputStream,length:Int):String {
        fieldSize(length.toLong()); val bytes=ByteArray(length); input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    fun resource(file:File,tree:File,check:()->Unit):String {
        fieldSize(file.length()); if(file.length()==0L) invalid()
        val hash=digest(file,check); val target=File(tree,"resources/$hash")
        if(target.exists()) { if(target.length()!=file.length()) invalid(); file.delete() }
        else if(!file.renameTo(target)) throw IOException("Cannot store private image")
        return hash
    }
    fun image(base64:String,tree:File,check:()->Unit):String {
        fieldSize(base64.length.toLong())
        val bytes=try { Base64.getDecoder().decode(base64) } catch(_:IllegalArgumentException) { invalid() }
        val temp=File(tree,"resource.tmp"); temp.writeBytes(bytes); return resource(temp,tree,check)
    }
    private fun imageValue(value:String,tree:File,restore:Boolean,check:()->Unit):String {
        if(!restore) return image(value,tree,check)
        if(!value.matches(Regex("[0-9a-f]{64}"))) invalid()
        val file=File(tree,"resources/$value")
        if(!file.isFile || file.length()==0L) invalid()
        // Base64 expansion plus the containing field is checked against the same resident budget.
        fieldSize((file.length()+2)/3*4)
        return Base64.getEncoder().encodeToString(file.readBytes())
    }
    /** Only wrap token reads backed by imagesJson's StringReader. Resource access, space
     * checks and cancellation run outside this boundary and keep their original failures. */
    private inline fun <T> jsonToken(read:()->T):T = try { read() }
        catch(error:kotlinx.coroutines.CancellationException) { throw error }
        catch(_:IllegalStateException) { invalid() }
        catch(_:NumberFormatException) { invalid() }
        catch(_:android.util.MalformedJsonException) { invalid() }
        catch(_:EOFException) { invalid() }

    /** Stream messages, preserving all known and unknown scalar fields, roles, IDs and image order. */
    fun imagesJson(text:String,tree:File,restore:Boolean,conversation:Boolean,check:()->Unit):String {
        val output=LimitedStringWriter()
        JsonReader(StringReader(text)).use { input -> JsonWriter(output).use { out ->
            jsonToken { input.beginArray() }; out.beginArray()
            var messages=0
            while(jsonToken { input.hasNext() }) {
                check()
                if(conversation) {
                    jsonToken { input.beginObject() }; out.beginObject(); val seen=HashSet<String>(); var id=false; var role=false; var content=false
                    while(jsonToken { input.hasNext() }) {
                        val key=jsonToken { input.nextName() }; if(seen.size>=128 || !seen.add(key)) invalid(); out.name(key)
                        when(key) {
                            "id" -> { out.value(jsonToken { input.nextLong() }); id=true }
                            "role" -> { out.value(jsonToken { input.nextString() }); role=true }
                            "content" -> { out.value(jsonToken { input.nextString() }); content=true }
                            "images" -> if(jsonToken { input.peek() }==JsonToken.NULL) { jsonToken { input.nextNull() }; out.nullValue() } else { imageArray(input,out,tree,restore,check) }
                            "thinking" -> if(jsonToken { input.peek() }==JsonToken.NULL) { jsonToken { input.nextNull() }; out.nullValue() } else out.value(jsonToken { input.nextString() })
                            else -> jsonToken { transfer(input,out,0) }
                        }
                    }
                    if(!id || !role || !content) invalid()
                    jsonToken { input.endObject() }; out.endObject()
                } else { out.value(imageValue(jsonToken { input.nextString() },tree,restore,check)); if(++messages>MAX_QUESTION_IMAGES) invalid() }
            }
            jsonToken { input.endArray() }; out.endArray(); if(jsonToken { input.peek() }!=JsonToken.END_DOCUMENT) invalid()
        } }
        return output.toString()
    }
    private fun imageArray(input:JsonReader,out:JsonWriter,tree:File,restore:Boolean,check:()->Unit) {
        jsonToken { input.beginArray() }; out.beginArray(); var count=0
        while(jsonToken { input.hasNext() }) { if(++count>MAX_QUESTION_IMAGES) invalid(); out.value(imageValue(jsonToken { input.nextString() },tree,restore,check)) }
        jsonToken { input.endArray() }; out.endArray()
    }
    internal fun transfer(input:JsonReader,out:JsonWriter,depth:Int) {
        if(depth>32) invalid()
        when(input.peek()) {
            JsonToken.BEGIN_OBJECT -> { input.beginObject(); out.beginObject(); val seen=HashSet<String>(); while(input.hasNext()) { val key=input.nextName(); if(seen.size>=128 || !seen.add(key)) invalid(); out.name(key); transfer(input,out,depth+1) }; input.endObject(); out.endObject() }
            JsonToken.BEGIN_ARRAY -> { input.beginArray(); out.beginArray(); while(input.hasNext()) transfer(input,out,depth+1); input.endArray(); out.endArray() }
            JsonToken.STRING -> out.value(input.nextString())
            JsonToken.NUMBER -> out.value(java.math.BigDecimal(input.nextString()))
            JsonToken.BOOLEAN -> out.value(input.nextBoolean())
            JsonToken.NULL -> { input.nextNull(); out.nullValue() }
            else -> invalid()
        }
    }
    internal class LimitedStringWriter:Writer() {
        private val out=StringBuilder()
        override fun write(chars:CharArray,offset:Int,length:Int) { fieldSize((out.length.toLong()+length)*2); out.append(chars,offset,length) }
        override fun flush() { }
        override fun close() { }
        override fun toString()=out.toString()
    }

    private fun validateLayout(tree:File,counts:LongArray,check:()->Unit) {
        val permitted=tables.map { it.name }.toSet()+setOf("manifest","resources")
        Files.newDirectoryStream(tree.toPath()).use { entries ->
            for(entry in entries) { check(); if(entry.fileName.toString() !in permitted || Files.isSymbolicLink(entry)) invalid() }
        }
        tables.forEachIndexed { ti,table ->
            var count=0L
            val directory=File(tree,table.name)
            if(!directory.exists()) { if(counts[ti]!=0L) invalid(); return@forEachIndexed }
            if(!directory.isDirectory) invalid()
            Files.newDirectoryStream(directory.toPath()).use { entries ->
                for(entry in entries) {
                    check(); val name=entry.fileName.toString()
                    if(!name.matches(Regex("[0-9]{12}\\.row")) || !Files.isRegularFile(entry) || Files.isSymbolicLink(entry)) invalid()
                    val index=name.substringBefore('.').toLongOrNull() ?: invalid()
                    if(index>=counts[ti]) invalid(); count++
                }
            }
            if(count!=counts[ti]) invalid()
        }
        val resources=File(tree,"resources")
        if(!resources.exists()) return
        if(!resources.isDirectory) invalid()
        Files.newDirectoryStream(resources.toPath()).use { entries ->
            for(entry in entries) {
                check(); val name=entry.fileName.toString(); val file=entry.toFile()
                if(!name.matches(Regex("[0-9a-f]{64}")) || !Files.isRegularFile(entry) || Files.isSymbolicLink(entry) || file.length()==0L) invalid()
                fieldSize(file.length()); if(digest(file,check)!=name) invalid()
            }
        }
    }
    /** Reconstruct native SQLite on disk before a single main-db write; fields use 64KiB updates. */
    fun restore(tree:File,target:File,check:()->Unit,progress:(Long)->Unit):BackupStats {
        val counts=counts(tree); validateLayout(tree,counts,check); if(target.exists()) invalid(); space(tree,0); var completed=0L
        SQLiteDatabase.openOrCreateDatabase(target,null).use { stage ->
            stage.rawQuery("PRAGMA journal_mode=DELETE",null).use { it.moveToFirst() }; stage.execSQL("PRAGMA synchronous=FULL"); stage.execSQL("PRAGMA temp_store=FILE")
            tables.forEach { table -> stage.execSQL("CREATE TABLE ${table.name} (__row INTEGER PRIMARY KEY, ${table.columns.joinToString(",") { "${it.name} ${it.type}" }}, UNIQUE(${table.key}))") }
            stage.beginTransaction()
            try {
                tables.forEachIndexed { ti,table ->
                    for(index in 0 until counts[ti]) {
                        check()
                        stage.execSQL("INSERT INTO ${table.name} DEFAULT VALUES")
                        val rowid=stage.compileStatement("SELECT last_insert_rowid()").use { it.simpleQueryForLong() }
                        DataInputStream(BufferedInputStream(rowFile(tree,table,index).inputStream())).use { input ->
                            for(column in table.columns) {
                                val length=input.readInt()
                                if(length == -1) { if(!column.nullable) invalid(); continue }
                                fieldSize(length.toLong())
                                if(column.type=="BLOB") {
                                    val hash=readString(input,length)
                                    if(!hash.matches(Regex("[0-9a-f]{64}"))) invalid()
                                    val resource=File(tree,"resources/$hash")
                                    if(!resource.isFile || resource.length()==0L) invalid()
                                    fieldSize(resource.length())
                                    resource.inputStream().use { putField(stage,table,column,rowid,it,resource.length().toInt(),check) }
                                } else if(column.name=="conversationJson" || column.name=="imagesJson") {
                                    val materialized=imagesJson(readString(input,length),tree,true,column.name=="conversationJson",check).toByteArray(Charsets.UTF_8)
                                    putField(stage,table,column,rowid,materialized.inputStream(),materialized.size,check)
                                } else putField(stage,table,column,rowid,input,length,check)
                            }
                            if(input.read()!=-1) invalid()
                        }
                        progress(++completed)
                    }
                }
                validate(stage,check)
                stage.setTransactionSuccessful()
            } finally { stage.endTransaction() }
        }
        FileOutputStream(target,true).use { it.fd.sync() }
        return SQLiteDatabase.openDatabase(target.path,null,SQLiteDatabase.OPEN_READONLY).use { stage ->
            fun count(table:String)=stage.rawQuery("SELECT count(*) FROM $table",null).use { it.moveToFirst(); it.getInt(0) }
            val historyImages=stage.rawQuery("SELECT COALESCE(SUM($sessionImageExpression),0) FROM solve_sessions",null).use { it.moveToFirst(); it.getInt(0) }
            BackupStats(count("questions"),count("question_images")+historyImages,count("solve_sessions"),count("categories"),count("tags"))
        }
    }
    private fun putField(stage:SQLiteDatabase,table:Table,column:Column,rowid:Long,input:InputStream,length:Int,check:()->Unit) {
        if(column.type=="INTEGER") {
            if(length !in 1..20) invalid()
            val bytes=ByteArray(length); DataInputStream(input).readFully(bytes)
            val value=String(bytes,Charsets.US_ASCII)
            if(!value.matches(Regex("-?(0|[1-9][0-9]*)"))) invalid()
            val number=value.toLongOrNull() ?: invalid()
            stage.execSQL("UPDATE ${table.name} SET ${column.name}=? WHERE rowid=?",arrayOf(number,rowid))
            return
        }
        val decoder=if(column.type=="TEXT") Charsets.UTF_8.newDecoder() else null
        val pending=java.nio.ByteBuffer.allocate(CHUNK+4)
        val characters=java.nio.CharBuffer.allocate(CHUNK+4)
        fun validateBytes(bytes:ByteArray,size:Int,last:Boolean) {
            if(decoder==null) return
            pending.put(bytes,0,size); pending.flip()
            while(true) {
                characters.clear(); val result=decoder.decode(pending,characters,last)
                if(result.isError) invalid()
                if(!result.isOverflow) break
            }
            pending.compact()
            if(last && pending.position()!=0) invalid()
        }
        stage.execSQL("UPDATE ${table.name} SET ${column.name}=? WHERE rowid=?",arrayOf(ByteArray(0),rowid))
        var remaining=length; val buffer=ByteArray(CHUNK)
        while(remaining>0) {
            check(); val n=input.read(buffer,0,minOf(buffer.size,remaining)); if(n<=0) invalid()
            validateBytes(buffer,n,remaining==n)
            stage.execSQL("UPDATE ${table.name} SET ${column.name}=CAST(${column.name} AS BLOB)||? WHERE rowid=?",arrayOf(buffer.copyOf(n),rowid)); remaining-=n
        }
        // Concatenation's affinity is TEXT; restore exact BLOB or declared scalar type explicitly.
        stage.execSQL("UPDATE ${table.name} SET ${column.name}=CAST(${column.name} AS ${column.type}) WHERE rowid=?",arrayOf(rowid))
    }
    fun validate(stage:SQLiteDatabase,check:()->Unit) {
        fun rejectIf(sql:String) { check(); stage.rawQuery(sql,null).use { if(it.moveToFirst()) invalid() } }
        tables.forEach { table -> table.columns.filter { !it.nullable }.forEach { c -> rejectIf("SELECT 1 FROM ${table.name} WHERE ${c.name} IS NULL LIMIT 1") } }
        listOf("questions","solve_sessions").forEach { table ->
            rejectIf("SELECT 1 FROM $table WHERE length(CAST(id AS BLOB))>80 LIMIT 1")
            stage.rawQuery("SELECT id,createdAt FROM $table",null).use { c -> while(c.moveToNext()) { check(); val id=c.getString(0); if(!QuestionIdentity.valid(id) || QuestionIdentity.time(id)!=c.getLong(1)) invalid() } }
        }
        rejectIf("SELECT 1 FROM categories WHERE id<=0 UNION ALL SELECT 1 FROM tags WHERE id<=0 LIMIT 1")
        rejectIf("SELECT 1 FROM questions WHERE deleted NOT IN (0,1) OR (categoryId IS NOT NULL AND categoryId NOT IN (SELECT id FROM categories)) LIMIT 1")
        rejectIf("SELECT 1 FROM question_tags WHERE questionId NOT IN (SELECT id FROM questions) OR tagId NOT IN (SELECT id FROM tags) LIMIT 1")
        rejectIf("SELECT 1 FROM review WHERE questionId NOT IN (SELECT id FROM questions) OR intervalStep NOT BETWEEN 0 AND 6 OR reviewCount<0 LIMIT 1")
        rejectIf("SELECT 1 FROM question_images WHERE questionId NOT IN (SELECT id FROM questions) OR position<0 OR length(bytes)=0 LIMIT 1")
        rejectIf("SELECT 1 FROM question_images GROUP BY questionId,position HAVING count(*)>1 LIMIT 1")
        rejectIf("SELECT 1 FROM solve_sessions WHERE savedQuestionId IS NOT NULL AND (savedQuestionId<>id OR savedQuestionId NOT IN (SELECT id FROM questions)) LIMIT 1")
    }
}
