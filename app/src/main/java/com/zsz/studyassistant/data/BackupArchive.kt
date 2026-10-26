package com.zsz.studyassistant.data

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Bak4: independent native ZIP volumes carrying a bounded, ordered file-frame stream.
 * Only private, immutable staging trees belong here; this class never opens the live database.
 * The content hash excludes volume boundaries, UUID and time. The commit root binds the exact
 * headers and per-volume directories, including every block's length, sequence and SHA-256.
 */
object BackupArchive {
    const val VERSION = 4
    const val DEFAULT_VOLUME_BYTES: Long = 256L * 1024 * 1024
    private const val MAX_BLOCK = 1024 * 1024
    private const val MAX_PATH = 4096
    private const val MAX_PARTS = 4096
    private const val MAX_BLOCKS_PER_PART = 4092
    private const val MAX_FILE_BYTES = 1024L * 1024 * 1024 * 1024
    private const val MAX_TREE_BYTES = 16 * MAX_FILE_BYTES
    private const val RESERVE = 1024L * 1024
    private const val BLOCK_MAGIC = 0x53414234
    private const val DIRECTORY_MAGIC = 0x53414434
    private const val COMMIT_MAGIC = 0x53414334
    private const val FORMAT = "study-assistant-backup"
    data class ArchiveSet(val setId: String, val snapshotAt: Long, val files: List<File>, val contentHash: String)
    data class ArchiveInfo(val setId: String, val snapshotAt: Long, val contentHash: String)
    data class PartInfo(val setId: String, val partIndex: Int, val snapshotAt: Long)

    /** Bounded ZIP/header probe only. Completeness and all payload hashes require readParts. */
    fun inspectPart(file: File, check: () -> Unit = {}): PartInfo {
        try {
            check(); rejectLinks(file); inspectZip(file, check)
            return ZipFile(file).use { zip ->
                var header: ZipEntry? = null
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    check()
                    val entry = entries.nextElement()
                    if (entry.name == "header.bin") {
                        if (header != null) invalid()
                        header = entry
                    }
                }
                val value = parseHeader(entryBytes(zip, header ?: invalid(), 4096, check))
                PartInfo(value.setId, value.partIndex, value.snapshotAt)
            }
        } catch (error: Throwable) {
            when (error) {
                is BackupValidationException -> throw error
                is java.util.concurrent.CancellationException -> throw error
                is EOFException, is java.util.zip.ZipException, is java.nio.charset.CharacterCodingException,
                is IllegalArgumentException -> invalid()
                else -> throw error
            }
        }
    }
    private data class Header(val setId: String, val partIndex: Int, val snapshotAt: Long)
    private data class Descriptor(val sequence: Long, val length: Int, val hash: ByteArray)
    private data class Part(val file: File, val header: Header)
    private fun invalid(): Nothing = throw BackupValidationException("data.backupInvalid")
    private fun tooLarge(): Nothing = throw BackupValidationException("data.backupTooLarge")
    private fun digest() = MessageDigest.getInstance("SHA-256")
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun encoded(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use(block) }.toByteArray()
    private fun blockName(sequence: Long) = "block-%012d.bin".format(java.util.Locale.ROOT, sequence)
    private fun headerBytes(header: Header) = encoded { out ->
        out.writeUTF(FORMAT); out.writeInt(VERSION); out.writeUTF(header.setId); out.writeInt(header.partIndex); out.writeLong(header.snapshotAt)
    }
    private fun parseHeader(bytes: ByteArray): Header = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readUTF() != FORMAT) invalid()
        if (input.readInt() != VERSION) throw BackupValidationException("data.backupVersionUnsupported")
        val id = input.readUTF()
        if (UUID.fromString(id).toString() != id) invalid()
        val index = input.readInt(); val at = input.readLong()
        if (index !in 0 until MAX_PARTS || input.read() != -1) invalid()
        Header(id, index, at)
    }
    private fun directoryBytes(descriptors: List<Descriptor>) = encoded { out ->
        out.writeInt(DIRECTORY_MAGIC); out.writeInt(descriptors.size)
        descriptors.forEach { out.writeLong(it.sequence); out.writeInt(it.length); out.write(it.hash) }
    }
    private fun validPath(path: String): ByteArray {
        val bytes = path.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_PATH || decodePath(bytes) != path) invalid()
        if (path.startsWith('/') || path.contains('\\') || path.any { it.code < 32 || it == ':' || it == '\u007f' }) invalid()
        val segments = path.split('/')
        if (segments.size > 64 || segments.any { segment ->
                segment.isEmpty() || segment == "." || segment == ".." || segment.endsWith('.') || segment.endsWith(' ') ||
                    segment.toByteArray(Charsets.UTF_8).size > 255 || segment.substringBefore('.').uppercase(java.util.Locale.ROOT).let {
                    it in setOf("CON", "PRN", "AUX", "NUL") || it.matches(Regex("(COM|LPT)[1-9]")) }
            }) invalid()
        return bytes
    }
    private fun decodePath(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun space(directory: File, amount: Long) {
        if (amount < 0 || directory.usableSpace < amount + RESERVE) tooLarge()
    }
    private fun removeOwned(file: File) {
        if (Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(file.toPath()).use { children -> children.forEach { removeOwned(it.toFile()) } }
        }
        Files.deleteIfExists(file.toPath())
    }
    private fun clean(file: File) { try { removeOwned(file) } catch (_: IOException) { /* Do not hide the original failure. */ } }
    private fun rejectLinks(file: File) { if (Files.isSymbolicLink(file.toPath())) invalid() }

    /** External merge sort keeps at most 1024 paths and 64 run heads in memory. */
    private fun manifest(source: File, work: File, check: () -> Unit): File {
        var runs = 0
        val paths = ArrayList<String>(1024)
        fun runFile(pass: Int, index: Int) = File(work, "paths-$pass-$index")
        fun flush() {
            if (paths.isEmpty()) return
            space(work, paths.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() + 4 })
            paths.sort()
            DataOutputStream(runFile(0, runs++).outputStream().buffered()).use { output -> paths.forEach { path ->
                val bytes = validPath(path); output.writeInt(bytes.size); output.write(bytes)
            } }
            paths.clear()
        }
        fun visit(directory: File, depth: Int) {
            check(); if (depth > 64) invalid()
            Files.newDirectoryStream(directory.toPath()).use { children -> children.forEach { child ->
                check(); rejectLinks(child.toFile())
                when {
                    Files.isDirectory(child, NOFOLLOW_LINKS) -> visit(child.toFile(), depth + 1)
                    Files.isRegularFile(child, NOFOLLOW_LINKS) -> {
                        val path = source.toPath().relativize(child).toString().replace(File.separatorChar, '/')
                        validPath(path); paths.add(path); if (paths.size == 1024) flush()
                    }
                    else -> invalid()
                }
            } }
        }
        visit(source, 0); flush()
        if (runs == 0) { runFile(0, 0).createNewFile(); return runFile(0, 0) }
        var pass = 0
        while (runs > 1) {
            var nextRuns = 0
            for (start in 0 until runs step 64) {
                check()
                space(work, (start until minOf(start + 64, runs)).sumOf { runFile(pass, it).length() })
                val inputs = (start until minOf(start + 64, runs)).map { DataInputStream(runFile(pass, it).inputStream().buffered()) }
                data class Head(val index: Int, val path: String)
                val queue = PriorityQueue<Head>(compareBy { it.path })
                try {
                    inputs.forEachIndexed { index, input -> nextPath(input)?.let { queue.add(Head(index, it)) } }
                    DataOutputStream(runFile(pass + 1, nextRuns++).outputStream().buffered()).use { output ->
                        while (queue.isNotEmpty()) {
                            check(); val head = queue.remove(); val bytes = validPath(head.path)
                            output.writeInt(bytes.size); output.write(bytes)
                            nextPath(inputs[head.index])?.let { queue.add(Head(head.index, it)) }
                        }
                    }
                } finally { inputs.forEach { it.close() } }
                for (index in start until minOf(start + 64, runs)) Files.delete(runFile(pass, index).toPath())
            }
            pass++; runs = nextRuns
        }
        return runFile(pass, 0)
    }
    private fun nextPath(input: DataInputStream): String? {
        val first = input.read(); if (first < 0) return null
        val length = (first shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        if (length !in 1..MAX_PATH) invalid()
        return ByteArray(length).also { input.readFully(it) }.let(::decodePath)
    }
    private class Counter(output: OutputStream) : FilterOutputStream(output) {
        var count = 0L
        override fun write(value: Int) { out.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length); count += length }
    }

    fun writeTree(sourceDir: File, outputDir: File, snapshotAt: Long = System.currentTimeMillis(),
                  volumeBytes: Long = DEFAULT_VOLUME_BYTES, check: () -> Unit = {}, progress: (Long) -> Unit = {}): ArchiveSet {
        if (volumeBytes !in 16_384..DEFAULT_VOLUME_BYTES) tooLarge()
        rejectLinks(sourceDir); rejectLinks(outputDir)
        if (!sourceDir.isDirectory) invalid()
        if (!outputDir.isDirectory && !outputDir.mkdirs()) throw IOException("Cannot create archive output")
        val source = sourceDir.canonicalFile; val output = outputDir.canonicalFile
        if (output.toPath().startsWith(source.toPath())) invalid()
        val work = Files.createTempDirectory(output.toPath(), ".sab-work-").toFile()
        val files = ArrayList<File>()
        val id = UUID.randomUUID().toString(); val content = digest(); val root = digest()
        var zip: ZipOutputStream? = null
        var counter: Counter? = null
        var descriptors = ArrayList<Descriptor>()
        var sequence = 0L; var fileCount = 0L; var total = 0L
        val blockLimit = minOf(MAX_BLOCK.toLong(), maxOf(8192L, volumeBytes / 4)).toInt()
        var block = ByteArrayOutputStream(blockLimit)
        var frames = DataOutputStream(block)
        fun resetBlock() { block = ByteArrayOutputStream(blockLimit); frames = DataOutputStream(block); frames.writeInt(BLOCK_MAGIC); frames.writeLong(sequence) }
        fun entry(name: String, bytes: ByteArray) {
            check(); space(output, bytes.size.toLong() + 65536)
            zip!!.putNextEntry(ZipEntry(name).apply { time = 0L }); zip!!.write(bytes); zip!!.closeEntry()
        }
        fun openPart() {
            if (files.size >= MAX_PARTS) tooLarge()
            check(); val file = File(output, "$id-part%04d.sab".format(java.util.Locale.ROOT, files.size + 1))
            val stream = Files.newOutputStream(file.toPath(), CREATE_NEW)
            files.add(file); counter = Counter(stream); zip = ZipOutputStream(counter)
            descriptors = ArrayList()
            val bytes = headerBytes(Header(id, files.lastIndex, snapshotAt)); entry("header.bin", bytes); root.update(bytes)
        }
        fun closeDirectory() { val bytes = directoryBytes(descriptors); entry("directory.bin", bytes); root.update(bytes) }
        fun closePart() { closeDirectory(); zip!!.close(); zip = null }
        fun flushBlock() {
            if (block.size() == 12) return
            check()
            if (descriptors.isNotEmpty() && (counter!!.count + block.size() + descriptors.size * 44L + 65536 > volumeBytes || descriptors.size >= MAX_BLOCKS_PER_PART)) {
                closePart(); openPart()
            }
            val bytes = block.toByteArray(); entry(blockName(sequence), bytes)
            descriptors.add(Descriptor(sequence, bytes.size, digest().digest(bytes))); sequence++; resetBlock()
        }
        fun fit(length: Int) { if (block.size() + length > blockLimit) flushBlock(); if (block.size() + length > blockLimit) tooLarge() }
        try {
            val sorted = manifest(source, work, check)
            openPart(); resetBlock()
            val chunk = ByteArray(minOf(65536, blockLimit / 2))
            DataInputStream(sorted.inputStream().buffered()).use { paths ->
                while (true) {
                    check(); val path = nextPath(paths) ?: break; val pathBytes = validPath(path)
                    val file = File(source, path); rejectLinks(file)
                    if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) invalid()
                    val length = file.length(); if (length > MAX_FILE_BYTES || total > MAX_TREE_BYTES - length) tooLarge()
                    val start = encoded { it.writeByte(1); it.writeInt(pathBytes.size); it.write(pathBytes); it.writeLong(length) }
                    fit(start.size); frames.write(start)
                    content.update(encoded { it.writeInt(pathBytes.size); it.write(pathBytes); it.writeLong(length) })
                    var offset = 0L
                    file.inputStream().buffered().use { input ->
                        while (offset < length) {
                            check(); val size = input.read(chunk, 0, minOf(chunk.size.toLong(), length - offset).toInt())
                            if (size < 0) invalid()
                            fit(13 + size); frames.writeByte(2); frames.writeLong(offset); frames.writeInt(size); frames.write(chunk, 0, size)
                            content.update(chunk, 0, size); offset += size; total += size; progress(total)
                        }
                        if (input.read() != -1) invalid()
                    }
                    fileCount++
                }
            }
            flushBlock(); closeDirectory()
            val hash = content.digest()
            val commit = encoded { it.writeInt(COMMIT_MAGIC); it.writeInt(files.size); it.writeLong(sequence); it.writeLong(fileCount); it.writeLong(total); it.write(hash); it.write(root.digest()) }
            entry("commit.bin", commit); zip!!.close(); zip = null
            files.forEach { file -> check(); FileChannel.open(file.toPath(), WRITE).use { it.force(true) } }
            check()
            return ArchiveSet(id, snapshotAt, files.toList(), hash.hex())
        } catch (error: Throwable) {
            try { zip?.close() } catch (_: Throwable) { }
            files.forEach(::clean)
            throw error
        } finally { clean(work) }
    }

    /** Bound central-directory allocation and reject links, extra bytes and inconsistent local ZIP
     * records before opening ZipFile. Bak4 intentionally accepts the native DEFLATE envelope only:
     * UTF-8 names, signed 32-bit data descriptors, no ZIP64, comments, encryption or spanning.
     */
    private fun inspectZip(file: File, check: () -> Unit) {
        RandomAccessFile(file, "r").use { input ->
            fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
            val length = input.length()
            if (length < 22 || length > DEFAULT_VOLUME_BYTES + 2L * MAX_BLOCK) invalid()
            input.seek(length - 22)
            if (u32() != 0x06054b50L || u16() != 0 || u16() != 0) invalid()
            val count = u16(); if (count !in 2..4096 || u16() != count) invalid()
            val centralLength = u32(); val centralStart = u32()
            if (u16() != 0 || centralLength > MAX_BLOCK || centralStart + centralLength != length - 22) invalid()
            var localEnd = 0L; input.seek(centralStart)
            repeat(count) {
                check()
                if (u32() != 0x02014b50L) invalid()
                u16(); u16(); val flags = u16(); val method = u16(); u16(); u16()
                val crc = u32(); val compressed = u32(); val raw = u32()
                val nameLength = u16(); val extraLength = u16(); val commentLength = u16()
                val disk = u16(); u16(); val attributes = u32(); val localStart = u32()
                if (flags != 0x0808 || method != ZipEntry.DEFLATED || nameLength !in 1..64 || extraLength > 64 || commentLength != 0 || disk != 0 ||
                    ((attributes ushr 16) and 0xf000) == 0xa000L || localStart != localEnd || raw > MAX_BLOCK) invalid()
                val name = ByteArray(nameLength); input.readFully(name)
                input.skipBytes(extraLength); val nextCentral = input.filePointer
                input.seek(localStart)
                if (u32() != 0x04034b50L) invalid()
                u16(); if (u16() != flags || u16() != method) invalid()
                u16(); u16(); u32(); u32(); u32()
                if (u16() != nameLength) invalid()
                val localExtra = u16(); if (localExtra > 64) invalid()
                val localName = ByteArray(nameLength); input.readFully(localName); if (!localName.contentEquals(name)) invalid()
                val descriptorStart = input.filePointer + localExtra + compressed
                if (descriptorStart + 16 > centralStart) invalid()
                input.seek(descriptorStart)
                if (u32() != 0x08074b50L || u32() != crc || u32() != compressed || u32() != raw) invalid()
                localEnd = input.filePointer; input.seek(nextCentral)
            }
            if (input.filePointer != centralStart + centralLength || localEnd != centralStart) invalid()
        }
    }
    private fun entryBytes(zip: ZipFile, entry: ZipEntry, maximum: Int, check: () -> Unit): ByteArray {
        if (entry.isDirectory || entry.method != ZipEntry.DEFLATED || entry.size !in 0..maximum.toLong()) invalid()
        val output = ByteArrayOutputStream(minOf(entry.size.toInt(), maximum)); val crc = CRC32(); val buffer = ByteArray(65536)
        zip.getInputStream(entry).use { input ->
            while (true) {
                check(); val count = input.read(buffer); if (count < 0) break
                if (count > maximum - output.size()) tooLarge()
                output.write(buffer, 0, count); crc.update(buffer, 0, count)
            }
        }
        if (output.size().toLong() != entry.size || crc.value != entry.crc) invalid()
        return output.toByteArray()
    }

    fun readParts(parts: List<File>, targetDir: File, check: () -> Unit = {}, progress: (Long) -> Unit = {}): ArchiveInfo {
        // Nothing inside a pre-existing nonempty target is ever owned by this invocation.
        rejectLinks(targetDir)
        if (targetDir.exists()) {
            if (!targetDir.isDirectory) invalid()
            Files.newDirectoryStream(targetDir.toPath()).use { if (it.iterator().hasNext()) invalid() }
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) throw IOException("Cannot create import staging")
        var owned = false
        var current: OutputStream? = null
        try {
            if (parts.isEmpty() || parts.size > MAX_PARTS) invalid()
            val ordered = arrayOfNulls<Part>(MAX_PARTS)
            var reference: Header? = null
            for (file in parts) {
                check(); rejectLinks(file); inspectZip(file, check)
                val header = ZipFile(file).use { zip ->
                    val entry = zip.getEntry("header.bin") ?: invalid()
                    parseHeader(entryBytes(zip, entry, 4096, check))
                }
                reference?.let { if (it.setId != header.setId || it.snapshotAt != header.snapshotAt) invalid() }
                reference = header
                if (header.partIndex >= parts.size || ordered[header.partIndex] != null) invalid()
                ordered[header.partIndex] = Part(file, header)
            }
            val root = digest(); val content = digest()
            var sequence = 0L; var fileCount = 0L; var total = 0L; var remaining = 0L; var offset = 0L; var previousPath = ""
            var committed = false
            for (index in parts.indices) {
                check(); val part = ordered[index] ?: invalid()
                ZipFile(part.file).use { zip ->
                    val entries = zip.entries()
                    fun next(): ZipEntry { if (!entries.hasMoreElements()) invalid(); return entries.nextElement() }
                    val first = next(); if (first.name != "header.bin") invalid()
                    val header = entryBytes(zip, first, 4096, check)
                    if (parseHeader(header) != part.header) invalid()
                    root.update(header)
                    val descriptors = ArrayList<Descriptor>()
                    var entry = next()
                    while (entry.name.startsWith("block-")) {
                        if (descriptors.size >= MAX_BLOCKS_PER_PART || entry.name != blockName(sequence)) invalid()
                        val bytes = entryBytes(zip, entry, MAX_BLOCK, check)
                        descriptors.add(Descriptor(sequence, bytes.size, digest().digest(bytes)))
                        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                            if (input.readInt() != BLOCK_MAGIC || input.readLong() != sequence) invalid()
                            while (input.available() > 0) {
                                check()
                                when (input.readUnsignedByte()) {
                                    1 -> {
                                        if (remaining != 0L || current != null) invalid()
                                        val pathLength = input.readInt(); if (pathLength !in 1..MAX_PATH) invalid()
                                        val pathBytes = ByteArray(pathLength); input.readFully(pathBytes)
                                        val path = decodePath(pathBytes); validPath(path)
                                        if (path <= previousPath) invalid()
                                        val length = input.readLong(); if (length < 0 || length > MAX_FILE_BYTES || total > MAX_TREE_BYTES - length) tooLarge()
                                        val target = File(targetDir, path)
                                        if (!target.canonicalFile.toPath().startsWith(targetDir.canonicalFile.toPath())) invalid()
                                        space(targetDir, minOf(length, MAX_BLOCK.toLong()))
                                        owned = true
                                        if (target.parentFile.exists() && !target.parentFile.isDirectory) invalid()
                                        if (!target.parentFile.isDirectory && !target.parentFile.mkdirs()) throw IOException("Cannot create staging path")
                                        current = BufferedOutputStream(Files.newOutputStream(target.toPath(), CREATE_NEW))
                                        content.update(encoded { it.writeInt(pathBytes.size); it.write(pathBytes); it.writeLong(length) })
                                        remaining = length; offset = 0; previousPath = path; fileCount++
                                        if (length == 0L) { current!!.close(); current = null }
                                    }
                                    2 -> {
                                        if (current == null || input.readLong() != offset) invalid()
                                        val length = input.readInt()
                                        if (length !in 1..65536 || length > remaining || length > input.available()) invalid()
                                        space(targetDir, length.toLong())
                                        val data = ByteArray(length); input.readFully(data); current!!.write(data); content.update(data)
                                        remaining -= length; offset += length; total += length; progress(total)
                                        if (remaining == 0L) { current!!.close(); current = null }
                                    }
                                    else -> invalid()
                                }
                            }
                        }
                        sequence++; entry = next()
                    }
                    if (entry.name != "directory.bin") invalid()
                    val directory = entryBytes(zip, entry, MAX_BLOCK, check)
                    if (!directory.contentEquals(directoryBytes(descriptors))) invalid()
                    root.update(directory)
                    if (index == parts.lastIndex) {
                        val commitEntry = next(); if (commitEntry.name != "commit.bin") invalid()
                        val commit = entryBytes(zip, commitEntry, 4096, check)
                        DataInputStream(ByteArrayInputStream(commit)).use { input ->
                            if (input.readInt() != COMMIT_MAGIC || input.readInt() != parts.size || input.readLong() != sequence ||
                                input.readLong() != fileCount || input.readLong() != total || remaining != 0L || current != null) invalid()
                            val expectedContent = ByteArray(32); input.readFully(expectedContent)
                            val expectedRoot = ByteArray(32); input.readFully(expectedRoot)
                            val actualContent = content.digest()
                            if (!MessageDigest.isEqual(actualContent, expectedContent) || !MessageDigest.isEqual(root.digest(), expectedRoot) || input.read() != -1) invalid()
                            committed = true
                            if (entries.hasMoreElements()) invalid()
                            check()
                            return ArchiveInfo(part.header.setId, part.header.snapshotAt, actualContent.hex())
                        }
                    } else if (entries.hasMoreElements()) invalid()
                }
            }
            if (!committed) invalid()
            invalid()
        } catch (error: Throwable) {
            try { current?.close() } catch (_: Throwable) { }
            if (owned) {
                try { Files.newDirectoryStream(targetDir.toPath()).use { children -> children.forEach { clean(it.toFile()) } } }
                catch (_: IOException) { /* Keep the original failure. */ }
            }
            when (error) {
                is BackupValidationException -> throw error
                is java.util.concurrent.CancellationException -> throw error
                is EOFException, is java.util.zip.ZipException, is java.nio.charset.CharacterCodingException,
                is java.nio.file.FileAlreadyExistsException, is IllegalArgumentException -> invalid()
                else -> throw error
            }
        }
    }
}
