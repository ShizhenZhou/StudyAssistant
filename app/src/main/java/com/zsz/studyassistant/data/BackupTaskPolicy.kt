package com.zsz.studyassistant.data

/** Pure decisions shared by the UI trigger and the durable worker. */
data class BackupSetStamp(val id:String,val at:Long,val complete:Boolean,val automatic:Boolean)
object BackupTaskPolicy {
    fun shouldSchedule(enabled:Boolean,hasFolder:Boolean,today:String,active:Boolean,hasQuestions:Boolean=true,
        latestBackupDay:String?=null,canceledDay:String?=null):Boolean =
        enabled && hasFolder && hasQuestions && !active && today!=latestBackupDay && today!=canceledDay

    fun canRequestNow(active:Boolean,automatic:Boolean,phase:String):Boolean =
        !active || (automatic && phase == "data.backupJob.queued")

    /** Never prune based on an unverified replacement; protect the new set even after clock rollback. */
    fun pruneSetIds(sets:List<BackupSetStamp>,newId:String,keep:Int):List<String> {
        require(keep>=1)
        val eligible=sets.filter { it.complete && it.automatic }.distinctBy { it.id }
        if(eligible.none { it.id==newId }) return emptyList()
        return eligible.filter { it.id!=newId }.sortedByDescending { it.at }.drop(keep-1).map { it.id }
    }
    fun shouldNotifyFailure(automatic:Boolean,foreground:Boolean,terminal:Boolean):Boolean =
        automatic && !foreground && terminal

    fun retryable(key:String,attempt:Int):Boolean = attempt<3 && key in setOf(
        "data.autoBackup.storageFailed","data.autoBackup.writeFailed","data.autoBackup.verifyFailed")
}
