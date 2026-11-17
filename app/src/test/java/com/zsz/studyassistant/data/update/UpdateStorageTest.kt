package com.zsz.studyassistant.data

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class UpdateStorageTest {
    @Test fun downloadBudgetCountsRemainingBytesAndBothCopiesOnSharedStorage() {
        val partial = File.createTempFile("update-space-", ".part")
        try {
            partial.writeBytes(ByteArray(40))
            val destination = File(partial.parentFile, "space-destination")
            val required = 60L + 100L + 100L + UpdateStorage.RESERVE
            UpdateStorage.requireDownloadSpace(partial, destination, 100, true) { required }
            assertThrows(UpdateStorage.InsufficientSpace::class.java) {
                UpdateStorage.requireDownloadSpace(partial, destination, 100, true) { required - 1 }
            }
            assertEquals(40L, partial.length())
            // Removable storage needs only its handoff copy; private storage retains the other two.
            UpdateStorage.requireDownloadSpace(partial, destination, 100, false) {
                (if (it == destination) 100L else 160L) + UpdateStorage.RESERVE
            }
        } finally { partial.delete() }
    }

    @Test fun storageFailureClassificationSurvivesWrappingAndCloseFailures() {
        assertTrue(UpdateStorage.isInsufficient(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(UpdateStorage.isInsufficient(IOException("copy failed", UpdateStorage.InsufficientSpace())))
        assertTrue(UpdateStorage.isInsufficient(IOException("close failed").apply {
            addSuppressed(IOException("No space left on device"))
        }))
        assertFalse(UpdateStorage.isInsufficient(IOException("connection timed out")))
        assertFalse(UpdateStorage.isInsufficient(IOException("Permission denied")))
        assertFalse(UpdateStorage.isInsufficient(IllegalArgumentException("invalid patch")))
    }
}
