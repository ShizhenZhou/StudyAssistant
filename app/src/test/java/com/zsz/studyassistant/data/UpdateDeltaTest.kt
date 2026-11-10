package com.zsz.studyassistant.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateDeltaTest {
    private fun patch(commands: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use {
            it.writeInt(0xd1ffd1ff.toInt()); it.writeByte(4); it.commands()
        }
        return bytes.toByteArray()
    }

    private fun withFiles(block: (File, File, File) -> Unit) {
        val dir = Files.createTempDirectory("update-delta-fixture").toFile()
        try { block(File(dir, "base").apply { writeText("ABCDEFG") }, File(dir, "patch"), File(dir, "out")) }
        finally { dir.deleteRecursively() }
    }

    @Test fun standardCopyAndLiteralInstructionsReconstructExactBytes() = withFiles { base, file, out ->
        file.writeBytes(patch {
            writeByte(249); writeShort(0); writeByte(2)
            writeByte(2); writeBytes("XY")
            writeByte(250); writeShort(2); writeShort(2)
            writeByte(255); writeLong(1); writeInt(4)
            writeByte(0)
        })
        UpdateDelta.applyGdiff(base, file, out, 10)
        assertArrayEquals("ABXYCDBCDE".toByteArray(), out.readBytes())
    }

    @Test fun invalidStreamsCannotEscapeBoundsOrProducePartialSuccess() = withFiles { base, file, out ->
        val invalid = listOf(
            patch { writeByte(249); writeShort(6); writeByte(2); writeByte(0) },
            patch { writeByte(255); writeLong(-1); writeInt(1); writeByte(0) },
            patch { writeByte(248); writeInt(-1); writeByte(0) },
            patch { writeByte(248); writeInt(Int.MAX_VALUE); writeByte(0) },
            patch { writeByte(2); writeBytes("AB"); writeByte(0) },
            patch { writeByte(1); writeByte(65) },
            patch { writeByte(1); writeByte(65); writeByte(0); writeByte(1) },
            patch { writeByte(247); writeShort(0); writeByte(0) },
        )
        invalid.forEach {
            file.writeBytes(it)
            assertThrows(Exception::class.java) { UpdateDelta.applyGdiff(base, file, out, 1) }
        }
    }

    @Test fun compressedTruncationAndWrongVersionAreRejected() = withFiles { base, file, out ->
        val valid = patch { writeByte(1); writeByte(65); writeByte(0) }
        file.writeBytes(valid.copyOf(valid.size - 5))
        assertThrows(Exception::class.java) { UpdateDelta.applyGdiff(base, file, out, 1) }
        file.writeBytes(ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { it.write(byteArrayOf(0xd1.toByte(), -1, 0xd1.toByte(), -1, 5, 0)) }
        }.toByteArray())
        assertThrows(Exception::class.java) { UpdateDelta.applyGdiff(base, file, out, 1) }
    }
}
