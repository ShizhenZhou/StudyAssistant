package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class BackupTaskPolicyTest {
    @Test fun actualFolderDayDeterminesWhetherAnAutomaticPlanIsNeeded() {
        assertTrue(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-26",false))
        assertFalse(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-26",false,latestBackupDay="2026-10-26"))
        assertTrue(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-27",false,latestBackupDay="2026-10-26"))
        assertFalse(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-27",true))
        assertFalse(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-26",false,canceledDay="2026-10-26"))
        assertTrue(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-27",false,canceledDay="2026-10-26"))
    }
    @Test fun noActiveQuestionsMeansNoAutomaticTask() {
        assertFalse(BackupTaskPolicy.shouldSchedule(true,true,"d",false,hasQuestions=false))
        assertTrue(BackupTaskPolicy.shouldSchedule(true,true,"d",false,hasQuestions=true))
    }
    @Test fun failureNotificationRequiresAutomaticTerminalFailureInBackground() {
        assertTrue(BackupTaskPolicy.shouldNotifyFailure(true,false,true))
        assertFalse(BackupTaskPolicy.shouldNotifyFailure(true,true,true))
        assertFalse(BackupTaskPolicy.shouldNotifyFailure(false,false,true))
        assertFalse(BackupTaskPolicy.shouldNotifyFailure(true,false,false))
    }
    @Test fun immediateRequestCanReplaceOnlyQueuedAutomaticWork() {
        assertTrue(BackupTaskPolicy.canRequestNow(false, false, ""))
        assertTrue(BackupTaskPolicy.canRequestNow(true, true, "data.backupJob.queued"))
        assertFalse(BackupTaskPolicy.canRequestNow(true, false, "data.backupJob.queued"))
        assertFalse(BackupTaskPolicy.canRequestNow(true, true, "data.backupJob.writing"))
        assertFalse(BackupTaskPolicy.canRequestNow(true, true, "data.backupJob.capturing"))
    }
    @Test fun anyCompletedBackupTodaySuppressesAnAutomaticPlan() {
        assertFalse(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-26",false,latestBackupDay="2026-10-26"))
        assertTrue(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-26",false,latestBackupDay="2026-10-25"))
    }
    @Test fun disabledAndMissingFolderNeverSchedule() {
        assertFalse(BackupTaskPolicy.shouldSchedule(false,true,"d",false))
        assertFalse(BackupTaskPolicy.shouldSchedule(true,false,"d",false))
    }
    @Test fun newVerifiedSetIsProtectedEvenWhenClockMovesBackwards() {
        val sets=listOf(BackupSetStamp("new",100,true,true),BackupSetStamp("a",600,true,true),
            BackupSetStamp("b",500,true,true),BackupSetStamp("c",400,true,true),
            BackupSetStamp("manual",300,true,false),BackupSetStamp("incomplete",200,false,true))
        assertEquals(listOf("c"),BackupTaskPolicy.pruneSetIds(sets,"new",3))
        assertTrue(BackupTaskPolicy.pruneSetIds(sets,"missing",3).isEmpty())
        assertTrue(BackupTaskPolicy.pruneSetIds(sets,"incomplete",3).isEmpty())
    }
    @Test fun permanentFailuresDoNotConsumeUnboundedRetries() {
        assertTrue(BackupTaskPolicy.retryable("data.autoBackup.storageFailed",0))
        assertFalse(BackupTaskPolicy.retryable("data.autoBackup.storageFailed",3))
        assertFalse(BackupTaskPolicy.retryable("data.autoBackup.permissionFailed",0))
        assertFalse(BackupTaskPolicy.retryable("data.backupSpaceInsufficient",0))
    }
}
