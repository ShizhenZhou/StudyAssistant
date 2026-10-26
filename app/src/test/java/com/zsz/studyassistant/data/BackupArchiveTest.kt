package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun directory() = temporary.newFolder()
    private fun fixture(root: File, large: Int = 150_000) {
        root.resolve("records/中文 空.txt").apply { parentFile.mkdirs(); writeText("exact\u0000text\n😀") }
        root.resolve("empty").writeBytes(byteArrayOf())
        root.resolve("resources/image.bin").apply { parentFile.mkdirs(); writeBytes(ByteArray(large).also { Random(72).nextBytes(it) }) }
        repeat(100) { root.resolve("records/r$it").writeText("small record $it") }
    }
    private fun assertTree(source: File, target: File) {
        val sourceFiles = source.walkTopDown().filter { it.isFile }.map { it.relativeTo(source).invariantSeparatorsPath }.toSet()
        val targetFiles = target.walkTopDown().filter { it.isFile }.map { it.relativeTo(target).invariantSeparatorsPath }.toSet()
        assertEquals(sourceFiles, targetFiles)
        sourceFiles.forEach { assertArrayEquals(source.resolve(it).readBytes(), target.resolve(it).readBytes()) }
    }
    private fun rejected(parts: List<File>, target: File = directory()) {
        try { BackupArchive.readParts(parts, target); fail("Accepted invalid set") }
        catch (_: BackupValidationException) { }
        assertEquals("Failure must remove owned extracted files", 0, target.listFiles()!!.size)
    }
    private fun rewrite(file: File, transform: (String, ByteArray) -> Pair<String, ByteArray>?) : File {
        val result = File(directory(), "changed.sab")
        ZipFile(file).use { input -> ZipOutputStream(result.outputStream()).use { output ->
            input.entries().asSequence().forEach { entry ->
                val changed = transform(entry.name, input.getInputStream(entry).use { it.readBytes() })
                if (changed != null) { output.putNextEntry(ZipEntry(changed.first)); output.write(changed.second); output.closeEntry() }
            }
        } }
        return result
    }
    @Test fun singleVolumeExactRoundTripAndStableContentHash() {
        val source = directory(); fixture(source)
        val first = BackupArchive.writeTree(source, directory(), snapshotAt = 123)
        val second = BackupArchive.writeTree(source, directory(), snapshotAt = 999)
        assertEquals(1, first.files.size)
        assertEquals(first.contentHash, second.contentHash)
        val target = directory(); val info = BackupArchive.readParts(first.files, target)
        assertEquals(first.setId, info.setId); assertEquals(123L, info.snapshotAt)
        assertEquals(first.contentHash, info.contentHash); assertTree(source, target)
    }
    @Test fun emptyTreeStillNeedsCommit() {
        val set = BackupArchive.writeTree(directory(), directory(), snapshotAt = 0)
        assertEquals(set.contentHash, BackupArchive.readParts(set.files, directory()).contentHash)
        rejected(listOf(rewrite(set.files.single()) { name, bytes -> if (name == "commit.bin") null else name to bytes }))
    }
    @Test fun independentVolumesAllowRenamingAndArbitrarySelectionOrder() {
        val source = directory(); fixture(source, 700_000)
        val set = BackupArchive.writeTree(source, directory(), volumeBytes = 32_768)
        assertTrue(set.files.size > 2)
        assertEquals(BackupArchive.writeTree(source, directory()).contentHash, set.contentHash)
        set.files.forEach { file -> ZipFile(file).use { assertNotNull(it.getEntry("header.bin")) }; assertTrue(file.length() < 100_000) }
        val renamed = set.files.mapIndexed { i, f -> f.copyTo(File(directory(), "unrelated-$i.bin")) }.reversed()
        val target = directory(); BackupArchive.readParts(renamed, target); assertTree(source, target)
        rejected(renamed.drop(1)); rejected(renamed.dropLast(1)); rejected(renamed + renamed.first())
        val other = BackupArchive.writeTree(source, directory(), volumeBytes = 32_768)
        rejected(set.files.dropLast(1) + other.files.last())
    }
    @Test fun corruptPayloadAndTruncatedZipAreRejected() {
        val source = directory(); fixture(source)
        val set = BackupArchive.writeTree(source, directory())
        rejected(listOf(rewrite(set.files.single()) { name, bytes ->
            name to if (name.startsWith("block-")) bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } else bytes
        }))
        val truncated = File(directory(), "truncated.sab"); truncated.writeBytes(set.files.single().readBytes().dropLast(12).toByteArray())
        rejected(listOf(truncated))
        rejected(listOf(rewrite(set.files.single()) { name, bytes -> if (name == "header.bin") "../header.bin" to bytes else name to bytes }))
    }
    @Test fun cancellationCleansOnlyNewFilesAndNeverTouchesExistingOutput() {
        val source = directory(); fixture(source)
        val output = directory(); output.resolve("keep.sab").writeText("old backup")
        var checks = 0
        try { BackupArchive.writeTree(source, output, check = { if (++checks > 5) throw CancellationException() }); fail() }
        catch (_: CancellationException) { }
        assertEquals(listOf("keep.sab"), output.listFiles()!!.map { it.name })
        try { BackupArchive.writeTree(source, output, progress = { throw CancellationException() }); fail() }
        catch (_: CancellationException) { }
        assertEquals(listOf("keep.sab"), output.listFiles()!!.map { it.name })
        val set = BackupArchive.writeTree(source, directory())
        val target = directory(); checks = 0
        try { BackupArchive.readParts(set.files, target, check = { if (++checks > 4) throw CancellationException() }); fail() }
        catch (_: CancellationException) { }
        assertEquals(0, target.listFiles()!!.size)
        try { BackupArchive.readParts(set.files, target, progress = { throw CancellationException() }); fail() }
        catch (_: CancellationException) { }
        assertEquals(0, target.listFiles()!!.size)
        target.resolve("existing").writeText("keep")
        try { BackupArchive.readParts(set.files, target); fail() } catch (_: BackupValidationException) { }
        assertEquals("keep", target.resolve("existing").readText())
    }
    @Test fun highCompressionTextIsAllowedAndSmallFilesAreAggregated() {
        val source = directory(); source.resolve("large.txt").writeText("a".repeat(5_000_000))
        val set = BackupArchive.writeTree(source, directory(), volumeBytes = 262_144)
        val target = directory(); BackupArchive.readParts(set.files, target); assertTree(source, target)
        val small = directory(); repeat(2400) { small.resolve("f$it").writeText("x") }
        val packed = BackupArchive.writeTree(small, directory())
        ZipFile(packed.files.single()).use { assertTrue(it.size() < 20) }
        val restored = directory(); BackupArchive.readParts(packed.files, restored); assertTree(small, restored)
    }
    @Test fun frameTraversalUnknownVersionAndCommitTamperingAreRejected() {
        val source = directory(); source.resolve("aa/x").apply { parentFile.mkdirs(); writeText("content") }
        val file = BackupArchive.writeTree(source, directory()).files.single()
        rejected(listOf(rewrite(file) { name, bytes ->
            name to if (name.startsWith("block-")) bytes.copyOf().also { "../x".toByteArray().copyInto(it, 17) } else bytes
        }))
        val unknown = rewrite(file) { name, bytes -> name to if (name == "header.bin") bytes.copyOf().also {
            java.nio.ByteBuffer.wrap(it).putInt(2 + "study-assistant-backup".length, 99)
        } else bytes }
        try { BackupArchive.readParts(listOf(unknown), directory()); fail() }
        catch (error: BackupValidationException) { assertEquals("data.backupVersionUnsupported", error.messageKey) }
        for (changed in listOf("directory.bin", "commit.bin")) {
            rejected(listOf(rewrite(file) { name, bytes -> name to if (name == changed) bytes.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            } else bytes }))
        }
        rejected(listOf(rewrite(file) { name, bytes -> name to if (name.startsWith("block-")) ByteArray(1024 * 1024 + 1) else bytes }))
    }
    @Test fun inconsistentLocalHeaderAndSymlinkAttributesAreRejected() {
        val source = directory(); source.resolve("x").writeText("data")
        val file = BackupArchive.writeTree(source, directory()).files.single()
        val malformed = File(directory(), "malformed.sab")
        val bytes = file.readBytes()
        malformed.writeBytes(bytes.copyOf().also { it[30] = 'x'.code.toByte() }) // local filename differs from central filename
        rejected(listOf(malformed))
        val central = bytes.indices.first { index -> index + 3 < bytes.size && bytes[index] == 0x50.toByte() &&
            bytes[index + 1] == 0x4b.toByte() && bytes[index + 2] == 1.toByte() && bytes[index + 3] == 2.toByte() }
        malformed.writeBytes(bytes.copyOf().also { it[central + 40] = 0xff.toByte(); it[central + 41] = 0xa1.toByte() })
        rejected(listOf(malformed))
    }
}
