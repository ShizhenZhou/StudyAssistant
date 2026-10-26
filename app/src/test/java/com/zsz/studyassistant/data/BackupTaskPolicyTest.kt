package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class BackupTaskPolicyTest {
    @Test fun openingAgainTodayDoesNotCreateAnotherAttemptButNextDayCan() {
        assertTrue(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-26",null,null,false))
        assertFalse(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-26","2026-10-26",null,false))
        assertFalse(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-26",null,"2026-10-26",false))
        assertTrue(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-27","2026-10-26","2026-10-26",false))
        assertFalse(BackupTaskPolicy.shouldSchedule(true,true,"2026-10-27","2026-10-26",null,true))
    }
    @Test fun noActiveQuestionsMeansNoAutomaticTask() {
        assertFalse(BackupTaskPolicy.shouldSchedule(true,true,"d",null,null,false,hasQuestions=false))
        assertTrue(BackupTaskPolicy.shouldSchedule(true,true,"d",null,null,false,hasQuestions=true))
    }
    @Test fun failureNotificationRequiresAutomaticTerminalFailureInBackground() {
        assertTrue(BackupTaskPolicy.shouldNotifyFailure(true,false,true))
        assertFalse(BackupTaskPolicy.shouldNotifyFailure(true,true,true))
        assertFalse(BackupTaskPolicy.shouldNotifyFailure(false,false,true))
        assertFalse(BackupTaskPolicy.shouldNotifyFailure(true,false,false))
    }
    @Test fun disabledAndMissingFolderNeverSchedule() {
        assertFalse(BackupTaskPolicy.shouldSchedule(false,true,"d",null,null,false))
        assertFalse(BackupTaskPolicy.shouldSchedule(true,false,"d",null,null,false))
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
