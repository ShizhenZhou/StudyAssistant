package com.zsz.studyassistant.data

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.util.UUID

/** Exists only in the independent integration APK. Synthetic files and deterministic fault injection. */
class TestBackupDocumentsProvider : DocumentsProvider() {
    companion object {
        @Volatile var denyAccess = false
        @Volatile var corruptReads = false
        @Volatile var returnExistingId: String? = null
        @Volatile var denyListing = false
        fun reset(context: Context) {
            check(context.packageName == "com.zsz.studyassistant.integration")
            denyAccess = false; corruptReads = false; returnExistingId = null; denyListing = false
            File(context.filesDir, "saf-fixture").listFiles()?.filter { it.isFile }?.forEach { check(it.delete()) }
        }
    }
    override fun onCreate() = true
    private fun directory(): File = File(checkNotNull(context).filesDir, "saf-fixture").apply { check(isDirectory || mkdirs()) }
    private fun access() { if (denyAccess) throw SecurityException("Injected test provider denial") }
    private fun file(id: String): File {
        require(id !in setOf("root", ".", "..") && id.matches(Regex("[A-Za-z0-9_.-]+")))
        return File(directory(), id)
    }
    private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE))
    private fun row(cursor: MatrixCursor, id: String) {
        val file = if (id == "root") directory() else file(id)
        val row = cursor.newRow()
        cursor.columnNames.forEach { column -> row.add(when (column) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> if (id == "root") "Synthetic backup fixtures" else file.name
            Document.COLUMN_MIME_TYPE -> if (id == "root") Document.MIME_TYPE_DIR else "application/json"
            Document.COLUMN_FLAGS -> if (id == "root") Document.FLAG_DIR_SUPPORTS_CREATE
                else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
            Document.COLUMN_SIZE -> file.length()
            else -> null
        }) }
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS))
        val row = cursor.newRow()
        cursor.columnNames.forEach { row.add(when (it) {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> "root"
            Root.COLUMN_TITLE -> "Synthetic backup fixtures"
            Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE
            else -> null
        }) }
        return cursor
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        access(); return cursor(projection).also { row(it, documentId) }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        access(); require(parentDocumentId == "root")
        if (denyListing) throw java.io.IOException("Injected listing failure")
        return cursor(projection).also { out -> directory().listFiles()?.filter { it.isFile }?.forEach { row(out, it.name) } }
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        access(); require(parentDocumentId == "root")
        returnExistingId?.let { return it }
        require(displayName.matches(Regex("[A-Za-z0-9_.-]+")))
        val id = displayName.removeSuffix(".json") + "-" + UUID.randomUUID() + ".json"
        check(file(id).createNewFile()); return id
    }
    override fun deleteDocument(documentId: String) { access(); check(file(documentId).delete()) }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == "root" && (documentId == "root" || file(documentId).isFile)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        access()
        val target = if (corruptReads && mode == "r") File(directory(), "corrupt-fixture.bin").apply { writeText("corrupt") }
            else file(documentId)
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }
}
