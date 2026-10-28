package com.zsz.studyassistant.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.*
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** Bak3 compatibility is progressive: one bounded record, immediately staged on disk. */
internal object BackupLegacyReader {
    private val json=Json { ignoreUnknownKeys=true }
    fun read(file:File,tree:File,check:()->Unit):Long {
        inspectHeader(file,check)
        val raw=File(tree.parentFile,"legacy-capture").apply { if(!mkdir()) throw IOException("Cannot create private legacy capture") }
        BackupTree.tables.forEach { File(raw,it.name).mkdir() }
        val counts=LongArray(BackupTree.tables.size)
        var format:String?=null; var version:Int?=null; var snapshotAt=0L
        fun row(tableName:String,values:Map<String,Any?>) {
            check()
            val ti=BackupTree.tables.indexOfFirst { it.name==tableName }; val table=BackupTree.tables[ti]
            val target=File(raw,"$tableName/${(counts[ti]++).toString().padStart(12,'0')}.row")
            DataOutputStream(BufferedOutputStream(target.outputStream())).use { output ->
                table.columns.forEach { column ->
                    val value=values[column.name]
                    val bytes=when(value) { null -> null; is ByteArray -> value; is Boolean -> (if(value) "1" else "0").toByteArray(); else -> value.toString().toByteArray(Charsets.UTF_8) }
                    if(bytes==null) { if(!column.nullable) BackupTree.invalid(); output.writeInt(-1) }
                    else { BackupTree.fieldSize(bytes.size.toLong()); output.writeInt(bytes.size); output.write(bytes) }
                }
            }
        }
        fun decodeImage(value:String):ByteArray=try { Base64.getDecoder().decode(value).also { if(it.isEmpty()) BackupTree.invalid() } } catch(_:IllegalArgumentException) { BackupTree.invalid() }
        fun record(reader:JsonReader):String {
            val writer=BackupTree.LimitedStringWriter()
            JsonWriter(writer).use { BackupTree.transfer(reader,it,0) }
            return writer.toString()
        }
        try {
            JsonReader(TokenBoundReader(strictReader(file),check)).use { input ->
                input.beginObject(); val seen=HashSet<String>()
                while(input.hasNext()) {
                    check(); val name=input.nextName(); if(seen.size>=128 || !seen.add(name)) BackupTree.invalid()
                    when(name) {
                        "format" -> format=input.nextString()
                        "version" -> version=input.nextInt()
                        "exportedAt" -> snapshotAt=input.nextLong()
                        "categories","tags","questions","questionTags","reviews","solveSessions" -> {
                            input.beginArray()
                            while(input.hasNext()) {
                                check(); val value=record(input)
                                when(name) {
                                    "categories" -> json.decodeFromString<BackupCategory>(value).let { row("categories",mapOf("id" to it.id,"name" to it.name,"createdAt" to it.createdAt)) }
                                    "tags" -> json.decodeFromString<BackupTag>(value).let { row("tags",mapOf("id" to it.id,"name" to it.name,"createdAt" to it.createdAt)) }
                                    "questionTags" -> json.decodeFromString<BackupQuestionTag>(value).let { row("question_tags",mapOf("questionId" to it.questionId,"tagId" to it.tagId)) }
                                    "reviews" -> json.decodeFromString<BackupReview>(value).let { row("review",mapOf("questionId" to it.questionId,"intervalStep" to it.intervalStep,"nextReviewAt" to it.nextReviewAt,"lastReviewedAt" to it.lastReviewedAt,"reviewCount" to it.reviewCount)) }
                                    "questions" -> json.decodeFromString<BackupQuestion>(value).let { q ->
                                        if(q.images.size>MAX_QUESTION_IMAGES || q.imageBase64!=q.images.firstOrNull()) BackupTree.invalid()
                                        row("questions",mapOf("id" to q.id,"text" to q.text,"answer" to q.answer,"createdAt" to q.createdAt,"imageBytes" to q.imageBase64?.let(::decodeImage),"conversationJson" to q.conversationJson,"deleted" to q.deleted,"categoryId" to q.categoryId))
                                        q.images.forEachIndexed { index,image -> row("question_images",mapOf("id" to (counts[3]+1),"questionId" to q.id,"position" to index,"bytes" to decodeImage(image),"source" to "import","createdAt" to q.createdAt)) }
                                    }
                                    "solveSessions" -> json.decodeFromString<BackupSolveSession>(value).let { s ->
                                        if(s.images.size>MAX_QUESTION_IMAGES || s.imageBase64!=s.images.firstOrNull()) BackupTree.invalid()
                                        row("solve_sessions",mapOf("id" to s.id,"createdAt" to s.createdAt,"updatedAt" to s.updatedAt,"source" to s.source,"text" to s.text,"answer" to s.answer,"conversationJson" to s.conversationJson,"imageBytes" to s.imageBase64?.let(::decodeImage),"imagesJson" to if(s.images.size>1) json.encodeToString(s.images) else null,"savedQuestionId" to s.savedQuestionId))
                                    }
                                }
                            }
                            input.endArray()
                        }
                        else -> JsonWriter(object:Writer() { override fun write(c:CharArray,o:Int,n:Int) {} ; override fun close() {}; override fun flush() {} }).apply { isLenient=true }.use { BackupTree.transfer(input,it,0) }
                    }
                }
                input.endObject(); if(input.peek()!=JsonToken.END_DOCUMENT) BackupTree.invalid()
            }
            if(format!=BackupFile.FORMAT || version==null) BackupTree.invalid()
            if(version!=BackupFile.VERSION) throw BackupValidationException("data.backupVersionUnsupported")
            BackupTree.writeCounts(raw,counts)
            File(tree,"resources").mkdir()
            BackupTree.normalize(raw,tree,check) { }
            if(!raw.deleteRecursively()) throw IOException("Cannot release private capture")
            return snapshotAt
        } catch(e:kotlinx.coroutines.CancellationException) { throw e }
        catch(e:BackupValidationException) { throw e }
        catch(_:java.nio.charset.CharacterCodingException) { BackupTree.invalid() }
        catch(e:IOException) { throw e }
        catch(e:SecurityException) { throw e }
        catch(_:Exception) { BackupTree.invalid() }
    }
    /** Header fields may follow the payload: inspect without decoding any business DTO. */
    private fun inspectHeader(file:File,check:()->Unit) {
        var format:String?=null; var version:Int?=null
        try {
            JsonReader(TokenBoundReader(strictReader(file),check)).use { input ->
                input.beginObject(); val seen=HashSet<String>()
                while(input.hasNext()) {
                    check(); val name=input.nextName()
                    if(seen.size>=128 || !seen.add(name)) BackupTree.invalid()
                    when(name) {
                        "format" -> format=input.nextString()
                        "version" -> version=input.nextInt()
                        else -> JsonWriter(object:Writer() {
                            override fun write(chars:CharArray,offset:Int,length:Int) { }
                            override fun flush() { }
                            override fun close() { }
                        }).apply { isLenient=true }.use { BackupTree.transfer(input,it,0) }
                    }
                }
                input.endObject(); if(input.peek()!=JsonToken.END_DOCUMENT) BackupTree.invalid()
            }
            if(format!=BackupFile.FORMAT || version==null) BackupTree.invalid()
            if(version!=BackupFile.VERSION) throw BackupValidationException("data.backupVersionUnsupported")
        } catch(error:kotlinx.coroutines.CancellationException) { throw error }
        catch(error:BackupValidationException) { throw error }
        catch(_:java.nio.charset.CharacterCodingException) { BackupTree.invalid() }
        catch(error:IOException) { throw error }
        catch(error:SecurityException) { throw error }
        catch(_:Exception) { BackupTree.invalid() }
    }
    private fun strictReader(file:File):Reader=InputStreamReader(file.inputStream(),Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))
    /** Reject oversized string and primitive tokens before the parser can allocate them. */
    private class TokenBoundReader(private val source:Reader,private val check:()->Unit):Reader() {
        private var quoted=false; private var escaped=false; private var size=0L; private var primitiveSize=0L
        override fun read(buffer:CharArray,offset:Int,length:Int):Int {
            check(); val count=source.read(buffer,offset,minOf(length,4096))
            for(index in offset until offset+maxOf(count,0)) {
                val char=buffer[index]
                if(quoted) {
                    BackupTree.fieldSize(++size*2)
                    if(escaped) escaped=false else if(char=='\\') escaped=true else if(char=='"') { quoted=false; size=0 }
                } else if(char=='"') { quoted=true; size=0; primitiveSize=0 }
                else if(char.isWhitespace() || char in "{}[],:" ) primitiveSize=0
                else BackupTree.fieldSize(++primitiveSize*2)
            }
            return count
        }
        override fun close()=source.close()
    }
}
