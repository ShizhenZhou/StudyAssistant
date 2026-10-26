package com.zsz.studyassistant.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import java.io.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*

/** Private, bounded disk pipeline. Neither export nor import materializes a library DTO. */
class BackupRepository(private val context: Context, private val db: AppDatabase = AppDatabase.get(context)) {
    data class PreparedImport(val directory: File, val stats: BackupStats, val snapshotAt: Long)

    suspend fun export(directory: File, volumeBytes: Long = BackupArchive.DEFAULT_VOLUME_BYTES,
        check: () -> Unit = {}, progress: (String, Long) -> Unit = { _, _ -> }): BackupArchive.ArchiveSet = withContext(Dispatchers.IO) {
        val job=currentCoroutineContext()[Job]
        val stop={ job?.ensureActive(); check() }
        privateLocation(directory)
        val journal=BackupExportCheckpoint(directory,stop)
        val raw=File(directory,"capture")
        val tree=File(directory,"tree")
        val normalized=journal.read("normalize",tree)
        var snapshotAt=normalized?.snapshotAt
        if(normalized==null) {
            val captured=journal.read("capture",raw)
            snapshotAt=captured?.snapshotAt
            if(captured==null) {
                journal.reset("capture","tree","archive","export-validation.db","capture.ready","normalize.ready","validation.ready","normalize.state")
                kotlin.check(raw.mkdir())
                snapshotAt=System.currentTimeMillis()
                progress("data.backupSnapshot",0)
                // Force Room to open/migrate and validate first. Never copy an active WAL database.
                val opened=db.openHelper.writableDatabase
                val sourcePath=opened.path
                if (Build.VERSION.SDK_INT >= 35 && sourcePath != null && sourcePath != ":memory:") {
                    val dispatcher=Executors.newSingleThreadExecutor { runnable -> Thread(runnable,"backup-read-snapshot") }.asCoroutineDispatcher()
                    try { withContext(dispatcher) {
                        SQLiteDatabase.openDatabase(sourcePath,null,SQLiteDatabase.OPEN_READONLY).use { source ->
                            source.beginTransactionReadOnly()
                            try { BackupTree.capture(raw,{ sql,args -> source.rawQuery(sql,args) },stop) { progress("data.backupSnapshot",it) }; source.setTransactionSuccessful() }
                            finally { source.endTransaction() }
                        }
                    } } finally { dispatcher.close() }
                } else {
                    db.withTransaction {
                        BackupTree.capture(raw,{ sql,args -> opened.query(sql,args) },stop) { progress("data.backupSnapshot",it) }
                    }
                }
                journal.complete("capture",raw,checkNotNull(snapshotAt))
            }
            journal.reset("tree","export-validation.db","normalize.ready","validation.ready")
            kotlin.check(tree.mkdir())
            progress("data.backupNormalize",0)
            BackupTree.normalize(raw,tree,stop) { progress("data.backupNormalize",it) }
            journal.complete("normalize",tree,checkNotNull(snapshotAt))
            journal.reset("capture","capture.ready")
        }
        val at=checkNotNull(snapshotAt)
        val validation=File(directory,"export-validation.db")
        if(!journal.validated(tree,at)) {
            journal.reset("export-validation.db","validation.ready")
            progress("data.backupValidate",0)
            BackupTree.restore(tree,validation,stop) { progress("data.backupValidate",it) }
            journal.markValidated(tree,at)
            journal.reset("export-validation.db")
        }
        journal.reset("archive")
        progress("data.backupCompress",0)
        BackupArchive.writeTree(tree,File(directory,"archive"),at,volumeBytes,stop) { progress("data.backupCompress",it) }
    }

    suspend fun prepareImport(uris: List<Uri>, directory: File, check: () -> Unit = {},
        progress: (String, Long) -> Unit = { _, _ -> }): PreparedImport = withContext(Dispatchers.IO) {
        privateWorkspace(directory)
        val job=currentCoroutineContext()[Job]
        val stop={ job?.ensureActive(); check() }
        val copied=File(directory,"selected").apply { kotlin.check(mkdir()) }
        val files=uris.mapIndexed { index,uri ->
            check()
            File(copied,"$index.input").also { target ->
                val input=context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open selected backup")
                input.use { from -> FileOutputStream(target).use { to -> BackupTree.copy(from,to,stop); to.fd.sync() } }
            }
        }
        prepareFiles(files,directory,check,progress)
    }

    /** Also useful for already fixed, private local selections; input files are never mutated. */
    suspend fun prepareImportFiles(files: List<File>, directory: File, check: () -> Unit = {},
        progress: (String, Long) -> Unit = { _, _ -> }): PreparedImport = withContext(Dispatchers.IO) {
        privateWorkspace(directory)
        prepareFiles(files,directory,check,progress)
    }

    private suspend fun prepareFiles(files: List<File>, directory: File, check: () -> Unit,
        progress: (String, Long) -> Unit): PreparedImport {
        val job=currentCoroutineContext()[Job]
        val stop={ job?.ensureActive(); check() }
        if(files.isEmpty()) BackupTree.invalid()
        val tree=File(directory,"tree")
        progress("data.backupRead",0)
        val isZip=files.first().inputStream().use { it.read()==0x50 && it.read()==0x4b }
        val snapshotAt=if(isZip) BackupArchive.readParts(files,tree,stop) { progress("data.backupRead",it) }.snapshotAt
        else {
            if(files.size!=1) BackupTree.invalid()
            kotlin.check(tree.mkdir())
            BackupLegacyReader.read(files.single(),tree,stop)
        }
        progress("data.backupValidate",0)
        val staged=File(directory,"validated.db")
        val stats=try { BackupTree.restore(tree,staged,stop) { progress("data.backupValidate",it) } }
            catch(_:android.database.sqlite.SQLiteFullException) { throw BackupValidationException("data.backupSpaceInsufficient") }
            catch(_:android.database.sqlite.SQLiteException) { BackupTree.invalid() }
        File(directory,"ready").writeText("$snapshotAt\n${BackupTree.digest(staged,stop)}\n")
        return PreparedImport(directory,stats,snapshotAt)
    }

    suspend fun merge(prepared: PreparedImport, check: () -> Unit = {}): ImportResult = withContext(Dispatchers.IO) {
        val job=currentCoroutineContext()[Job]
        val stop={ job?.ensureActive(); check() }
        privateLocation(prepared.directory)
        val maps=File(prepared.directory,"merge-index-${java.util.UUID.randomUUID()}.db")
        val staged=File(prepared.directory,"validated.db")
        val ready=File(prepared.directory,"ready")
        if(!ready.isFile || ready.length()>256) BackupTree.invalid()
        val lines=ready.readLines()
        if(lines.size!=2 || lines[0].toLongOrNull()!=prepared.snapshotAt || lines[1]!=BackupTree.digest(staged,stop)) BackupTree.invalid()
        // Private staged input remains separate: Android ATTACH changes WAL state and is
        // forbidden during an active Room transaction. No journal-mode changes are needed.
        try {
            SQLiteDatabase.openDatabase(staged.path,null,SQLiteDatabase.OPEN_READONLY).use { source ->
                BackupTree.validate(source,stop)
                SQLiteDatabase.openOrCreateDatabase(maps,null).use { index ->
                    TagMergeRepository.invalidateForImport(db) {
                        db.withTransaction {
                            BackupStagedMerge(source,index,db.openHelper.writableDatabase,stop).merge()
                        }
                    }
                }
            }
        } finally {
            maps.delete(); File(maps.path+"-journal").delete()
        }
    }
    private fun privateLocation(directory:File) {
        val path=directory.canonicalFile.toPath()
        val allowed=listOf(context.filesDir,context.cacheDir,context.noBackupFilesDir).any { path.startsWith(it.canonicalFile.toPath()) }
        require(allowed) { "Backup workspace must be app-private" }
    }
    private fun privateWorkspace(directory:File) {
        privateLocation(directory)
        require(!directory.exists() || directory.list()?.isEmpty()==true) { "Backup workspace must be empty" }
        require(directory.exists() || directory.mkdirs())
    }
}
