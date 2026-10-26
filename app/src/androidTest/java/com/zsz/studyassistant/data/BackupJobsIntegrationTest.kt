package com.zsz.studyassistant.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupJobsIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also { check(it.packageName == "com.zsz.studyassistant.integration") }
    private val fixtureId = QuestionIdentity.create(1700000200000L)
    private val deletedFixtureId = QuestionIdentity.create(1700000200001L)
    private val dao get() = AppDatabase.get(context).questionDao()
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun works() = WorkManager.getInstance(context).getWorkInfosForUniqueWork("backup-v4-on-open").get().filter { !it.state.isFinished }
    @Before fun reset() {
        WorkManager.getInstance(context).cancelUniqueWork("backup-v4-on-open").result.get()
        TestBackupDocumentsProvider.reset(context)
        prefs.edit().clear().putString("auto_backup_tree", "content://${context.packageName}.fixture.backups/tree/root").commit()
        runBlocking { dao.insert(Question(fixtureId, "synthetic schedule fixture", "answer", 1700000200000L)) }
    }
    @After fun cleanup() {
        BackupJobs.setForeground(false)
        WorkManager.getInstance(context).cancelUniqueWork("backup-v4-on-open").result.get()
        runBlocking { dao.deleteById(fixtureId); dao.deleteById(deletedFixtureId) }
        prefs.edit().clear().commit()
    }
    @Test fun emptyOrOnlyDeletedQuestionsDoNotScheduleAutomaticWork() = runBlocking {
        dao.deleteById(fixtureId)
        assertFalse("Integration DB contains unexpected unowned active fixture", BackupJobs.hasActiveQuestions(context))
        BackupJobs.onAppOpened(context)
        assertTrue(works().isEmpty())
        assertFalse(prefs.contains("backup_job_attempt_day"))
        dao.insert(Question(deletedFixtureId, "synthetic deleted fixture", "answer", 1700000200001L, deleted = true))
        BackupJobs.onAppOpened(context)
        assertTrue(works().isEmpty())
        // Manual exports/explicit requests remain possible for an empty library.
        BackupJobs.enqueue(context, automatic = false, delay = 60)
        assertEquals(1, works().size)
    }
    @Test fun workerEligibilityRechecksEmptyLibraryAfterQueueingWithoutReportingFailure() = runBlocking {
        BackupJobs.onAppOpened(context)
        assertEquals(1, works().size)
        dao.deleteById(fixtureId)
        assertFalse("Integration DB contains unexpected unowned active fixture", BackupJobs.hasActiveQuestions(context))
        assertTrue(BackupJobs.stopAutomaticIfEmpty(context, automatic = true))
        assertFalse(BackupJobs.status(context).active)
        assertEquals("data.backupJob.empty", BackupJobs.status(context).phaseKey)
        assertNull(BackupJobs.status(context).errorKey)
        assertFalse(prefs.contains("backup_job_attempt_day"))
        assertFalse(BackupJobs.stopAutomaticIfEmpty(context, automatic = false))
    }
    @Test fun successEventsAreForegroundOnlyAndNeverReplay() = runBlocking {
        BackupJobs.setForeground(false)
        val background = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(100) { BackupJobs.notices.first() }
        }
        BackupJobs.notifySuccess(true, 1000)
        assertNull(background.await())
        BackupJobs.setForeground(true)
        val foreground = async(start = CoroutineStart.UNDISPATCHED) { BackupJobs.notices.first() }
        BackupJobs.notifySuccess(true, 2000)
        assertEquals(BackupJobs.Notice(automatic = true, snapshotAt = 2000), foreground.await())
        assertTrue(BackupJobs.notices.replayCache.isEmpty())
        assertNull(withTimeoutOrNull(100) { BackupJobs.notices.first() })
        BackupJobs.setForeground(false)
    }
    @Test fun appOpenRecoversActivePreferenceWithoutDispatchedWork() {
        prefs.edit().putBoolean("backup_job_active", true).putString("backup_job_token", "orphan")
            .putString("backup_job_work_id", java.util.UUID.randomUUID().toString())
            .putString("backup_job_attempt_day", AutoBackup.dayKey(System.currentTimeMillis())).commit()
        BackupJobs.onAppOpened(context)
        assertEquals(1, works().size)
        assertTrue(BackupJobs.status(context).active)
        assertNotEquals("orphan", prefs.getString("backup_job_token", null))
    }
    @Test fun dispatchExceptionRollsBackActiveAndNextOpenCanRetry() {
        BackupJobs.enqueue(context, automatic = true, delay = 60) { throw java.io.IOException("synthetic dispatch failure") }
        assertFalse(BackupJobs.status(context).active)
        assertEquals("data.autoBackup.storageFailed", BackupJobs.status(context).errorKey)
        assertTrue(works().isEmpty())
        BackupJobs.onAppOpened(context)
        assertTrue(BackupJobs.status(context).active)
        assertEquals(1, works().size)
    }
    @Test fun terminalErrorDoesNotAutomaticallyRetryOnLaterDay() {
        prefs.edit().putString("backup_job_attempt_day", "1900-01-01")
            .putString("backup_job_error", "data.autoBackup.permissionFailed").commit()
        BackupJobs.onAppOpened(context)
        assertTrue(works().isEmpty())
        assertFalse(BackupJobs.status(context).active)
        BackupJobs.configurationChanged(context)
        BackupJobs.onAppOpened(context)
        assertEquals(1, works().size)
    }
    private suspend fun saveFolderBackup(at: Long, automatic: Boolean): String {
        val work = java.io.File(context.cacheDir, "schedule-fixture-${java.util.UUID.randomUUID()}")
        try {
            val output = java.io.File(work, "export")
            BackupRepository(context).export(output)
            val archive = BackupArchive.writeTree(java.io.File(output, "tree"), java.io.File(work, "dated"), snapshotAt = at)
            BackupDocuments.writeSet(context, archive, AutoBackup.treeUri(context)!!, automatic)
            return archive.contentHash
        } finally { work.deleteRecursively() }
    }
    @Test fun actualFolderManualBackupTodaySuppressesAutomaticPlanWithoutLocalSuccessFlag() = runBlocking {
        saveFolderBackup(System.currentTimeMillis(), false)
        assertFalse(prefs.contains("backup_job_checked_day"))
        BackupJobs.onAppOpened(context)
        assertTrue(works().isEmpty())
        assertEquals("data.backupJob.today", BackupJobs.status(context).phaseKey)
        // Explicit requests remain available even though today's folder backup exists.
        BackupJobs.requestNow(context)
        assertFalse(prefs.getBoolean("backup_job_automatic", true))
        assertNotNull(prefs.getString("backup_job_work_id", null))
    }
    @Test fun deletingAllBackupsTodayDoesNotLetStaleSuccessfulFlagsBlockANewPlan() = runBlocking {
        saveFolderBackup(System.currentTimeMillis(), false)
        val today = AutoBackup.dayKey(System.currentTimeMillis())
        prefs.edit().putString("backup_job_checked_day", today).putString("backup_job_attempt_day", today)
            .putString("backup_job_hash", "stale-success").putString("backup_job_phase", "data.backupJob.success").commit()
        TestBackupDocumentsProvider.reset(context)
        BackupJobs.onAppOpened(context)
        assertEquals(1, works().size)
        assertTrue(BackupJobs.status(context).active)
    }
    @Test fun actualFolderAutomaticBackupTodayAlsoSuppressesPlanAndYesterdayDoesNot() = runBlocking {
        saveFolderBackup(System.currentTimeMillis(), true)
        BackupJobs.onAppOpened(context)
        assertTrue(works().isEmpty())
        TestBackupDocumentsProvider.reset(context)
        saveFolderBackup(System.currentTimeMillis() - 48 * 60 * 60_000L, false)
        BackupJobs.onAppOpened(context)
        assertEquals(1, works().size)
    }
    private fun waitForWorker() {
        val deadline = android.os.SystemClock.uptimeMillis() + 20000
        while (BackupJobs.status(context).active && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(60)
        assertFalse("Worker did not complete", BackupJobs.status(context).active)
    }
    @Test fun identicalContentStillWritesWhenTheLastFolderBackupWasDeleted() = runBlocking {
        val hash = saveFolderBackup(System.currentTimeMillis(), false)
        TestBackupDocumentsProvider.reset(context)
        prefs.edit().putString("backup_job_hash", hash).commit()
        BackupJobs.enqueue(context, automatic = true, delay = 0)
        waitForWorker()
        assertEquals("data.backupJob.success", BackupJobs.status(context).phaseKey)
        assertEquals(1, BackupRetention.folderSummary(context, AutoBackup.treeUri(context)!!).automatic)
    }
    @Test fun workerSkipsAnAutomaticPlanWhenACompleteManualBackupAppearsBeforeExecution() = runBlocking {
        saveFolderBackup(System.currentTimeMillis(), false)
        BackupJobs.enqueue(context, automatic = true, delay = 0)
        waitForWorker()
        assertEquals("data.backupJob.today", BackupJobs.status(context).phaseKey)
        val actual = BackupRetention.folderSummary(context, AutoBackup.treeUri(context)!!)
        assertEquals(1, actual.manual); assertEquals(0, actual.automatic)
    }
    @Test fun automaticPlanWaitsThirtySecondsAndImmediateRequestReplacesIt() {
        BackupJobs.onAppOpened(context)
        val delayed = works().single()
        assertEquals(30_000L, delayed.initialDelayMillis)
        val oldToken = prefs.getString("backup_job_token", null)
        BackupJobs.requestNow(context)
        val immediateId = java.util.UUID.fromString(prefs.getString("backup_job_work_id", null))
        val immediate = WorkManager.getInstance(context).getWorkInfoById(immediateId).get()!!
        assertNotEquals(delayed.id, immediate.id)
        assertEquals(0L, immediate.initialDelayMillis)
        assertEquals(androidx.work.Constraints.NONE, immediate.constraints)
        assertNotEquals(oldToken, prefs.getString("backup_job_token", null))
        assertFalse(prefs.getBoolean("backup_job_automatic", true))
        val replaced = WorkManager.getInstance(context).getWorkInfoById(delayed.id).get()
        assertTrue("The old plan must be canceled or removed", replaced == null || replaced.state == androidx.work.WorkInfo.State.CANCELLED)
        assertFalse(works().any { it.id == delayed.id })
    }
    @Test fun manualRequestDoesNotInterruptWorkThatHasAlreadyStarted() {
        BackupJobs.onAppOpened(context)
        val token = prefs.getString("backup_job_token", null)
        prefs.edit().putString("backup_job_phase", "data.backupJob.writing").commit()
        BackupJobs.requestNow(context)
        assertEquals(token, prefs.getString("backup_job_token", null))
    }
    @Test fun cancelingAnOlderPendingPlanSuppressesReschedulingOnTheActualCancellationDay() {
        BackupJobs.onAppOpened(context)
        prefs.edit().putString("backup_job_attempt_day", "1900-01-01").commit()
        BackupJobs.cancel(context)
        WorkManager.getInstance(context).cancelUniqueWork("backup-v4-on-open").result.get()
        BackupJobs.onAppOpened(context)
        assertTrue(works().isEmpty())
        assertEquals(AutoBackup.dayKey(System.currentTimeMillis()), prefs.getString("backup_job_attempt_day", null))
    }
    @Test fun repeatedOpensCreateOneTaskAndCancelPersists() {
        BackupJobs.onAppOpened(context)
        val first = works().single().id
        repeat(4) { BackupJobs.onAppOpened(context) }
        assertEquals(first, works().single().id)
        assertTrue(BackupJobs.status(context).active)
        BackupJobs.cancel(context)
        WorkManager.getInstance(context).cancelUniqueWork("backup-v4-on-open").result.get()
        assertTrue(works().isEmpty())
        assertFalse(BackupJobs.status(context).active)
        assertEquals("data.backupJob.canceled", BackupJobs.status(context).phaseKey)
    }
    @Test fun manualRequestBypassesDailyCheckAndConfigChangeDoesNotSchedule() = runBlocking {
        saveFolderBackup(System.currentTimeMillis(), false)
        BackupJobs.onAppOpened(context)
        assertTrue(works().isEmpty())
        BackupJobs.requestNow(context)
        val manualId = java.util.UUID.fromString(prefs.getString("backup_job_work_id", null))
        assertNotNull(WorkManager.getInstance(context).getWorkInfoById(manualId).get())
        assertFalse(prefs.getBoolean("backup_job_automatic", true))
        val epoch = prefs.getLong("backup_job_generation", 0)
        BackupJobs.configurationChanged(context)
        WorkManager.getInstance(context).cancelUniqueWork("backup-v4-on-open").result.get()
        assertTrue(works().isEmpty())
        assertTrue(prefs.getLong("backup_job_generation", 0) > epoch)
        assertFalse(BackupJobs.status(context).active)
    }
}
