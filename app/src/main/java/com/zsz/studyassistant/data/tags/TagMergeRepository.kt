package com.zsz.studyassistant.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One lock for merge, undo, recovery and backup imports. Ordinary edits remain protected by snapshots. */
object TagMergeRepository {
    private val mutex = Mutex()
    suspend fun snapshot(db: AppDatabase): Pair<TagInventory, TagMergeUndo?> = withContext(Dispatchers.IO) {
        mutex.withLock { db.withTransaction {
            val base = TagMergeEngine.inventory(db.questionDao())
            base to TagUndoJournal.recover(db.tagUndoStore, base)
        } }
    }
    suspend fun apply(db: AppDatabase, plan: TagMergePreview): Triple<TagMergeResult, TagInventory, TagMergeUndo> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val committed = db.withTransaction {
                val dao = db.questionDao()
                TagUndoJournal.recover(db.tagUndoStore, TagMergeEngine.inventory(dao))
                var staged: TagMergeUndo? = null
                val result = TagMergeEngine.apply(dao, plan, stageUndo = {
                    db.tagUndoStore.stage(it)
                    staged = it
                })
                Triple(result, TagMergeEngine.inventory(dao), checkNotNull(staged))
            }
            // Auxiliary cleanup must not turn a committed merge into a reported failure.
            // If promotion fails or the process exits here, the durable candidate is recovered on next read.
            try { db.tagUndoStore.resolve(promote = true) } catch (_: Exception) { }
            committed
        }
    }
    suspend fun undo(db: AppDatabase): Pair<Int, TagInventory> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val committed = db.withTransaction {
                val dao = db.questionDao()
                val record = TagUndoJournal.recover(db.tagUndoStore, TagMergeEngine.inventory(dao))
                // Keep the journal until commit. Recovery can recognize the restored state after a crash.
                TagMergeEngine.undo(dao, record) to TagMergeEngine.inventory(dao)
            }
            try { db.tagUndoStore.clear() } catch (_: Exception) { }
            committed
        }
    }
    suspend fun <T> invalidateForImport(db: AppDatabase, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Invalidate BEFORE importing: an interrupted import cannot revive an unrelated old undo.
            // Conservatively also invalidates when an import transaction subsequently fails.
            db.tagUndoStore.clear()
            block()
        }
    }
}
