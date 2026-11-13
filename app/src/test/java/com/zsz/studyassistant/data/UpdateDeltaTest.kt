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
    @Test fun directDeltaWinsOtherwiseChooseCheapestExactChainBelowFullSize() {
        fun hash(c: Char) = c.toString().repeat(64)
        fun edge(from: Char, to: Char, bytes: Long) = UpdateDeltaStep(
            UpdateDeltaInfo(UpdateDelta.FORMAT, hash(from), 100, "$from-$to.gdiff.gz",
                "https://github.com/${UpdateChecker.REPO}/releases/download/v1.0.0/$from-$to.gdiff.gz", hash('e'), bytes),
            hash(to), 100, "v1.0.0")
        val chain = listOf(edge('a', 'b', 20), edge('b', 'd', 30), edge('a', 'c', 40), edge('c', 'd', 40))
        fun plan(edges: List<UpdateDeltaStep>) = UpdateDeltaPlanner.find(hash('a'), 100, hash('d'), 100, edges)
        org.junit.Assert.assertEquals(50L, plan(chain)!!.downloadSize)
        org.junit.Assert.assertEquals(90L, plan(chain + edge('a', 'd', 90))!!.downloadSize)
        org.junit.Assert.assertNull(plan(listOf(edge('a', 'b', 50), edge('b', 'd', 50))))
        org.junit.Assert.assertNull(plan(listOf(edge('b', 'd', 30))))
        org.junit.Assert.assertNull(plan(listOf(edge('a', 'b', 20), edge('b', 'd', 30).let {
            it.copy(delta = it.delta.copy(baseSize = 101))
        })))
        org.junit.Assert.assertEquals(50L, plan(chain + edge('b', 'a', 1))!!.downloadSize)
        val newer = edge('a', 'd', 60).let { it.copy(delta = it.delta.copy(format = UpdateArchiveDelta.FORMAT)) }
        org.junit.Assert.assertEquals(60L, plan(chain + edge('a', 'd', 90) + newer)!!.downloadSize)
        val mixed = chain.take(2).mapIndexed { i, it ->
            if (i == 1) it.copy(delta = it.delta.copy(format = UpdateArchiveDelta.FORMAT)) else it
        }
        org.junit.Assert.assertEquals(listOf(UpdateDelta.FORMAT, UpdateArchiveDelta.FORMAT),
            plan(mixed)!!.steps.map { it.delta.format })
    }

    @Test fun cancellationInterruptsReconstructionAndHashing() = withFiles { base, file, out ->
        base.writeBytes(ByteArray(256 * 1024))
        file.writeBytes(patch { writeByte(254); writeInt(0); writeInt(256 * 1024); writeByte(0) })
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            UpdateDelta.applyGdiff(base, file, out, base.length()) {
                if (++checks == 3) throw java.util.concurrent.CancellationException()
            }
        }
        org.junit.Assert.assertTrue(out.length() < base.length())
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            UpdateDelta.matches(base, base.length(), "0".repeat(64)) { throw java.util.concurrent.CancellationException() }
        }
    }
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
