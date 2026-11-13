package com.zsz.studyassistant.data

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlinx.serialization.json.*

/** Exact APK byte reconstruction. No ZIP paths are extracted and no APK is re-signed. */
internal object UpdateArchiveDelta {
    const val FORMAT = "apk-gdiff1-gzip"
    private const val MAX_VIRTUAL = 256L * 1024 * 1024
    private const val MAX_ENTRY = 64L * 1024 * 1024
    private const val BUFFER = 64 * 1024

    private data class Range(val offset: Long, val compressed: Long, val raw: Long, val level: Int)
    private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let {
        require(!it.isString && Regex("[0-9]+").matches(it.content))
        it.long
    }

    private fun ranges(root: JsonObject, key: String, size: Long, target: Boolean): List<Range> {
        val entries = root.getValue(key).jsonArray
        require(entries.size <= 16)
        var end = 0L
        return entries.map { entry ->
            val item = entry.jsonObject
            val offset = item.number("offset")
            val compressed = item.number("compressedSize")
            val raw = item.number("rawSize")
            val level = if (target) item.number("level").also { require(it in 1..9) }.toInt() else 0
            require(offset >= end && offset <= size && compressed in 1..(size - offset) && raw in 1..MAX_ENTRY)
            end = offset + compressed
            Range(offset, compressed, raw, level)
        }
    }

    private fun expandedSize(size: Long, ranges: List<Range>): Long =
        (size + ranges.sumOf { it.raw - it.compressed }).also { require(it in 1..MAX_VIRTUAL) }

    private fun scratchFor(output: File) = File(checkNotNull(output.parentFile), "${output.name}.archive-delta")
    internal fun cleanup(output: File) { scratchFor(output).deleteRecursively() }

    fun apply(base: File, patch: File, output: File, targetSize: Long, check: () -> Unit) {
        check()
        require(targetSize in 1..UpdateDelta.MAX_APK_SIZE)
        // Same private destination identifies leftovers after process death. Downloads
        // serialize reconstruction, so resume/cancel can remove only this task's scratch.
        val scratch = scratchFor(output)
        var complete = false
        try {
            DataInputStream(GZIPInputStream(patch.inputStream().buffered(), BUFFER)).use { input ->
                require(input.readInt() == 0x53415a31) // SAZ1
                val headerSize = input.readInt().also { require(it in 1..BUFFER) }
                val header = ByteArray(headerSize).also(input::readFully)
                val root = Json.parseToJsonElement(header.toString(Charsets.UTF_8)).jsonObject
                val baseSize = root.number("baseSize")
                require(baseSize == base.length() && baseSize in 1..UpdateDelta.MAX_APK_SIZE)
                require(root.number("targetSize") == targetSize)
                val from = ranges(root, "baseRanges", baseSize, false)
                val to = ranges(root, "targetRanges", targetSize, true)
                require(to.isNotEmpty())
                val expandedBase = expandedSize(baseSize, from)
                val expandedTarget = expandedSize(targetSize, to)
                check()
                if (scratch.exists()) require(scratch.deleteRecursively())
                require(scratch.mkdir())
                require(scratch.usableSpace >= expandedBase + expandedTarget + targetSize + 4L * 1024 * 1024)
                val before = File(scratch, "base")
                val after = File(scratch, "target")
                base.inputStream().buffered().use { source -> before.outputStream().buffered().use { dest ->
                    var previous = 0L
                    for (range in from) {
                        copy(source, dest, range.offset - previous, check)
                        inflate(source, dest, range, check)
                        previous = range.offset + range.compressed
                    }
                    copy(source, dest, baseSize - previous, check)
                    require(source.read() == -1)
                } }
                require(before.length() == expandedBase)
                UpdateDelta.applyGdiffStream(before, input, after, expandedTarget, check)
                after.inputStream().buffered().use { source -> output.outputStream().buffered().use { dest ->
                    var previous = 0L
                    for (range in to) {
                        copy(source, dest, range.offset - previous, check)
                        deflate(source, dest, range, check)
                        previous = range.offset + range.compressed
                    }
                    copy(source, dest, targetSize - previous, check)
                    require(source.read() == -1)
                } }
                check()
                require(output.length() == targetSize)
                // Existing download pipeline verifies the final SHA and original signing identity.
                complete = true
            }
        } finally {
            if (!complete) output.delete()
            scratch.deleteRecursively()
        }
    }

    private fun copy(source: InputStream, dest: OutputStream, length: Long, check: () -> Unit) {
        val buffer = ByteArray(BUFFER)
        var remaining = length
        require(remaining >= 0)
        while (remaining > 0) {
            check()
            val count = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            require(count > 0)
            dest.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun inflate(source: InputStream, dest: OutputStream, range: Range, check: () -> Unit) {
        val inflater = Inflater(true)
        val packed = ByteArray(BUFFER); val raw = ByteArray(BUFFER)
        var remaining = range.compressed
        var produced = 0L
        try {
            while (!inflater.finished()) {
                check()
                if (inflater.needsInput()) {
                    require(remaining > 0)
                    val count = source.read(packed, 0, minOf(remaining, packed.size.toLong()).toInt())
                    require(count > 0)
                    inflater.setInput(packed, 0, count); remaining -= count
                }
                val count = inflater.inflate(raw)
                require(produced + count <= range.raw)
                dest.write(raw, 0, count); produced += count
                require(count > 0 || inflater.finished() || (inflater.needsInput() && !inflater.needsDictionary()))
            }
            require(produced == range.raw && remaining == 0L && inflater.remaining == 0)
        } finally { inflater.end() }
    }

    private fun deflate(source: InputStream, dest: OutputStream, range: Range, check: () -> Unit) {
        val deflater = Deflater(range.level, true)
        val raw = ByteArray(BUFFER); val packed = ByteArray(BUFFER)
        var remaining = range.raw
        var produced = 0L
        fun drain() {
            check()
            val count = deflater.deflate(packed)
            require(produced + count <= range.compressed)
            dest.write(packed, 0, count); produced += count
            require(count > 0 || deflater.needsInput() || deflater.finished())
        }
        try {
            while (remaining > 0) {
                check()
                val count = source.read(raw, 0, minOf(remaining, raw.size.toLong()).toInt())
                require(count > 0)
                deflater.setInput(raw, 0, count); remaining -= count
                while (!deflater.needsInput()) drain()
            }
            deflater.finish()
            while (!deflater.finished()) drain()
            require(produced == range.compressed)
        } finally { deflater.end() }
    }
}
