package com.zsz.studyassistant.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Only synthetic integration-APK data. Exercises production reconciliation and daily scheduling. */
class BackupFolderRecoveryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName == "com.zsz.studyassistant.integration")
    }
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val tree get() = Uri.parse("content://${context.packageName}.fixture.backups/tree/root")
    private val fixtureId = QuestionIdentity.create(1700000600000L)
    private val dao get() = AppDatabase.get(context).questionDao()
    private fun works() = WorkManager.getInstance(context).getWorkInfosForUniqueWork("backup-v4-on-open")
        .get().filter { !it.state.isFinished }
    // Uses the complete production health check; Android permits access to this app's own UID provider.
    private fun refresh(schedule: Boolean = true) = BackupFolderAccess.refresh(context, schedule)

    @Before fun setup() {
        WorkManager.getInstance(context).cancelUniqueWork("backup-v4-on-open").result.get()
        prefs.edit().clear().commit()
        TestBackupDocumentsProvider.reset(context)
        AutoBackup.setTreeUri(context, tree)
        runBlocking { dao.insert(Question(fixtureId, "synthetic recovery fixture", "answer", 1700000600000L)) }
    }
    @After fun cleanup() {
        WorkManager.getInstance(context).cancelUniqueWork("backup-v4-on-open").result.get()
        runBlocking { dao.deleteById(fixtureId) }
        prefs.edit().clear().commit()
        TestBackupDocumentsProvider.reset(context)
    }

    @Test fun temporaryProviderFailuresKeepIntentAndRecoveryQueuesRealDailyWork() {
        for (fault in listOf("io", "provider", "missing-file", "null", "empty")) {
            TestBackupDocumentsProvider.metadataFault = fault
            val result = checkNotNull(refresh())
            assertEquals(fault, BackupFolderAccess.State.TEMPORARILY_UNAVAILABLE, result.health.state)
            assertEquals(tree, AutoBackup.treeUri(context))
            assertTrue(fault, AutoBackup.isEnabled(context))
            assertTrue(fault, works().isEmpty())
            assertNull(AutoBackup.lastFailure(context))
        }
        TestBackupDocumentsProvider.metadataFault = null
        assertEquals(BackupFolderAccess.State.AVAILABLE, checkNotNull(refresh()).health.state)
        assertTrue(AutoBackup.isEnabled(context))
        assertEquals(1, works().size)
        assertTrue(BackupJobs.status(context).active)
        assertNull(BackupJobs.status(context).errorKey)
    }

    @Test fun ownProviderHasDurableAccessButUnownedProviderStillNeedsPersistedGrant() {
        assertEquals(BackupFolderAccess.State.AVAILABLE, BackupFolderAccess.check(context, tree).state)
        val external = Uri.parse("content://synthetic.unowned.provider/tree/root")
        val revoked = BackupFolderAccess.check(context, external)
        assertEquals(BackupFolderAccess.State.INVALID, revoked.state)
        assertEquals(BackupFolderAccess.Reason.REVOKED, revoked.reason)
        AutoBackup.setEnabled(context, false)
        assertTrue(BackupFolderAccess.select(context, tree))
        assertTrue(AutoBackup.isEnabled(context))
    }

    @Test fun deliberateOffRemainsOffThroughFailureAndRecovery() {
        AutoBackup.setEnabled(context, false)
        TestBackupDocumentsProvider.metadataFault = "io"
        assertEquals(BackupFolderAccess.State.TEMPORARILY_UNAVAILABLE, checkNotNull(refresh()).health.state)
        TestBackupDocumentsProvider.metadataFault = null
        assertEquals(BackupFolderAccess.State.AVAILABLE, checkNotNull(refresh()).health.state)
        assertFalse(AutoBackup.isEnabled(context))
        assertTrue(works().isEmpty())
    }

    @Test fun explicitPermissionDenialDisablesButPreservesDirectory() {
        TestBackupDocumentsProvider.denyAccess = true
        val result = checkNotNull(refresh())
        assertEquals(BackupFolderAccess.State.INVALID, result.health.state)
        assertEquals(BackupFolderAccess.Reason.REVOKED, result.health.reason)
        assertEquals(tree, AutoBackup.treeUri(context))
        assertFalse(AutoBackup.isEnabled(context))
        assertTrue(works().isEmpty())
        TestBackupDocumentsProvider.denyAccess = false
        assertEquals(BackupFolderAccess.State.AVAILABLE, checkNotNull(refresh()).health.state)
        assertFalse(AutoBackup.isEnabled(context))
    }

    @Test fun missingConfiguredDirectoryIsExplicitlyInvalid() {
        AutoBackup.setTreeUri(context, null)
        val result = checkNotNull(refresh())
        assertEquals(BackupFolderAccess.State.INVALID, result.health.state)
        assertEquals(BackupFolderAccess.Reason.MISSING, result.health.reason)
        assertFalse(AutoBackup.isEnabled(context))
    }

    @Test fun staleInvalidResponseCannotDisableNewSelection() {
        val newer = Uri.parse("$tree?selection=new")
        var reads = 0
        val observed = BackupFolderAccess.refresh(context, false) { c, uri ->
            if (reads++ == 0) {
                AutoBackup.setTreeUri(context, newer)
                BackupFolderAccess.Health(BackupFolderAccess.State.INVALID, BackupFolderAccess.Reason.REVOKED)
            } else BackupFolderAccess.directoryHealth(c, checkNotNull(uri))
        }
        assertEquals(2, reads)
        assertEquals(newer, checkNotNull(observed).tree)
        assertEquals(BackupFolderAccess.State.AVAILABLE, observed.health.state)
        assertTrue(AutoBackup.isEnabled(context))
    }

    @Test fun acceptingSameEnabledUriInvalidatesOldHealthRead() {
        val previous = checkNotNull(refresh(schedule = false))
        assertTrue(BackupFolderAccess.isCurrent(context, previous))
        var reads = 0
        val observed = BackupFolderAccess.refresh(context, false) { c, uri ->
            if (reads++ == 0) {
                // Re-select through the real helper, with no URI or switch change.
                assertTrue(BackupFolderAccess.select(context, tree))
                assertFalse(BackupFolderAccess.isCurrent(context, previous))
                BackupFolderAccess.Health(BackupFolderAccess.State.INVALID, BackupFolderAccess.Reason.REVOKED)
            } else BackupFolderAccess.directoryHealth(c, checkNotNull(uri))
        }
        assertEquals(2, reads)
        assertEquals(BackupFolderAccess.State.AVAILABLE, checkNotNull(observed).health.state)
        assertTrue(AutoBackup.isEnabled(context))
    }
}
