package com.zsz.studyassistant.data

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream

/** Ownership and completed-stage journals live only in the job's private workspace. */
internal class BackupExportCheckpoint(private val root:File,private val check:()->Unit) {
    private val owner=File(root,"repository.owner")
    init {
        require(root.exists() || root.mkdirs())
        if(owner.exists()) require(owner.length()<64 && owner.readText()=="backup-repository-v1")
        else { require(root.list()?.isEmpty()==true); atomic(owner,"backup-repository-v1") }
    }
    data class Stage(val snapshotAt:Long,val contentHash:String)
    fun read(name:String,tree:File):Stage? {
        val marker=File(root,"$name.ready")
        if(!marker.isFile || marker.length()>256 || !File(tree,"manifest").isFile) return null
        val fields=marker.readLines()
        val at=fields.getOrNull(0)?.toLongOrNull() ?: return null
        val age=System.currentTimeMillis()-at
        if(age !in 0 until 24L*60*60*1000 || fields.size!=3 || fields[1]!="1") return null
        val hash=BackupTree.contentDigest(tree,check)
        return Stage(at,hash).takeIf { fields[2]==hash }
    }
    fun complete(name:String,tree:File,at:Long) {
        atomic(File(root,"$name.ready"),"$at\n1\n${BackupTree.contentDigest(tree,check)}\n")
    }
    fun validated(tree:File,at:Long):Boolean {
        val marker=File(root,"validation.ready"); if(!marker.isFile || marker.length()>256) return false
        val fields=marker.readLines()
        return fields.size==3 && fields[0]==at.toString() && fields[1]=="1" && fields[2]==BackupTree.contentDigest(tree,check)
    }
    fun markValidated(tree:File,at:Long) = atomic(File(root,"validation.ready"),"$at\n1\n${BackupTree.contentDigest(tree,check)}\n")
    /** Every name is fixed by this class, and ownership is established before deletion. */
    fun reset(vararg names:String) {
        for(name in names) { require(name in setOf("capture","tree","archive","export-validation.db","capture.ready","normalize.ready","validation.ready","normalize.state")); val target=File(root,name); if(target.exists() && !target.deleteRecursively()) throw java.io.IOException("Cannot reset private backup stage") }
    }
    companion object {
        fun atomic(file:File,text:String) {
            val atomic=AtomicFile(file); val output=atomic.startWrite()
            try { output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync(); atomic.finishWrite(output) }
            catch(error:Throwable) { atomic.failWrite(output); throw error }
        }
    }
}
