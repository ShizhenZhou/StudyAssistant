package com.zsz.studyassistant.data

import android.content.Context
import androidx.compose.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import kotlinx.coroutines.*

/** Owns only temporary manual-backup state. Database transactions stay in their existing coordinator. */
internal class ManualBackupState(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "backup-manual")
    var export by mutableStateOf<BackupArchive.ArchiveSet?>(null)
    var preparedImport by mutableStateOf<BackupRepository.PreparedImport?>(null)
    var progress by mutableStateOf<String?>(null)
    var readJob: Job? = null
    var exportJob: Job? = null
    var exportDirectory: File? = null

    fun workspace() = File(root, UUID.randomUUID().toString())

    suspend fun cleanAbandoned(current: File) {
        val keep = setOfNotNull(current, exportDirectory, preparedImport?.directory)
        withContext(Dispatchers.IO) {
            if (!root.isDirectory) return@withContext
            val protectedPaths = keep.map { it.canonicalFile }.toSet()
            Files.newDirectoryStream(root.toPath()).use { entries ->
                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    val candidate = entry.toFile()
                    if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || runCatching { UUID.fromString(candidate.name) }.isFailure) continue
                    if (candidate.canonicalFile !in protectedPaths && System.currentTimeMillis() - candidate.lastModified() > 86_400_000)
                        remove(candidate)
                }
            }
        }
    }

    suspend fun remove(directory: File?) = withContext(NonCancellable + Dispatchers.IO) {
        directory?.let {
            val parent = root.canonicalFile
            val target = it.canonicalFile
            check(target != parent && target.toPath().startsWith(parent.toPath()))
            check(runCatching { UUID.fromString(target.name) }.isSuccess)
            target.deleteRecursively()
        }
        Unit
    }

    fun cancel() { readJob?.cancel(); exportJob?.cancel() }
}
