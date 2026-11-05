package com.zsz.studyassistant.data

import java.io.InputStream

suspend fun readChunkedText(length: Int?, part: suspend (Int,Int) -> String?): String? {
    if (length == null) return null
    val result = StringBuilder(minOf(length, 65536))
    var position = 1
    while(position <= length) { result.append(checkNotNull(part(position,65536))); position += 65536 }
    return result.toString()
}

/** SQLite substr offsets are one-based; each BLOB chunk stays well below CursorWindow size. */
suspend fun readChunkedBlob(length: Int?, part: suspend (Int,Int) -> ByteArray?): ByteArray? {
    if(length==null) return null
    require(length>=0 && length.toLong()<=minOf(32L*1024*1024,Runtime.getRuntime().maxMemory()/4)) { "Image exceeds safe read size" }
    val result=ByteArray(length)
    var position=0
    while(position<length) {
        val count=minOf(65536,length-position)
        val bytes=checkNotNull(part(position+1,count))
        check(bytes.size==count) { "Incomplete image read" }
        bytes.copyInto(result,position)
        position+=count
    }
    return result
}

object BackupLimits {
    val maxBytes: Long get() = minOf(48L*1024*1024, Runtime.getRuntime().maxMemory()/8)
    fun checkEncoded(text: String, maximum: Long = maxBytes) {
        var bytes=0L;var index=0
        while(index<text.length) {
            val c=text[index++]
            bytes+=when {
                c.code<0x80 -> 1
                c.code<0x800 -> 2
                Character.isHighSurrogate(c) && index<text.length && Character.isLowSurrogate(text[index]) -> { index++;4 }
                else -> 3
            }
            if(bytes>maximum) throw BackupValidationException("data.backupTooLarge")
        }
    }
    fun checkEstimate(raw: Long) { if (raw > maxBytes / 3) throw BackupValidationException("data.backupTooLarge") }
}
class LimitedBackupInput(private val source: InputStream, private val maximum: Long = BackupLimits.maxBytes) : InputStream() {
    private var count = 0L
    private fun account(amount: Int): Int {
        if(amount > 0) { count += amount; if(count > maximum) throw BackupValidationException("data.backupTooLarge") }
        return amount
    }
    override fun read(): Int { val value = source.read(); if(value >= 0) account(1); return value }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = account(source.read(buffer,offset,length))
    override fun close() { source.close() }
}
