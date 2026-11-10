package com.zsz.studyassistant.data

import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Optional public release metadata; the enclosing release supplies the exact target identity. */
data class UpdateDeltaInfo(
    val format: String,
    val baseSha256: String,
    val baseSize: Long,
    val patchName: String,
    val patchUrl: String,
    val patchSha256: String,
    val patchSize: Long,
)

/** Bounded GDIFF v4 interpreter (https://www.w3.org/TR/NOTE-gdiff-19970901).
 * It only copies byte ranges and literal bytes, never interprets paths or executable commands.
 */
internal object UpdateDelta {
    const val FORMAT = "gdiff4-gzip"
    const val MAX_APK_SIZE = 512L * 1024 * 1024

    fun matches(file: File, size: Long, sha256: String, check: () -> Unit = {}): Boolean =
        file.isFile && file.length() == size && digest(file, check).equals(sha256, ignoreCase = true)

    fun digest(file: File, check: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun applyGdiff(base: File, patch: File, output: File, targetSize: Long, check: () -> Unit = {}) {
        check()
        require(targetSize in 1..MAX_APK_SIZE)
        RandomAccessFile(base, "r").use { source ->
            DataInputStream(GZIPInputStream(patch.inputStream().buffered(), 64 * 1024)).use { input ->
                require(input.readInt() == 0xd1ffd1ff.toInt() && input.readUnsignedByte() == 4)
                output.outputStream().buffered().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var commands = 0
                    while (true) {
                        check()
                        val command = input.readUnsignedByte()
                        if (command == 0) {
                            require(written == targetSize && input.read() == -1)
                            break
                        }
                        require(++commands <= 1_000_000)
                        val offset = when (command) {
                            in 249..251 -> input.readUnsignedShort().toLong()
                            in 252..254 -> input.readInt().toLong()
                            255 -> input.readLong()
                            else -> -1L
                        }
                        val length = when (command) {
                            in 1..246 -> command
                            247, 250, 253 -> input.readUnsignedShort()
                            249, 252 -> input.readUnsignedByte()
                            else -> input.readInt()
                        }
                        require(length > 0 && length.toLong() <= targetSize - written)
                        if (command >= 249) {
                            require(offset >= 0 && offset <= source.length() && length.toLong() <= source.length() - offset)
                            source.seek(offset)
                        }
                        var remaining = length
                        while (remaining > 0) {
                            check()
                            val count = minOf(remaining, buffer.size)
                            if (command >= 249) source.readFully(buffer, 0, count) else input.readFully(buffer, 0, count)
                            target.write(buffer, 0, count)
                            remaining -= count
                        }
                        written += length
                    }
                }
            }
        }
    }
}
