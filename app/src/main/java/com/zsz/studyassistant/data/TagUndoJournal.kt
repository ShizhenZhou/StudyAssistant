package com.zsz.studyassistant.data

/** Synchronous durable operations; callers serialize access and run them on Dispatchers.IO. */
interface TagUndoStore {
    fun read(slot: Int): TagMergeUndo?
    fun stage(record: TagMergeUndo)
    fun resolve(promote: Boolean)
    fun clear()
}

object TagUndoJournal {
    /** Called with a consistent main-DB snapshot, only after any earlier transaction has ended. */
    fun recover(store: TagUndoStore, current: TagInventory): TagMergeUndo? {
        val pending = store.read(2)
        if (pending != null) {
            when {
                TagMergeEngine.canUndo(current, pending) -> store.resolve(promote = true)
                TagMergeEngine.matchesBefore(current, pending) -> store.resolve(promote = false)
                // An interrupted operation followed by other edits is ambiguous: never guess an undo.
                else -> store.clear()
            }
        }
        val active = store.read(1)
        // Also cleans up a completed undo if the process died before auxiliary cleanup.
        if (active != null && TagMergeEngine.matchesBefore(current, active)) {
            store.clear()
            return null
        }
        return active
    }
}
