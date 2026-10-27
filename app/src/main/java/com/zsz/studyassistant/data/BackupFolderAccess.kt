package com.zsz.studyassistant.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** Read-only directory health checks. No probe files or data modification. Call from IO. */
internal object BackupFolderAccess {
    fun isUsable(context: Context, tree: Uri?): Boolean = tree != null && runCatching {
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission } &&
            isWritableDirectory(context, tree)
    }.getOrDefault(false)

    internal fun isWritableDirectory(context: Context, tree: Uri): Boolean = runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
        context.contentResolver.query(document, columns, null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR &&
                cursor.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        } ?: false
    }.getOrDefault(false)

    /** A canceled or unusable selection leaves the previous configured directory unchanged. */
    fun select(context: Context, tree: Uri): Boolean {
        try {
            context.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { return false }
        if (!isUsable(context, tree)) return false
        AutoBackup.setTreeUri(context, tree)
        AutoBackup.setEnabled(context, true)
        BackupJobs.onAppOpened(context)
        return true
    }
}
