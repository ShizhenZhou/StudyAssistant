package com.zsz.studyassistant.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class BackupNoticesTest {
    @Test fun onlySuccessWhileForegroundEmitsAndNothingReplays() = runBlocking {
        BackupJobs.setForeground(false)
        val background = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(100) { BackupJobs.notices.first() }
        }
        BackupJobs.notifySuccess(true, 10)
        assertNull(background.await())
        BackupJobs.setForeground(true)
        val foreground = async(start = CoroutineStart.UNDISPATCHED) { BackupJobs.notices.first() }
        BackupJobs.notifySuccess(true, 20)
        assertEquals(BackupJobs.Notice(true, 20), foreground.await())
        assertTrue(BackupJobs.notices.replayCache.isEmpty())
        assertNull(withTimeoutOrNull(100) { BackupJobs.notices.first() })
        BackupJobs.setForeground(false)
    }
}
