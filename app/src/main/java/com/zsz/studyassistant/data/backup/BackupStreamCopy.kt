package com.zsz.studyassistant.data

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Bounded byte copying. Success includes close and a full independent read-back. */
internal object BackupStreamCopy {
    fun digest(input: InputStream, check: () -> Unit = {}): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        input.use { while (true) { check(); val n = it.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) } }
        return digest.digest()
    }
    fun writeAndVerify(source: () -> InputStream, output: () -> OutputStream?, input: () -> InputStream?, check: () -> Unit = {}) {
        val expected = MessageDigest.getInstance("SHA-256")
        val target = output() ?: throw BackupWriteException("data.autoBackup.writeFailed")
        target.use { out -> source().use { src ->
            val buffer = ByteArray(64 * 1024)
            while (true) { check(); val n = src.read(buffer); if (n < 0) break; if (n > 0) { out.write(buffer, 0, n); expected.update(buffer, 0, n) } }
        } }
        check()
        val read = input() ?: throw BackupWriteException("data.autoBackup.verifyFailed")
        if (!MessageDigest.isEqual(expected.digest(), digest(read, check))) throw BackupWriteException("data.autoBackup.verifyFailed")
    }
}
