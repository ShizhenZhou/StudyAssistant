package com.zsz.studyassistant.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class BackupFolderOpenIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName == "com.zsz.studyassistant.integration")
    }
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FSyntheticBackup")
    private class Launcher(base: Context, private val reject: Int) : ContextWrapper(base) {
        val calls = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            calls += intent
            if (calls.size <= reject) throw ActivityNotFoundException("Synthetic unavailable handler")
        }
    }
    @Test fun folderViewUsesGrantedSafUriWithoutFilePathsOrWritePermission() {
        val launch = Launcher(context, 0)
        assertTrue(openBackupFolder(launch, tree))
        val call = launch.calls.single()
        assertEquals(Intent.ACTION_VIEW, call.action)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, call.type)
        assertEquals("primary:Documents/SyntheticBackup", DocumentsContract.getDocumentId(call.data!!))
        assertEquals("content", call.data!!.scheme)
        assertTrue(call.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, call.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    @Test fun unsupportedFolderViewFallsBackToSystemPickerAtTheSameDirectory() {
        val launch = Launcher(context, 1)
        assertTrue(openBackupFolder(launch, tree))
        assertEquals(2, launch.calls.size)
        val fallback = launch.calls.last()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, fallback.action)
        @Suppress("DEPRECATION")
        assertEquals(launch.calls.first().data, fallback.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
    }
    @Test fun noHandlerOrMissingFolderReturnsFailureWithoutCrashing() {
        val launch = Launcher(context, 2)
        assertFalse(openBackupFolder(launch, tree))
        assertFalse(openBackupFolder(launch, null))
        assertEquals(2, launch.calls.size)
    }
}
