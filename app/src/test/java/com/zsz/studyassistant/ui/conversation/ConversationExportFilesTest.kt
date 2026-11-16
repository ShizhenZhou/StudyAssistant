package com.zsz.studyassistant.ui

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationExportFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cleanupPreservesActiveTaskAndUnownedFiles() {
        val root = temporary.newFolder("exports")
        val active = java.io.File(root, UUID.randomUUID().toString()).apply { mkdir() }
        val orphan = java.io.File(root, UUID.randomUUID().toString()).apply { mkdir() }
        val unknown = java.io.File(root, "user-file.pdf").apply { writeText("keep") }
        val uuidFile = java.io.File(root, UUID.randomUUID().toString()).apply { writeText("not a task folder") }
        java.io.File(active, "messages.json").writeText("active")
        java.io.File(orphan, "output").mkdir()
        java.io.File(orphan, "output/export.pdf").writeText("orphan")
        ConversationExportFiles.cleanupInactive(root, active.name)
        assertTrue(java.io.File(active, "messages.json").isFile)
        assertTrue(unknown.isFile)
        assertTrue(uuidFile.isFile)
        assertFalse(orphan.exists())
        // Missing/corrupt journal has no active task; repeat cleanup is safe.
        ConversationExportFiles.cleanupInactive(root, null)
        ConversationExportFiles.cleanupInactive(root, null)
        assertFalse(active.exists())
        assertTrue(unknown.isFile)
    }

    @Test fun taskIdsCannotEscapePrivateRoot() {
        val root = temporary.newFolder("exports")
        val outside = temporary.newFile("keep.pdf")
        for (id in listOf("../keep.pdf", outside.absolutePath, "..", ".", "1-1-1-1-1")) {
            assertFalse(ConversationExportFiles.isTaskId(id))
            assertThrows(IllegalArgumentException::class.java) { ConversationExportFiles.deleteTask(root, id) }
        }
        assertTrue(outside.exists())
    }

    @Test fun cleanupDoesNotFollowLinksToExternalFiles() {
        val root = temporary.newFolder("exports")
        val task = java.io.File(root, UUID.randomUUID().toString()).apply { mkdir() }
        val external = temporary.newFolder("user-destination")
        val keep = java.io.File(external, "keep.pdf").apply { writeText("user export") }
        try { Files.createSymbolicLink(java.io.File(task, "link").toPath(), external.toPath()) }
        catch (error: Exception) { org.junit.Assume.assumeNoException(error) }
        ConversationExportFiles.cleanupInactive(root, null)
        assertFalse(task.exists())
        assertEquals("user export", keep.readText())
    }
}
