package com.zsz.studyassistant.data

import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.DigestOutputStream
import java.security.MessageDigest

internal class BackupWriteException(val messageKey: String) : Exception(messageKey)

/** Close the writer, then compare the full read-back digest without duplicating the JSON in memory. */
internal object VerifiedBackupWriter {
    fun writeAndVerify(text: String, output: () -> OutputStream?, input: () -> InputStream?,
                       checkActive: () -> Unit = {}) {
        val written = MessageDigest.getInstance("SHA-256")
        val stream = output() ?: throw BackupWriteException("data.autoBackup.writeFailed")
        DigestOutputStream(stream, written).use { digest ->
            OutputStreamWriter(digest, Charsets.UTF_8).use { writer ->
                var offset = 0
                while (offset < text.length) {
                    checkActive()
                    val length = minOf(8192, text.length - offset)
                    writer.write(text, offset, length)
                    offset += length
                }
            }
        }
        checkActive()
        val actual = MessageDigest.getInstance("SHA-256")
        val read = input() ?: throw BackupWriteException("data.autoBackup.verifyFailed")
        read.use {
            val buffer = ByteArray(8192)
            while (true) {
                checkActive()
                val n = it.read(buffer)
                if (n < 0) break
                if (n > 0) actual.update(buffer, 0, n)
            }
        }
        if (!MessageDigest.isEqual(written.digest(), actual.digest())) {
            throw BackupWriteException("data.autoBackup.verifyFailed")
        }
    }
}
