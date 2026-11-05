package com.zsz.studyassistant.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BackupFolderAccessIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName == "com.zsz.studyassistant.integration")
    }
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val tree get() = Uri.parse("content://${context.packageName}.fixture.backups/tree/root")
    @Before fun setup() { prefs.edit().clear().commit(); TestBackupDocumentsProvider.reset(context) }
    @After fun cleanup() { prefs.edit().clear().commit(); TestBackupDocumentsProvider.reset(context) }

    @Test fun missingFolderCannotEnableBackupsAndNewSelectionDefaultsOn() {
        assertFalse(AutoBackup.isEnabled(context))
        AutoBackup.setEnabled(context, true)
        assertFalse(AutoBackup.isEnabled(context))
        AutoBackup.setTreeUri(context, tree)
        assertTrue(AutoBackup.isEnabled(context))
        AutoBackup.setEnabled(context, false)
        assertFalse(AutoBackup.isEnabled(context))
        // Reopening settings must not turn a deliberately disabled backup back on.
        AutoBackup.setTreeUri(context, tree)
        assertFalse(AutoBackup.isEnabled(context))
        AutoBackup.setTreeUri(context, null)
        assertFalse(AutoBackup.isEnabled(context))
    }
    @Test fun directoryMetadataChecksAreReadOnlyAndDetectRevokedAccess() {
        assertTrue(BackupFolderAccess.isWritableDirectory(context, tree))
        TestBackupDocumentsProvider.denyAccess = true
        assertFalse(BackupFolderAccess.isWritableDirectory(context, tree))
        assertFalse(BackupFolderAccess.isUsable(context, tree))
        assertFalse(BackupFolderAccess.isUsable(context, null))
    }
    @Test fun ungrantedSelectionDoesNotOverwritePriorFolderOrEnableBackup() {
        AutoBackup.setTreeUri(context, tree)
        AutoBackup.setEnabled(context, false)
        val invalid = Uri.parse("content://invalid.synthetic.provider/tree/missing")
        assertFalse(BackupFolderAccess.select(context, invalid))
        assertEquals(tree, AutoBackup.treeUri(context))
        assertFalse(AutoBackup.isEnabled(context))
    }
}
