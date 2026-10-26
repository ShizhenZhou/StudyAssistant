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
    @Test fun manualRequestBypassesDailyCheckAndConfigChangeDoesNotSchedule() {
        prefs.edit().putString("backup_job_checked_day", AutoBackup.dayKey(System.currentTimeMillis())).commit()
        BackupJobs.onAppOpened(context)
        assertTrue(works().isEmpty())
        BackupJobs.requestNow(context)
        assertEquals(1, works().size)
        val epoch = prefs.getLong("backup_job_generation", 0)
        BackupJobs.configurationChanged(context)
        WorkManager.getInstance(context).cancelUniqueWork("backup-v4-on-open").result.get()
        assertTrue(works().isEmpty())
        assertTrue(prefs.getLong("backup_job_generation", 0) > epoch)
        assertFalse(BackupJobs.status(context).active)
    }
}
