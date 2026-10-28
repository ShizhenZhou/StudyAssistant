package com.zsz.studyassistant.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class CaptureFlowState(
    val paths: List<String>, val index: Int, val grade: Boolean, val expect: Int,
    val needsMore: Boolean, val results: List<ByteArray>, val questionId: String?, val createdAt: Long
)

/** Independent, app-private checkpoint. Call disk operations on an IO dispatcher. */
internal class CaptureFlowStore(context: Context) {
    private class Coordinator { val revision = AtomicLong(); val io = Any() }
    private val root = File(context.noBackupFilesDir, "capture-flow-v1")
    private val cache = context.cacheDir
    private val coordinator = coordinators.computeIfAbsent(root.canonicalPath) { Coordinator() }
    private val metadataPath = File(root, "state.json")
    private val metadata = PrivateJsonFile(metadataPath, 16 * 1024)

    /** Intentionally does not acquire the disk lock, inspect files, or wait for a copy. */
    fun reserve(): Long = coordinator.revision.incrementAndGet()

    fun write(state: CaptureFlowState, ticket: Long, check: () -> Unit = {}): Boolean = guarded {
        synchronized(coordinator.io) {
            if (ticket != coordinator.revision.get()) return@synchronized false
            validate(state)
            safeRoot(create = true)
            val previous = metadata.read()
            val generation = UUID.randomUUID().toString()
            val directory = File(root, generation)
            if (!directory.mkdir()) throw IOException("Cannot create capture generation")
            var committed = false
            var metadataAttempted = false
            try {
                fun active() { check(); if (ticket != coordinator.revision.get()) throw StaleWrite() }
                val paths = state.paths.mapIndexed { index, path ->
                    active()
                    val source = sourceFile(path)
                    val name = "source-$index.bin"
                    FileOutputStream(File(directory, name)).use { output ->
                        source.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                active()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > SOURCE_LIMIT) throw IOException("Capture source exceeds limit")
                                output.write(buffer, 0, count)
                            }
                            if (total == 0L) throw IOException("Empty capture source")
                        }
                        output.fd.sync()
                    }
                    name
                }
                val results = state.results.mapIndexed { index, bytes ->
                    active()
                    val name = "result-$index.bin"
                    FileOutputStream(File(directory, name)).use { it.write(bytes); it.fd.sync() }
                    name
                }
                val json = JSONObject().put("version", 1).put("generation", generation)
                    .put("paths", JSONArray(paths)).put("index", state.index).put("grade", state.grade)
                    .put("expect", state.expect).put("needsMore", state.needsMore)
                    .put("results", JSONArray(results)).put("questionId", state.questionId ?: JSONObject.NULL)
                    .put("createdAt", state.createdAt).toString()
                active()
                syncDirectory(directory)
                syncDirectory(root)
                active()
                metadataAttempted = true
                metadata.write(json)
                // reserve() must stay nonblocking, including during AtomicFile's fsync.
                // If invalidated in that window, restore the preceding checkpoint under the IO lock.
                if (ticket != coordinator.revision.get()) {
                    if (previous == null) metadata.clear() else metadata.write(previous)
                    return@synchronized false
                }
                committed = true
                true
            } catch (error: Exception) {
                if (metadataAttempted) {
                    try { if (previous == null) metadata.clear() else metadata.write(previous) }
                    catch (restoreError: Exception) { error.addSuppressed(restoreError) }
                }
                if (error !is StaleWrite) throw error
                false
            } finally {
                // A failed pointer replacement/rollback can leave either metadata version on disk.
                // Keep both generations until explicit clear if any metadata IO was attempted.
                if (!committed && !metadataAttempted) deleteGeneration(directory)
            }
        }
    }

    fun read(): CaptureFlowState? = guarded {
        synchronized(coordinator.io) {
            safeRoot(create = false)
            val text = metadata.read() ?: return@synchronized null
            val json = JSONObject(text)
            if (integer(json, "version") != 1L) throw IOException("Unsupported capture checkpoint")
            val generation = json.getString("generation")
            if (!GENERATION.matches(generation)) throw IOException("Invalid capture generation")
            val directory = File(root, generation)
            safeChild(root, directory, directory = true)
            val names = names(json.getJSONArray("paths"), "source", 1, 2)
            val resultNames = names(json.getJSONArray("results"), "result", 0, 2)
            val paths = names.map { name ->
                val file = File(directory, name)
                safeChild(directory, file)
                if (file.length() !in 1..SOURCE_LIMIT) throw IOException("Invalid capture source length")
                file.absolutePath
            }
            val results = resultNames.map { name -> boundedResult(File(directory, name), directory) }
            if (!json.has("questionId")) throw IOException("Missing capture identity")
            val questionId = if (json.isNull("questionId")) null else json.get("questionId") as? String
                ?: throw IOException("Invalid capture identity")
            val state = CaptureFlowState(paths, int(json, "index"), boolean(json, "grade"),
                int(json, "expect"), boolean(json, "needsMore"), results, questionId, integer(json, "createdAt"))
            validate(state)
            state
        }
    }

    fun clear(ticket: Long) = guarded {
        synchronized(coordinator.io) {
            if (ticket != coordinator.revision.get()) return@synchronized
            safeRoot(create = false)
            metadata.clear()
            // Only this store's UUID generations; never traverse or remove source/cache folders.
            root.listFiles()?.filter { GENERATION.matches(it.name) }?.forEach { deleteGeneration(it) }
        }
    }

    private fun validate(state: CaptureFlowState) {
        if (state.paths.size !in 1..2 || state.expect !in 1..2 || state.paths.size > state.expect ||
            state.results.size > 2 || state.results.any { it.isEmpty() || it.size > RESULT_LIMIT } ||
            state.createdAt <= 0 || state.index !in 0..state.paths.size || state.results.size >= state.expect ||
            (!state.needsMore && (state.index >= state.paths.size || state.results.size != state.index)) ||
            (state.needsMore && (state.index < state.paths.lastIndex || state.results.size > state.paths.size)) ||
            (!state.grade && state.questionId == null) ||
            (state.questionId != null && (!QuestionIdentity.valid(state.questionId) ||
                QuestionIdentity.time(state.questionId) != state.createdAt))) {
            throw IOException("Invalid capture checkpoint fields")
        }
    }

    private fun safeRoot(create: Boolean) {
        if (Files.isSymbolicLink(root.toPath())) throw IOException("Capture root is a symbolic link")
        if (create && !root.isDirectory && !root.mkdirs()) throw IOException("Cannot create capture root")
        if (root.exists() && !root.isDirectory) throw IOException("Invalid capture root")
        if (root.canonicalFile.parentFile != root.parentFile!!.canonicalFile) throw IOException("Invalid capture root")
        listOf(metadataPath, File(metadataPath.path + ".bak"), File(metadataPath.path + ".new")).forEach {
            if (Files.isSymbolicLink(it.toPath())) throw IOException("Invalid capture metadata path")
        }
    }

    private fun sourceFile(path: String): File {
        val file = File(path)
        if (!file.isAbsolute) throw IOException("Capture source must be absolute")
        val base = listOf(cache, root).firstOrNull {
            file.absoluteFile.toPath().normalize().startsWith(it.absoluteFile.toPath().normalize())
        } ?: throw IOException("Capture source outside private storage")
        var current = file.absoluteFile
        val basePath = base.absoluteFile.toPath().normalize()
        if (file.toPath() != file.toPath().normalize()) throw IOException("Noncanonical capture source")
        while (current.toPath() != basePath) {
            if (Files.isSymbolicLink(current.toPath())) throw IOException("Symbolic capture source")
            current = current.parentFile ?: throw IOException("Invalid capture source")
        }
        if (Files.isSymbolicLink(base.toPath()) || !file.isFile ||
            !file.canonicalFile.toPath().startsWith(base.canonicalFile.toPath()) ||
            file.length() !in 1..SOURCE_LIMIT) throw IOException("Invalid capture source")
        return file
    }

    private fun safeChild(parent: File, file: File, directory: Boolean = false) {
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != parent.canonicalFile ||
            (if (directory) !file.isDirectory else !file.isFile)) throw IOException("Missing or unsafe capture resource")
    }

    private fun boundedResult(file: File, directory: File): ByteArray {
        safeChild(directory, file)
        if (file.length() !in 1..RESULT_LIMIT.toLong()) throw IOException("Invalid capture result length")
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream(minOf(file.length().toInt(), 64 * 1024))
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > RESULT_LIMIT) throw IOException("Capture result exceeds limit")
                output.write(buffer, 0, count)
            }
            if (total == 0) throw IOException("Empty capture result")
            output.toByteArray()
        }
    }

    private fun names(array: JSONArray, prefix: String, min: Int, max: Int): List<String> {
        if (array.length() !in min..max) throw IOException("Invalid capture resource count")
        return (0 until array.length()).map { index ->
            val expected = "$prefix-$index.bin"
            if (array.get(index) != expected) throw IOException("Invalid capture resource name")
            expected
        }
    }
    private fun boolean(json: JSONObject, key: String): Boolean = json.get(key) as? Boolean
        ?: throw IOException("Invalid capture boolean")
    private fun integer(json: JSONObject, key: String): Long = when (val value = json.get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IOException("Invalid capture integer")
    }
    private fun int(json: JSONObject, key: String): Int {
        val value = integer(json, key)
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw IOException("Capture integer overflow")
        return value.toInt()
    }
    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private fun deleteGeneration(directory: File) {
        if (!GENERATION.matches(directory.name) || Files.isSymbolicLink(directory.toPath()) ||
            directory.canonicalFile.parentFile != root.canonicalFile || !directory.isDirectory) return
        // Generations are flat. Refuse unknown nested directories rather than following them.
        directory.listFiles()?.forEach { file ->
            if (!file.isDirectory || Files.isSymbolicLink(file.toPath())) file.delete()
        }
        directory.delete()
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (e: IOException) { throw e }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw IOException("Capture checkpoint unavailable", e) }
    private class StaleWrite : RuntimeException()
    companion object {
        private val coordinators = ConcurrentHashMap<String, Coordinator>()
        private val GENERATION = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        private const val SOURCE_LIMIT = 64L * 1024 * 1024
        private const val RESULT_LIMIT = 16 * 1024 * 1024
    }
}
