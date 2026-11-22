package com.zsz.studyassistant.ui

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Only private UUID task folders are owned here. Never traverse a symbolic link. Call on IO. */
internal object ConversationExportFiles {
    fun cleanupInactive(root: File, activeId: String?) {
        taskFolders(root).filter { it.name != activeId }.forEach { runCatching { deleteTask(root, it.name) } }
    }

    fun discardUnrestored(root: File) = taskFolders(root).forEach { deleteTask(root, it.name) }

    private fun taskFolders(root: File): List<File> {
        if (Files.notExists(root.toPath(), NOFOLLOW_LINKS)) return emptyList()
        check(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        return checkNotNull(root.listFiles()).filter { isTaskId(it.name) && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
    }

    fun isTaskId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun deleteTask(root: File, id: String) {
        require(isTaskId(id))
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) return
        val path = File(root, id).toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

internal fun conversationExportName(input: String, fallback: String): String {
    val cleaned = input.trim().replace(Regex("(?i)\\.(pdf|png)$"), "")
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.')
    val bounded = cleaned.codePoints().limit(72).toArray().let { String(it, 0, it.size) }.trimEnd(' ', '.')
    if (bounded.isBlank()) return fallback
    return if (Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$").matches(bounded)) "SA-$bounded" else bounded
}
