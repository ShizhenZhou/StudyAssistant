package com.zsz.studyassistant.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** Read-only directory health checks. No probe files or data modification. Call from IO. */
internal object BackupFolderAccess {
    enum class State { AVAILABLE, INVALID, TEMPORARILY_UNAVAILABLE }
    enum class Reason { MISSING, REVOKED, NOT_DIRECTORY, READ_ONLY, MALFORMED_URI }
    data class Health(val state: State, val reason: Reason? = null)
    data class Observation(val tree: Uri?, val generation: Long, val health: Health)
    private val available = Health(State.AVAILABLE)
    private val temporary = Health(State.TEMPORARILY_UNAVAILABLE)
    private fun invalid(reason: Reason) = Health(State.INVALID, reason)
    private fun generation(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getLong("backup_job_generation", 0L)

    fun check(context: Context, tree: Uri?): Health {
        if (tree == null) return invalid(Reason.MISSING)
        return try {
            val persisted = context.contentResolver.persistedUriPermissions.any {
                it.uri == tree && it.isReadPermission && it.isWritePermission
            }
            if (!persisted && !ownsProvider(context, tree)) invalid(Reason.REVOKED)
            else directoryHealth(context, tree)
        } catch (_: SecurityException) { invalid(Reason.REVOKED) }
        catch (_: Exception) { temporary }
    }

    // Android grants a provider's own UID read/write access before testing URI grants.
    // Resolve actual installed ownership, then still verify readable, writable directory metadata.
    private fun ownsProvider(context: Context, tree: Uri): Boolean {
        if (tree.scheme != "content") return false
        val authority = tree.authority ?: return false
        @Suppress("DEPRECATION")
        val provider = context.packageManager.resolveContentProvider(authority, 0) ?: return false
        return provider.applicationInfo.uid == context.applicationInfo.uid
    }

    internal fun directoryHealth(context: Context, tree: Uri): Health {
        val document = try {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        } catch (_: IllegalArgumentException) { return invalid(Reason.MALFORMED_URI) }
        return try {
            val columns = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
            context.contentResolver.query(document, columns, null, null, null)?.use { cursor ->
                // Providers may return null/empty cursors while offline; neither proves deletion.
                if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) temporary
                else if (cursor.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR) invalid(Reason.NOT_DIRECTORY)
                else if (cursor.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) invalid(Reason.READ_ONLY)
                else available
            } ?: temporary
        } catch (_: SecurityException) { invalid(Reason.REVOKED) }
        catch (_: Exception) { temporary }
    }

    fun isUsable(context: Context, tree: Uri?) = check(context, tree).state == State.AVAILABLE
    internal fun isWritableDirectory(context: Context, tree: Uri) = directoryHealth(context, tree).state == State.AVAILABLE

    /** A stale provider response must never change a newer directory or switch configuration.
     * healthCheck lets integration tests inject configuration changes during an in-flight read.
     */
    fun refresh(context: Context, scheduleIfAvailable: Boolean = false,
        healthCheck: (Context, Uri?) -> Health = ::check): Observation? {
        // Startup and the setup dialog may inspect concurrently. Retry a superseded read rather
        // than letting the losing initial inspection suppress the setup prompt for this launch.
        repeat(3) {
            val before = synchronized(BackupJobs) { AutoBackup.treeUri(context) to generation(context) }
            val health = healthCheck(context, before.first)
            val observed = synchronized(BackupJobs) {
                if (AutoBackup.treeUri(context) != before.first || generation(context) != before.second) null
                else {
                    if (health.state == State.INVALID) AutoBackup.setEnabled(context, false)
                    Observation(before.first, generation(context), health)
                }
            }
            if (observed != null) {
                // Inventory IO runs outside the monitor; BackupJobs also checks its generation.
                if (health.state == State.AVAILABLE && scheduleIfAvailable && isCurrent(context, observed))
                    BackupJobs.onAppOpened(context)
                return observed
            }
        }
        return null
    }

    fun isCurrent(context: Context, observation: Observation) = synchronized(BackupJobs) {
        AutoBackup.treeUri(context) == observation.tree && generation(context) == observation.generation
    }

    /** A canceled or unusable selection leaves the previous configured directory unchanged. */
    fun select(context: Context, tree: Uri): Boolean {
        try {
            if (!ownsProvider(context, tree)) context.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { return false }
        if (!isUsable(context, tree)) return false
        acceptSelection(context, tree)
        return true
    }

    /** Called only after a selection has durable access and healthy directory metadata. */
    internal fun acceptSelection(context: Context, tree: Uri) {
        synchronized(BackupJobs) {
            // Reauthorizing the same enabled URI is still a new selection. Neither setter would
            // advance the generation, so explicitly invalidate reads started before this grant.
            if (AutoBackup.treeUri(context) == tree && AutoBackup.isEnabled(context))
                BackupJobs.configurationChanged(context)
            AutoBackup.setTreeUri(context, tree)
            AutoBackup.setEnabled(context, true)
        }
        BackupJobs.onAppOpened(context)
    }
}
